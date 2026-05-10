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
private const val HEARTBEAT_INTERVAL_MS = 5_000L

/**
 * Connection state mirrored to the UI. Same 3 values used everywhere on
 * phone (status notification, MainActivity, button enable/disable).
 */
enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

/**
 * Capture mode mirrored from glass. Used both for the UI status display
 * (`mode_change` event) and as the session kind on `capture_start` for
 * newer-wins arbitration. Wire form is the lowercase enum name.
 */
enum class CaptureMode {
    PHOTO, VIDEO, AUDIO, MOVIE;

    fun toWire(): String = name.lowercase()

    companion object {
        fun fromWire(s: String): CaptureMode? =
            entries.firstOrNull { it.name.equals(s, ignoreCase = true) }
    }
}

internal fun CaptureMode.usesCamera(): Boolean = this == CaptureMode.VIDEO || this == CaptureMode.MOVIE
internal fun CaptureMode.usesAudio(): Boolean = this == CaptureMode.AUDIO || this == CaptureMode.MOVIE

/**
 * Foreground service that holds the CXRL link and renders frames coming from
 * the glass. Wire format:
 *   phone → glass:  session_open / ping / session_close
 *   glass → phone:  frame / capture_start{kind, period_ms?} / capture_end /
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
            sendCapsEvent(Wire.EVENT_PING)
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
            updateConnectionState(ConnectionState.DISCONNECTED, "token なし — 認証してください")
            return START_STICKY
        }

        startLink(token)
        return START_STICKY
    }

    override fun onDestroy() {
        _running.value = false
        mainHandler.removeCallbacksAndMessages(null)
        sendCapsEvent(Wire.EVENT_SESSION_CLOSE)
        // Stop any in-flight audio stream so the SDK doesn't keep pushing PCM
        // to a callback whose AudioRecorder file we're about to discard.
        if (AudioRecorder.recording.value) {
            runCatching { cxrLink?.stopAudioStream() }
            AudioRecorder.stopRecording()
        }
        VideoRecorder.stopRecording()
        FpsTracker.reset()
        runCatching { cxrLink?.disconnect() }
        cxrLink = null
        lConnected = false
        btConnected = false
        appStarted = false
        _connState.value = ConnectionState.DISCONNECTED
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startLink(token: String) {
        Log.d(TAG, "startLink token len=${token.length}")
        updateConnectionState(ConnectionState.CONNECTING, "接続中…")
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
                    if (key != Wire.CHANNEL_FROM_GLASS) return
                    val caps = Caps.fromBytes(payload) ?: return
                    val event = caps.readEvent() ?: return
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
            Wire.EVENT_FRAME -> handleFrame(caps)
            Wire.EVENT_CAPTURE_START -> handleCaptureStart(caps)
            Wire.EVENT_CAPTURE_END -> handleCaptureEnd()
            Wire.EVENT_MODE_CHANGE -> handleModeChange(caps)
            else -> Log.d(TAG, "ignore unknown glass event: $event")
        }
    }

    private fun handleModeChange(caps: Caps) {
        val modeStr = caps.readString(Wire.FIELD_MODE) ?: return
        val mode = CaptureMode.fromWire(modeStr) ?: run {
            Log.w(TAG, "mode_change unknown mode=$modeStr")
            return
        }
        Log.d(TAG, "<- mode_change $modeStr")
        _captureMode.value = mode
        switchSessionBuffers(mode)
    }

    private fun handleFrame(caps: Caps) {
        val frame = GlassImage.parse(caps) ?: return
        val bitmap = GlassImage.decode(frame) ?: return
        PhotoStore.set(bitmap.asImageBitmap(), frame)
        if (VideoRecorder.recording.value) {
            FpsTracker.tick()
            VideoRecorder.add(frame)
        }
    }

    /**
     * Single capture-session entry point. Glass tells us what kind via
     * [CaptureMode] and (for video-bearing kinds) the camera period. The
     * mode_change that precedes capture_start has already cleared the stale
     * buffers; here we just (re)start the relevant recorders.
     */
    private fun handleCaptureStart(caps: Caps) {
        val kindStr = caps.readString(Wire.FIELD_KIND) ?: return
        val kind = CaptureMode.fromWire(kindStr) ?: run {
            Log.w(TAG, "capture_start unknown kind=$kindStr")
            return
        }
        if (kind == CaptureMode.PHOTO) {
            Log.w(TAG, "capture_start ignored: photo doesn't use sessions")
            return
        }
        val periodMs = caps.readInt64(Wire.FIELD_PERIOD_MS) ?: 1000L
        Log.d(TAG, "<- capture_start kind=$kindStr period_ms=$periodMs")
        switchSessionBuffers(kind)
        if (kind.usesCamera()) {
            VideoRecorder.startNewSession(periodMs)
            FpsTracker.reset()
        }
        if (kind.usesAudio()) {
            AudioRecorder.startNewSession(applicationContext.cacheDir)
            val ok = cxrLink?.startAudioStream(1)
            Log.d(TAG, "startAudioStream → $ok")
        }
    }

    private fun handleCaptureEnd() {
        Log.d(TAG, "<- capture_end (videoRecording=${VideoRecorder.recording.value})")
        if (AudioRecorder.recording.value) {
            val ok = cxrLink?.stopAudioStream()
            Log.d(TAG, "stopAudioStream → $ok")
            AudioRecorder.stopRecording()
        }
        if (VideoRecorder.recording.value) {
            VideoRecorder.stopRecording()
            FpsTracker.reset()
        }
    }

    /**
     * "Newer wins" — clear buffers that are NOT relevant to the incoming
     * mode/kind so save-time priority logic stays unambiguous. Recorders
     * that the new session will repopulate are reset by their own
     * `startNewSession` calls in [handleCaptureStart].
     */
    private fun switchSessionBuffers(kind: CaptureMode) {
        when (kind) {
            CaptureMode.PHOTO -> {
                VideoRecorder.clear()
                AudioRecorder.clear()
            }
            CaptureMode.VIDEO -> {
                PhotoStore.clear()
                AudioRecorder.clear()
            }
            CaptureMode.AUDIO -> {
                PhotoStore.clear()
                VideoRecorder.clear()
            }
            CaptureMode.MOVIE -> {
                PhotoStore.clear()
                // VideoRecorder + AudioRecorder are about to be (re)started.
            }
        }
    }

    // ---- helpers ----

    private fun refreshConnState() {
        val state = when {
            lConnected && btConnected -> ConnectionState.CONNECTED
            else -> ConnectionState.CONNECTING
        }
        val text = when (state) {
            ConnectionState.CONNECTED -> "接続済み"
            ConnectionState.CONNECTING -> "接続中… (L=$lConnected BT=$btConnected)"
            ConnectionState.DISCONNECTED -> "切断"
        }
        updateConnectionState(state, text)
        if (state != ConnectionState.CONNECTED && VideoRecorder.recording.value) {
            Log.d(TAG, "disconnect: stopping video recording")
            VideoRecorder.stopRecording()
            FpsTracker.reset()
        }
        if (state == ConnectionState.CONNECTED && !appStarted) {
            appStarted = true
            Log.d(TAG, "appStart $GLASS_MAIN_ACTIVITY")
            cxrLink?.appStart(GLASS_MAIN_ACTIVITY, object : IGlassAppCbk {
                override fun onOpenAppResult(success: Boolean) {
                    Log.d(TAG, "onOpenAppResult: $success")
                    if (success) sendCapsEvent(Wire.EVENT_SESSION_OPEN)
                }

                override fun onGlassAppResume(resume: Boolean) {
                    Log.d(TAG, "onGlassAppResume: $resume")
                    if (resume) sendCapsEvent(Wire.EVENT_SESSION_OPEN)
                }
            })
        }
    }

    private fun sendCapsEvent(event: String) {
        val link = cxrLink ?: return
        if (event != Wire.EVENT_PING) Log.d(TAG, "-> $event")
        val payload = Caps().apply {
            write(Wire.FIELD_EVENT); write(event)
            write(Wire.FIELD_TS); writeInt64(System.currentTimeMillis())
        }.serialize()
        runCatching { link.sendCustomCmd(Wire.CHANNEL_TO_GLASS, payload) }
            .onFailure { Log.w(TAG, "sendCapsEvent($event) failed", it) }
        when (event) {
            Wire.EVENT_SESSION_OPEN -> {
                mainHandler.removeCallbacks(heartbeatRunnable)
                mainHandler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS)
            }
            Wire.EVENT_SESSION_CLOSE -> mainHandler.removeCallbacks(heartbeatRunnable)
        }
    }

    private fun updateConnectionState(state: ConnectionState, notifText: String) {
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

        private val _connState = MutableStateFlow(ConnectionState.DISCONNECTED)
        val connState: StateFlow<ConnectionState> = _connState.asStateFlow()

        private val _captureMode = MutableStateFlow(CaptureMode.PHOTO)
        val captureMode: StateFlow<CaptureMode> = _captureMode.asStateFlow()

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
