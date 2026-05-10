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

private const val SHOT_TARGET_W = 1024
private const val SHOT_TARGET_H = 768
private const val SHOT_QUALITY = 80
private const val STREAM_TARGET_W = 480
private const val STREAM_TARGET_H = 360
private const val STREAM_QUALITY = 50

private const val FRAME_KIND_SHOT = "shot"
private const val FRAME_KIND_STREAM = "stream"

enum class BridgeStatus { DISCONNECTED, CONNECTING, CONNECTED }

/**
 * Glass-local capture state. The glass owns capture lifecycle entirely; phone
 * is a passive viewer that just renders incoming frames. State transitions
 * happen on user input (tap / swipe) and on local camera callbacks.
 */
enum class CaptureState {
    IDLE,
    CAPTURING,   // single shot in flight
    CAPTURED,    // shot sent, showing brief feedback (auto reset)
    FAILED,      // capture or send failed (auto reset)
    STREAMING,   // continuous capture in flight
}

enum class CaptureMode { SHOT, STREAM }

/**
 * State + protocol owner on the glass. Wire format:
 *   phone → glass:  session_open / ping / session_close
 *   glass → phone:  frame (binary) / stream_start / stream_end
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

    private val _mode = MutableStateFlow(CaptureMode.SHOT)
    val mode: StateFlow<CaptureMode> = _mode.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())

    // Stream send pipeline: camera (producer) → senderHandler (consumer).
    // Capture period is fixed by GlassCamera; sending is decoupled here so
    // BT send time doesn't drag the capture cadence.
    //
    // Buffering = "in-flight 1 + queued slot 1". Producer puts the latest frame
    // into `streamSlot`; if a queued frame was already there it's overwritten
    // (newer wins) and logged as "skipped". Consumer drains the slot, sends,
    // then re-checks. Each frame carries its own captureTs so phone can place
    // frames on a timeline and gap-fill on video assembly.
    private var senderThread: HandlerThread? = null
    private var senderHandler: Handler? = null
    private val streamSlot = AtomicReference<StreamFrame?>(null)
    private var lastFrameProducedAt = 0L

    private data class StreamFrame(
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
        when (_mode.value) {
            CaptureMode.SHOT -> tapInShotMode()
            CaptureMode.STREAM -> tapInStreamMode()
        }
    }

    fun toggleMode() {
        if (_status.value != BridgeStatus.CONNECTED || !_sessionOpen.value) {
            Log.d(TAG, "toggleMode ignored (no session)")
            return
        }
        if (_captureState.value == CaptureState.STREAMING) {
            stopStreaming("mode toggle")
        }
        _mode.value = when (_mode.value) {
            CaptureMode.SHOT -> CaptureMode.STREAM
            CaptureMode.STREAM -> CaptureMode.SHOT
        }
        sendModeChange()
    }

    private fun tapInShotMode() {
        if (_captureState.value != CaptureState.IDLE) {
            Log.d(TAG, "shot tap ignored (state=${_captureState.value})")
            return
        }
        val ctx = appContext ?: run {
            Log.w(TAG, "shot tap dropped: no appContext")
            return
        }
        cancelResultReset()
        _captureState.value = CaptureState.CAPTURING
        GlassCamera.takeShot(
            ctx = ctx,
            width = SHOT_TARGET_W,
            height = SHOT_TARGET_H,
            quality = SHOT_QUALITY,
            onSuccess = { jpeg, w, h, rot, captureTs ->
                mainHandler.post { onShotCaptured(jpeg, w, h, rot, captureTs) }
            },
            onError = { reason ->
                mainHandler.post { onShotFailed(reason) }
            },
        )
    }

    private fun tapInStreamMode() {
        when (_captureState.value) {
            CaptureState.IDLE -> startStreaming()
            CaptureState.STREAMING -> stopStreaming("user tap")
            else -> Log.d(TAG, "stream tap ignored (state=${_captureState.value})")
        }
    }

    // ---- shot ----

    private fun onShotCaptured(jpeg: ByteArray, w: Int, h: Int, rot: Int, captureTs: Long) {
        if (_captureState.value != CaptureState.CAPTURING) {
            Log.w(TAG, "shot result in unexpected state=${_captureState.value} (dropped)")
            return
        }
        val sent = sendFrame(FRAME_KIND_SHOT, jpeg, w, h, rot, captureTs)
        if (sent) {
            CameraSfx.playShutter()
            scheduleResetTo(CaptureState.CAPTURED)
        } else {
            CameraSfx.playFail()
            scheduleResetTo(CaptureState.FAILED)
        }
    }

    private fun onShotFailed(reason: String) {
        Log.w(TAG, "shot failed: $reason")
        if (_captureState.value != CaptureState.CAPTURING) return
        CameraSfx.playFail()
        scheduleResetTo(CaptureState.FAILED)
    }

    // ---- stream ----

    private fun startStreaming() {
        val ctx = appContext ?: run {
            Log.w(TAG, "startStreaming dropped: no appContext")
            return
        }
        cancelResultReset()
        _captureState.value = CaptureState.STREAMING
        sendStreamStart()
        startSender()
        GlassCamera.startStream(
            ctx = ctx,
            width = STREAM_TARGET_W,
            height = STREAM_TARGET_H,
            quality = STREAM_QUALITY,
            onFrame = ::onStreamFrameProduced,
            onError = { reason ->
                mainHandler.post { onStreamFailed(reason) }
            },
        )
    }

    private fun onStreamFrameProduced(jpeg: ByteArray, w: Int, h: Int, rot: Int, captureTs: Long) {
        val now = SystemClock.uptimeMillis()
        val gap = if (lastFrameProducedAt > 0L) now - lastFrameProducedAt else 0L
        lastFrameProducedAt = now

        val incoming = StreamFrame(jpeg, w, h, rot, captureTs)
        // Newer wins: overwrite the queued slot. If something was already there
        // (= consumer was busy with the previous in-flight send AND a queued
        // frame existed waiting), we just discarded a frame the receiver will
        // never see. Phone fills these gaps via captureTs on assembly.
        val replaced = streamSlot.getAndSet(incoming)
        if (replaced != null) {
            Log.w(TAG, "queued frame skipped: newer overwrote (gap=${gap}ms oldSize=${replaced.jpeg.size} newSize=${jpeg.size})")
        }
        senderHandler?.post(::drainSlotOnce)
    }

    /**
     * Sender-side drain. Posted by producer for every new frame. Multiple
     * pending posts are harmless — whoever runs while the slot has content
     * sends it; later runs that find an empty slot are no-ops.
     */
    private fun drainSlotOnce() {
        val f = streamSlot.getAndSet(null) ?: return
        val t0 = SystemClock.uptimeMillis()
        val ok = try {
            sendFrame(FRAME_KIND_STREAM, f.jpeg, f.w, f.h, f.rot, f.captureTs)
        } catch (t: Throwable) {
            Log.w(TAG, "stream sendFrame threw", t); false
        }
        Log.d(TAG, "stream send: send=${SystemClock.uptimeMillis() - t0}ms ok=$ok size=${f.jpeg.size}")
    }

    private fun startSender() {
        if (senderThread != null) return
        senderThread = HandlerThread("glass-bt-sender").also { it.start() }
        senderHandler = Handler(senderThread!!.looper)
        streamSlot.set(null)
        lastFrameProducedAt = 0L
    }

    private fun stopSender() {
        senderThread?.quitSafely()
        senderThread = null
        senderHandler = null
        streamSlot.set(null)
        lastFrameProducedAt = 0L
    }

    private fun stopStreaming(reason: String) {
        if (_captureState.value != CaptureState.STREAMING) return
        Log.d(TAG, "stopStreaming: $reason")
        GlassCamera.stopStream()
        sendEvent("stream_end")
        stopSender()
        _captureState.value = CaptureState.IDLE
    }

    private fun onStreamFailed(reason: String) {
        Log.w(TAG, "stream failed: $reason")
        if (_captureState.value != CaptureState.STREAMING) return
        GlassCamera.stopStream()
        sendEvent("stream_end")
        stopSender()
        CameraSfx.playFail()
        scheduleResetTo(CaptureState.FAILED)
    }

    // ---- helpers ----

    private fun resetCapture(reason: String) {
        Log.d(TAG, "resetCapture: $reason (was ${_captureState.value})")
        cancelResultReset()
        GlassCamera.stopStream()
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

    private fun sendStreamStart() {
        val b = bridge ?: run {
            Log.w(TAG, "sendStreamStart dropped: bridge null")
            return
        }
        Log.d(TAG, "-> stream_start period_ms=$STREAM_FRAME_PERIOD_MS")
        val caps = Caps().apply {
            write("event"); write("stream_start")
            write("ts"); writeInt64(System.currentTimeMillis())
            write("period_ms"); writeInt64(STREAM_FRAME_PERIOD_MS)
        }
        runCatching { b.sendMessage(CHANNEL_TO_PHONE, caps) }
            .onFailure { Log.w(TAG, "sendMessage(stream_start) failed", it) }
    }

    private fun sendModeChange() {
        val b = bridge ?: return
        val modeStr = if (_mode.value == CaptureMode.SHOT) "shot" else "stream"
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
