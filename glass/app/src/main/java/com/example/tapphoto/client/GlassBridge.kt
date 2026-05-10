package com.example.tapphoto.client

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.rokid.cxr.CXRServiceBridge
import com.rokid.cxr.Caps
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "GlassBridge"
private const val CHANNEL_FROM_PHONE = "rk_custom_client"
private const val CHANNEL_TO_PHONE = "rk_custom_key"
private const val PING_TIMEOUT_MS = 12_000L
private const val RESULT_RESET_MS = 2_000L

private const val PHOTO_TARGET_W = 1024
private const val PHOTO_TARGET_H = 768
private const val PHOTO_QUALITY = 80
private const val VIDEO_TARGET_W = 480
private const val VIDEO_TARGET_H = 360
private const val VIDEO_QUALITY = 50

private const val FRAME_KIND_PHOTO = "photo"
private const val FRAME_KIND_VIDEO = "video"
private const val FRAME_KIND_MOVIE = "movie"

enum class BridgeStatus { DISCONNECTED, CONNECTING, CONNECTED }

/**
 * Glass-local capture state. Only 5 values:
 *   IDLE       — nothing happening, ready for tap
 *   CAPTURING  — single PHOTO request in flight
 *   CAPTURED   — PHOTO sent, showing brief feedback (auto reset in 2s)
 *   FAILED     — capture or send failed (auto reset in 2s)
 *   RUNNING    — continuous mode active (VIDEO / AUDIO / MOVIE)
 *
 * The mode (`CaptureMode`) tells you *what kind of* RUNNING (video-only,
 * audio-only, both); the state machine itself doesn't need three flavors.
 */
enum class CaptureState { IDLE, CAPTURING, CAPTURED, FAILED, RUNNING }

/**
 * Output media kind, parallel set:
 *   PHOTO  — single image
 *   VIDEO  — continuous frames, no audio
 *   AUDIO  — continuous audio, no video
 *   MOVIE  — VIDEO + AUDIO together
 */
enum class CaptureMode { PHOTO, VIDEO, AUDIO, MOVIE }

private fun CaptureMode.usesCamera(): Boolean = this != CaptureMode.AUDIO
private fun CaptureMode.usesAudio(): Boolean = this == CaptureMode.AUDIO || this == CaptureMode.MOVIE
private fun CaptureMode.isContinuous(): Boolean = this != CaptureMode.PHOTO

private fun CaptureMode.wireString(): String = when (this) {
    CaptureMode.PHOTO -> "photo"
    CaptureMode.VIDEO -> "video"
    CaptureMode.AUDIO -> "audio"
    CaptureMode.MOVIE -> "movie"
}

/** Frame.kind for the streaming continuous modes. PHOTO uses [FRAME_KIND_PHOTO] directly. */
private fun CaptureMode.continuousFrameKind(): String = when (this) {
    CaptureMode.VIDEO -> FRAME_KIND_VIDEO
    CaptureMode.MOVIE -> FRAME_KIND_MOVIE
    else -> FRAME_KIND_VIDEO
}

/**
 * State + protocol owner on the glass. Wire format:
 *   phone → glass:  session_open / ping / session_close
 *   glass → phone:  frame{kind} / capture_start{kind, period_ms?} / capture_end /
 *                   mode_change{mode}
 */
object GlassBridge {
    private var bridge: CXRServiceBridge? = null
    private var appContext: Context? = null

    private val _status = MutableStateFlow(BridgeStatus.DISCONNECTED)
    val status: StateFlow<BridgeStatus> = _status.asStateFlow()

    private val _sessionOpen = MutableStateFlow(false)
    val sessionOpen: StateFlow<Boolean> = _sessionOpen.asStateFlow()

    private val _captureState = MutableStateFlow(CaptureState.IDLE)
    val captureState: StateFlow<CaptureState> = _captureState.asStateFlow()

