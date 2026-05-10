package com.example.tapphoto.host

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.app.NotificationCompat
import com.example.cxrglobal.CXRLink
import com.example.cxrglobal.CxrDefs
import com.example.cxrglobal.callbacks.IAudioStreamCbk
import com.example.cxrglobal.callbacks.ICXRLinkCbk
import com.example.cxrglobal.callbacks.ICustomCmdCbk
import com.example.cxrglobal.callbacks.IGlassAppCbk
import com.rokid.cxr.Caps
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "ConnectionService"
private const val CHANNEL_ID = "cxrl_connection"
private const val NOTIF_ID = 1
private const val GLASS_APP_PKG = "com.example.tapphoto.client"
private const val GLASS_MAIN_ACTIVITY = "com.example.tapphoto.client.MainActivity"
private const val CHANNEL_TO_GLASS = "rk_custom_client"
private const val CHANNEL_FROM_GLASS = "rk_custom_key"
private const val HEARTBEAT_INTERVAL_MS = 5_000L

/**
 * Capture mode mirrored from glass. Used both for the UI status display
 * (`mode_change` event) and as the session kind on `capture_start` for
 * newer-wins arbitration. The two contexts always carry the same vocabulary
 * so we keep a single enum.
 */
enum class GlassMode { PHOTO, VIDEO, AUDIO, MOVIE }

private fun parseGlassMode(s: String): GlassMode? = when (s) {
    "photo" -> GlassMode.PHOTO
    "video" -> GlassMode.VIDEO
    "audio" -> GlassMode.AUDIO
    "movie" -> GlassMode.MOVIE
    else -> null
}

private fun GlassMode.usesVideo(): Boolean = this == GlassMode.VIDEO || this == GlassMode.MOVIE
private fun GlassMode.usesAudio(): Boolean = this == GlassMode.AUDIO || this == GlassMode.MOVIE

/**
 * Foreground service that holds the CXRL link and renders frames coming from
 * the glass. Wire format:
 *   phone → glass:  session_open / ping / session_close
 *   glass → phone:  frame{kind} / capture_start{kind, period_ms?} / capture_end /
 *                   mode_change{mode}
 *
 * In addition the Hi Rokid SDK delivers raw PCM to phone via IAudioStreamCbk
 * (separate AIDL channel, not Caps).
 */
class ConnectionService : Service() {

    private var cxrLink: CXRLink? = null
    private var lConnected = false
    private var btConnected = false
    private var appStarted = false

    private val mainHandler = Handler(Looper.getMainLooper())

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            sendCapsEvent("ping")
            mainHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat("待機中")
        _running.value = true

        val token = TokenStore.token.value
        if (token.isNullOrBlank()) {
            Log.w(TAG, "no token, foreground only (idle)")
            updateConnectionState(CxrConnState.DISCONNECTED, "token なし — 認証してください")
            return START_STICKY
        }