    private val _mode = MutableStateFlow(CaptureMode.PHOTO)
    val mode: StateFlow<CaptureMode> = _mode.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())

    // Frame send pipeline: camera (producer) → senderHandler (consumer).
    // Capture period is fixed by GlassCamera; sending is decoupled here so
    // BT send time doesn't drag the capture cadence.
    //
    // Buffering = "in-flight 1 + queued slot 1". Producer puts the latest
    // frame into `frameSlot`; if a queued frame was already there it's
    // overwritten (newer wins) and logged as "skipped". Consumer drains the
    // slot and sends. Each frame carries its own captureTs so phone can place
    // frames on a timeline and gap-fill on assembly.
    private var senderThread: HandlerThread? = null
    private var senderHandler: Handler? = null
    private val frameSlot = AtomicReference<Frame?>(null)
    private var lastFrameProducedAt = 0L

    private data class Frame(
        val jpeg: ByteArray,
        val w: Int,
        val h: Int,
        val rot: Int,
        val captureTs: Long,
    )

    private val pingTimeoutRunnable = Runnable {
        Log.d(TAG, "ping timeout")
        _sessionOpen.value = false
        resetCapture("ping timeout")
    }
    private val resultResetRunnable = Runnable {
        if (_captureState.value == CaptureState.CAPTURED || _captureState.value == CaptureState.FAILED) {
            _captureState.value = CaptureState.IDLE
        }
    }

    fun init(context: Context) {
        if (bridge != null) return
        appContext = context.applicationContext
        bridge = CXRServiceBridge().apply {
            setStatusListener(object : CXRServiceBridge.StatusListener {
                override fun onConnected(p0: String?, p1: String?, p2: Int) {
                    Log.d(TAG, "onConnected pkg=$p0")
                    _status.value = BridgeStatus.CONNECTED
                }

                override fun onDisconnected() {
                    Log.d(TAG, "onDisconnected")
                    _status.value = BridgeStatus.DISCONNECTED
                    _sessionOpen.value = false
                    resetCapture("disconnected")
                    stopPingWatchdog()
                }

                override fun onConnecting(p0: String?, p1: String?, p2: Int) {
                    Log.d(TAG, "onConnecting pkg=$p0")
                    _status.value = BridgeStatus.CONNECTING
                }

                override fun onARTCStatus(p0: Float, p1: Boolean) {}
                override fun onRokidAccountChanged(p0: String?) {}
            })
            subscribe(CHANNEL_FROM_PHONE, object : CXRServiceBridge.MsgCallback {
                override fun onReceive(name: String?, args: Caps?, bytes: ByteArray?) {
                    val event = readEvent(args) ?: return
                    if (event != "ping") Log.d(TAG, "<- $event  state=${_captureState.value}")
                    mainHandler.post { handlePhoneEvent(event) }
                }
            })
        }
    }

    // ---- phone → glass ----

    private fun handlePhoneEvent(event: String) {
        when (event) {
            "session_open" -> {
                _sessionOpen.value = true
                armPingWatchdog()
                sendModeChange()  // resync: phone may have just (re)connected
            }
            "ping" -> {
                _sessionOpen.value = true
                armPingWatchdog()
            }
            "session_close" -> {
                _sessionOpen.value = false
                stopPingWatchdog()
                resetCapture("session_close")
            }
        }
    }

    // ---- user input ----

    fun onTap() {
        if (_status.value != BridgeStatus.CONNECTED || !_sessionOpen.value) {
            Log.d(TAG, "tap ignored (status=${_status.value} sessionOpen=${_sessionOpen.value})")
            return
        }
        val mode = _mode.value
        if (!mode.isContinuous()) {
            tapPhoto()
            return
        }
        when (_captureState.value) {
            CaptureState.IDLE -> startContinuous(mode)
            CaptureState.RUNNING -> stopContinuous("user tap")
            else -> Log.d(TAG, "$mode tap ignored (state=${_captureState.value})")
        }
    }

    fun toggleMode() {
        if (_status.value != BridgeStatus.CONNECTED || !_sessionOpen.value) {
            Log.d(TAG, "toggleMode ignored (no session)")
            return
        }
        if (_captureState.value == CaptureState.RUNNING) {
            stopContinuous("mode toggle")
        }
        _mode.value = when (_mode.value) {
            CaptureMode.PHOTO -> CaptureMode.VIDEO
            CaptureMode.VIDEO -> CaptureMode.AUDIO
            CaptureMode.AUDIO -> CaptureMode.MOVIE
            CaptureMode.MOVIE -> CaptureMode.PHOTO
        }
        sendModeChange()
    }

    // ---- PHOTO ----

    private fun tapPhoto() {
        if (_captureState.value != CaptureState.IDLE) {
            Log.d(TAG, "photo tap ignored (state=${_captureState.value})")
            return
        }
        val ctx = appContext ?: run {
            Log.w(TAG, "photo tap dropped: no appContext")
            return
        }
        cancelResultReset()
        _captureState.value = CaptureState.CAPTURING
        GlassCamera.takePhoto(
            ctx = ctx,
            width = PHOTO_TARGET_W,
            height = PHOTO_TARGET_H,
            quality = PHOTO_QUALITY,
            onSuccess = { jpeg, w, h, rot, captureTs ->
                mainHandler.post { onPhotoCaptured(jpeg, w, h, rot, captureTs) }
            },
            onError = { reason -> mainHandler.post { onPhotoFailed(reason) } },
        )
    }

    private fun onPhotoCaptured(jpeg: ByteArray, w: Int, h: Int, rot: Int, captureTs: Long) {
        if (_captureState.value != CaptureState.CAPTURING) {
            Log.w(TAG, "photo result in unexpected state=${_captureState.value} (dropped)")
            return
        }
        val sent = sendFrame(FRAME_KIND_PHOTO, jpeg, w, h, rot, captureTs)
        if (sent) {
            CameraSfx.playShutter()
            scheduleResetTo(CaptureState.CAPTURED)
        } else {
            CameraSfx.playFail()
            scheduleResetTo(CaptureState.FAILED)
        }
    }

    private fun onPhotoFailed(reason: String) {
        Log.w(TAG, "photo failed: $reason")
        if (_captureState.value != CaptureState.CAPTURING) return
        CameraSfx.playFail()
        scheduleResetTo(CaptureState.FAILED)
    }

    // ---- VIDEO / AUDIO / MOVIE (continuous) ----

    private fun startContinuous(mode: CaptureMode) {
        val ctx = appContext ?: run {
            Log.w(TAG, "startContinuous dropped: no appContext")
            return
        }
        cancelResultReset()
        _captureState.value = CaptureState.RUNNING
        sendCaptureStart(mode)
        if (mode.usesCamera()) {
            startSender()
            GlassCamera.startContinuous(
                ctx = ctx,
                width = VIDEO_TARGET_W,
                height = VIDEO_TARGET_H,
                quality = VIDEO_QUALITY,
                onFrame = ::onContinuousFrameProduced,
                onError = { reason -> mainHandler.post { onContinuousFailed(reason) } },
            )
        }
        // AUDIO mode has no glass-side capture loop — phone owns the audio
        // stream subscription via Hi Rokid SDK.
    }

    private fun stopContinuous(reason: String) {
        if (_captureState.value != CaptureState.RUNNING) return
        val mode = _mode.value
        Log.d(TAG, "stopContinuous: $reason (mode=$mode)")
        if (mode.usesCamera()) {
            GlassCamera.stopContinuous()
            stopSender()
        }
        sendEvent("capture_end")
        _captureState.value = CaptureState.IDLE
    }

    private fun onContinuousFailed(reason: String) {
        Log.w(TAG, "continuous failed: $reason (mode=${_mode.value})")
        if (_captureState.value != CaptureState.RUNNING) return
        if (_mode.value.usesCamera()) {
            GlassCamera.stopContinuous()
            stopSender()
        }
        sendEvent("capture_end")
        CameraSfx.playFail()
        scheduleResetTo(CaptureState.FAILED)
    }

    private fun onContinuousFrameProduced(jpeg: ByteArray, w: Int, h: Int, rot: Int, captureTs: Long) {
        val now = SystemClock.uptimeMillis()
        val gap = if (lastFrameProducedAt > 0L) now - lastFrameProducedAt else 0L
        lastFrameProducedAt = now
        val incoming = Frame(jpeg, w, h, rot, captureTs)
        val replaced = frameSlot.getAndSet(incoming)
        if (replaced != null) {
            Log.w(TAG, "queued frame skipped: newer overwrote (gap=${gap}ms oldSize=${replaced.jpeg.size} newSize=${jpeg.size})")
        }
        senderHandler?.post(::drainSlotOnce)
    }

    /**
     * Sender-side drain. Posted by producer for every new frame. Multiple
     * pending posts are harmless — whoever runs while the slot has content
     * sends it; later runs that find an empty slot are no-ops. The frame
     * `kind` is derived from the current mode at send time (no separate
     * volatile field needed).
     */
    private fun drainSlotOnce() {
        val f = frameSlot.getAndSet(null) ?: return
        val kind = _mode.value.continuousFrameKind()
        val t0 = SystemClock.uptimeMillis()
        val ok = try {
            sendFrame(kind, f.jpeg, f.w, f.h, f.rot, f.captureTs)
        } catch (t: Throwable) {
            Log.w(TAG, "frame send threw", t); false
        }
        Log.d(TAG, "frame send($kind): send=${SystemClock.uptimeMillis() - t0}ms ok=$ok size=${f.jpeg.size}")
    }

    private fun startSender() {
        if (senderThread != null) return
        senderThread = HandlerThread("glass-bt-sender").also { it.start() }
        senderHandler = Handler(senderThread!!.looper)
        frameSlot.set(null)
        lastFrameProducedAt = 0L
    }

    private fun stopSender() {
        senderThread?.quitSafely()
        senderThread = null
        senderHandler = null
        frameSlot.set(null)
        lastFrameProducedAt = 0L
    }

    // ---- helpers ----

    private fun resetCapture(reason: String) {
        Log.d(TAG, "resetCapture: $reason (was ${_captureState.value})")
        cancelResultReset()
        // Stops camera if it was running; no-op for AUDIO. Phone observes
        // session_close / disconnect via its own watchdog and stops its audio
        // stream subscription there.
        GlassCamera.stopContinuous()
        stopSender()
        _captureState.value = CaptureState.IDLE
    }

    private fun scheduleResetTo(terminal: CaptureState) {
        require(terminal == CaptureState.CAPTURED || terminal == CaptureState.FAILED)
        _captureState.value = terminal
        cancelResultReset()
        mainHandler.postDelayed(resultResetRunnable, RESULT_RESET_MS)
    }

    private fun cancelResultReset() {
        mainHandler.removeCallbacks(resultResetRunnable)
    }

    private fun armPingWatchdog() {
        mainHandler.removeCallbacks(pingTimeoutRunnable)
        mainHandler.postDelayed(pingTimeoutRunnable, PING_TIMEOUT_MS)
    }

    private fun stopPingWatchdog() {
        mainHandler.removeCallbacks(pingTimeoutRunnable)
    }

    private fun sendEvent(event: String) {
        val b = bridge ?: run {
            Log.w(TAG, "sendEvent($event) dropped: bridge null")
            return
        }
        Log.d(TAG, "-> $event")
        val caps = Caps().apply {
            write("event"); write(event)
            write("ts"); writeInt64(System.currentTimeMillis())
        }
        runCatching { b.sendMessage(CHANNEL_TO_PHONE, caps) }
            .onFailure { Log.w(TAG, "sendMessage($event) failed", it) }
    }

    private fun sendCaptureStart(mode: CaptureMode) {
        val b = bridge ?: run {
            Log.w(TAG, "sendCaptureStart dropped: bridge null")
            return
        }
        val kind = mode.wireString()
        val withPeriod = mode.usesCamera()
        Log.d(TAG, "-> capture_start kind=$kind${if (withPeriod) " period_ms=$CAMERA_FRAME_PERIOD_MS" else ""}")
        val caps = Caps().apply {
            write("event"); write("capture_start")
            write("kind"); write(kind)
            write("ts"); writeInt64(System.currentTimeMillis())
            if (withPeriod) {
                write("period_ms"); writeInt64(CAMERA_FRAME_PERIOD_MS)
            }
        }
        runCatching { b.sendMessage(CHANNEL_TO_PHONE, caps) }
            .onFailure { Log.w(TAG, "sendCaptureStart failed", it) }
    }

    private fun sendModeChange() {
        val b = bridge ?: return
        val modeStr = _mode.value.wireString()
        Log.d(TAG, "-> mode_change ($modeStr)")
        val caps = Caps().apply {
            write("event"); write("mode_change")
            write("mode"); write(modeStr)
            write("ts"); writeInt64(System.currentTimeMillis())
        }
        runCatching { b.sendMessage(CHANNEL_TO_PHONE, caps) }
            .onFailure { Log.w(TAG, "sendModeChange failed", it) }
    }

    private fun sendFrame(kind: String, jpeg: ByteArray, w: Int, h: Int, rot: Int, captureTs: Long): Boolean {
        val b = bridge ?: return false
        val caps = Caps().apply {
            write("event"); write("frame")
            write("kind"); write(kind)
            write("w"); writeInt32(w)
            write("h"); writeInt32(h)
            write("rot"); writeInt32(rot)
            write("ts"); writeInt64(captureTs)  // capture time, not send time
            write("data"); write(jpeg)
        }
        val result = runCatching { b.sendMessage(CHANNEL_TO_PHONE, caps) }
            .onFailure { Log.w(TAG, "frame send threw", it) }
            .getOrDefault(-1)
        if (result != 0) Log.w(TAG, "frame send result=$result kind=$kind size=${jpeg.size}")
        return result == 0
    }

    private fun readEvent(caps: Caps?): String? {
        if (caps == null) return null
        for (i in 0 until caps.size() - 1) {
            val key = caps.at(i)
            if (key.type() == Caps.Value.TYPE_STRING && key.string == "event") {
                val v = caps.at(i + 1)
                if (v.type() == Caps.Value.TYPE_STRING) return v.string
            }
        }
        return null
    }
}