        startLink(token)
        return START_STICKY
    }

    override fun onDestroy() {
        _running.value = false
        mainHandler.removeCallbacksAndMessages(null)
        sendCapsEvent("session_close")
        // Stop any in-flight audio stream so the SDK doesn't keep pushing PCM
        // to a callback whose AudioRecorder file we're about to discard.
        if (AudioRecorder.recording.value) {
            runCatching { cxrLink?.stopAudioStream() }
            AudioRecorder.stopRecording()
        }
        runCatching { cxrLink?.disconnect() }
        cxrLink = null
        lConnected = false
        btConnected = false
        appStarted = false
        _videoActive.value = false
        FpsTracker.reset()
        _connState.value = CxrConnState.DISCONNECTED
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startLink(token: String) {
        Log.d(TAG, "startLink token len=${token.length}")
        updateConnectionState(CxrConnState.CONNECTING, "接続中…")
        cxrLink = CXRLink(this).apply {
            configCXRSession(
                CxrDefs.CXRSession(CxrDefs.CXRSessionType.CUSTOMAPP, GLASS_APP_PKG),
            )
            setCXRLinkCbk(object : ICXRLinkCbk {
                override fun onCXRLConnected(connected: Boolean) {
                    Log.d(TAG, "onCXRLConnected: $connected")
                    lConnected = connected
                    refreshConnState()
                }

                override fun onGlassBtConnected(connected: Boolean) {
                    Log.d(TAG, "onGlassBtConnected: $connected")
                    btConnected = connected
                    refreshConnState()
                }

                override fun onGlassAiAssistStart() {}
                override fun onGlassAiAssistStop() {}
            })
            setCXRCustomCmdCbk(object : ICustomCmdCbk {
                override fun onCustomCmdResult(key: String, payload: ByteArray) {
                    if (key != CHANNEL_FROM_GLASS) return
                    val caps = Caps.fromBytes(payload) ?: return
                    val event = readEvent(caps) ?: return
                    mainHandler.post { handleGlassMessage(event, caps) }
                }
            })
            // PCM arrives on a binder thread; AudioRecorder writes are
            // synchronized internally so we don't bounce back to mainHandler.
            setCXRAudioCbk(object : IAudioStreamCbk {
                override fun onAudioReceived(data: ByteArray, offset: Int, length: Int) {
                    AudioRecorder.onAudioChunk(data, offset, length)
                }

                override fun onAudioError(code: Int, msg: String?) {
                    Log.w(TAG, "audio error code=$code msg=$msg")
                }

                override fun onAudioStreamStateChanged(started: Boolean) {
                    Log.d(TAG, "audio stream state changed: started=$started")
                }
            })
            connect(token)
        }
    }

    // ---- glass → phone ----

    private fun handleGlassMessage(event: String, caps: Caps) {
        when (event) {
            "frame" -> handleFrame(caps)
            "capture_start" -> handleCaptureStart(caps)
            "capture_end" -> handleCaptureEnd()
            "mode_change" -> handleModeChange(caps)
            else -> Log.d(TAG, "ignore unknown glass event: $event")
        }
    }

    private fun handleModeChange(caps: Caps) {
        val modeStr = readString(caps, "mode") ?: return
        val mode = parseGlassMode(modeStr) ?: run {
            Log.w(TAG, "mode_change unknown mode=$modeStr")
            return
        }
        Log.d(TAG, "<- mode_change $modeStr")
        _glassMode.value = mode
    }

    private fun handleFrame(caps: Caps) {
        val frame = GlassImage.parse(caps) ?: return
        val bitmap = GlassImage.decode(frame) ?: return
        PhotoStore.set(bitmap.asImageBitmap(), frame)
        if (_videoActive.value) {
            FpsTracker.tick()
            VideoRecorder.add(frame)
        } else if (frame.kind == "photo") {
            // a fresh photo supersedes any previously recorded session content
            switchSession(GlassMode.PHOTO)
        }
    }

    /**
     * Single capture-session entry point. Glass tells us what kind via
     * [GlassMode] and (for video-bearing kinds) the camera period. We
     * arbitrate "newer wins" across PhotoStore / VideoRecorder / AudioRecorder
     * here so each individual handler doesn't have to remember which buffers
     * to clear.
     */
    private fun handleCaptureStart(caps: Caps) {
        val kindStr = readString(caps, "kind") ?: return
        val kind = parseGlassMode(kindStr) ?: run {
            Log.w(TAG, "capture_start unknown kind=$kindStr")
            return
        }
        if (kind == GlassMode.PHOTO) {
            Log.w(TAG, "capture_start ignored: photo doesn't use sessions")
            return
        }
        val periodMs = readInt64(caps, "period_ms") ?: 1000L
        Log.d(TAG, "<- capture_start kind=$kindStr period_ms=$periodMs")
        switchSession(kind)
        if (kind.usesVideo()) {
            VideoRecorder.startNewSession(periodMs)
            _videoActive.value = true
            FpsTracker.reset()
        }
        if (kind.usesAudio()) {
            AudioRecorder.startNewSession(applicationContext)
            val ok = cxrLink?.startAudioStream(1)
            Log.d(TAG, "startAudioStream → $ok")
        }
    }

    private fun handleCaptureEnd() {
        Log.d(TAG, "<- capture_end (videoActive=${_videoActive.value})")
        if (AudioRecorder.recording.value) {
            val ok = cxrLink?.stopAudioStream()
            Log.d(TAG, "stopAudioStream → $ok")
            AudioRecorder.stopRecording()
        }
        if (_videoActive.value) {
            _videoActive.value = false
            FpsTracker.reset()
        }
    }

    /**
     * "Newer wins" — clear buffers that are NOT relevant to the incoming
     * session kind so save-time priority logic stays unambiguous. The
     * recorders that the new session will populate are reset by their own
     * `startNewSession` calls in the caller, so we only clear the others.
     */
    private fun switchSession(kind: GlassMode) {
        when (kind) {
            GlassMode.PHOTO -> {
                VideoRecorder.clear()
                AudioRecorder.clear()
            }
            GlassMode.VIDEO -> {
                PhotoStore.clear()
                AudioRecorder.clear()
            }
            GlassMode.AUDIO -> {
                PhotoStore.clear()
                VideoRecorder.clear()
            }
            GlassMode.MOVIE -> {
                PhotoStore.clear()
                // VideoRecorder + AudioRecorder will both be (re)started by
                // handleCaptureStart, so no explicit clear needed for them.
            }
        }
    }

    // ---- helpers ----

    private fun readEvent(caps: Caps): String? = readString(caps, "event")

    private fun readString(caps: Caps, fieldName: String): String? = runCatching {
        for (i in 0 until caps.size() - 1) {
            val key = caps.at(i)
            if (key.type() == Caps.Value.TYPE_STRING && key.string == fieldName) {
                val v = caps.at(i + 1)
                if (v.type() == Caps.Value.TYPE_STRING) return@runCatching v.string
            }
        }
        null
    }.getOrNull()

    private fun readInt64(caps: Caps, fieldName: String): Long? = runCatching {
        for (i in 0 until caps.size() - 1) {
            val key = caps.at(i)
            if (key.type() == Caps.Value.TYPE_STRING && key.string == fieldName) {
                val v = caps.at(i + 1)
                if (v.type() == Caps.Value.TYPE_INT64 || v.type() == Caps.Value.TYPE_UINT64) {
                    return@runCatching v.long
                }
            }
        }
        null
    }.getOrNull()

    private fun refreshConnState() {
        val state = when {
            lConnected && btConnected -> CxrConnState.CONNECTED
            else -> CxrConnState.CONNECTING
        }
        val text = when (state) {
            CxrConnState.CONNECTED -> "接続済み"
            CxrConnState.CONNECTING -> "接続中… (L=$lConnected BT=$btConnected)"
            CxrConnState.DISCONNECTED -> "切断"
        }
        updateConnectionState(state, text)
        if (state != CxrConnState.CONNECTED && _videoActive.value) {
            Log.d(TAG, "disconnect: clearing video active state")
            _videoActive.value = false
            FpsTracker.reset()
        }
        if (state == CxrConnState.CONNECTED && !appStarted) {
            appStarted = true
            Log.d(TAG, "appStart $GLASS_MAIN_ACTIVITY")
            cxrLink?.appStart(GLASS_MAIN_ACTIVITY, object : IGlassAppCbk {
                override fun onOpenAppResult(success: Boolean) {
                    Log.d(TAG, "onOpenAppResult: $success")
                    if (success) sendCapsEvent("session_open")
                }

                override fun onGlassAppResume(resume: Boolean) {
                    Log.d(TAG, "onGlassAppResume: $resume")
                    if (resume) sendCapsEvent("session_open")
                }
            })
        }
    }

    private fun sendCapsEvent(event: String) {
        val link = cxrLink ?: return
        if (event != "ping") Log.d(TAG, "-> $event")
        val payload = Caps().apply {
            write("event")
            write(event)
            write("ts")
            writeInt64(System.currentTimeMillis())
        }.serialize()
        runCatching { link.sendCustomCmd(CHANNEL_TO_GLASS, payload) }
            .onFailure { Log.w(TAG, "sendCapsEvent($event) failed", it) }
        when (event) {
            "session_open" -> {
                mainHandler.removeCallbacks(heartbeatRunnable)
                mainHandler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS)
            }
            "session_close" -> mainHandler.removeCallbacks(heartbeatRunnable)
        }
    }

    private fun updateConnectionState(state: CxrConnState, notifText: String) {
        _connState.value = state
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIF_ID, buildNotification(notifText))
    }

    private fun startForegroundCompat(text: String) {
        val notif = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val ch = NotificationChannel(
            CHANNEL_ID,
            "CXR 接続",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "CXRL 接続常駐通知" }
        manager.createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            this,
            0,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TapPhotoCxrl")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    companion object {
        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        private val _connState = MutableStateFlow(CxrConnState.DISCONNECTED)
        val connState: StateFlow<CxrConnState> = _connState.asStateFlow()

        private val _glassMode = MutableStateFlow(GlassMode.PHOTO)
        val glassMode: StateFlow<GlassMode> = _glassMode.asStateFlow()

        private val _videoActive = MutableStateFlow(false)
        val videoActive: StateFlow<Boolean> = _videoActive.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, ConnectionService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ConnectionService::class.java)
            context.stopService(intent)
        }
    }
}

enum class CxrConnState { DISCONNECTED, CONNECTING, CONNECTED }
