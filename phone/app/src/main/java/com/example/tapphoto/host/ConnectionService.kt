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
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.app.NotificationCompat
import com.example.cxrglobal.CXRLink
import com.example.cxrglobal.CxrDefs
import com.example.cxrglobal.callbacks.ICXRLinkCbk
import com.example.cxrglobal.callbacks.ICustomCmdCbk
import com.example.cxrglobal.callbacks.IGlassAppCbk
import com.example.cxrglobal.callbacks.IImageStreamCbk
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
private const val PHOTO_WIDTH = 1024
private const val PHOTO_HEIGHT = 768
private const val PHOTO_QUALITY = 80

class ConnectionService : Service() {

    private var cxrLink: CXRLink? = null
    private var lConnected = false
    private var btConnected = false
    private var appStarted = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            sendSessionEvent("ping")
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
        mainHandler.removeCallbacks(heartbeatRunnable)
        sendSessionEvent("session_close")
        runCatching { cxrLink?.disconnect() }
        cxrLink = null
        lConnected = false
        btConnected = false
        appStarted = false
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
                CxrDefs.CXRSession(
                    CxrDefs.CXRSessionType.CUSTOMAPP,
                    GLASS_APP_PKG,
                ),
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
                    when (readEvent(caps)) {
                        "request_photo" -> requestPhotoCapture()
                    }
                }
            })
            setCXRImageCbk(object : IImageStreamCbk {
                override fun onImageReceived(data: ByteArray) {
                    Log.d(TAG, "onImageReceived bytes=${data.size}")
                    val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
                    if (bitmap == null) {
                        Log.w(TAG, "decode failed")
                        sendSessionEvent("photo_failed")
                        return
                    }
                    PhotoStore.set(bitmap.asImageBitmap())
                    sendSessionEvent("photo_done")
                }

                override fun onImageError(code: Int, msg: String?) {
                    Log.w(TAG, "onImageError code=$code msg=$msg")
                    sendSessionEvent("photo_failed")
                }
            })
            connect(token)
        }
    }

    private fun requestPhotoCapture() {
        val link = cxrLink
        if (link == null) {
            Log.w(TAG, "requestPhotoCapture: no link")
            sendSessionEvent("photo_failed")
            return
        }
        Log.d(TAG, "takePhoto ${PHOTO_WIDTH}x${PHOTO_HEIGHT} q=$PHOTO_QUALITY")
        val accepted = runCatching {
            link.takePhoto(PHOTO_WIDTH, PHOTO_HEIGHT, PHOTO_QUALITY)
        }.onFailure { Log.w(TAG, "takePhoto threw", it) }.getOrDefault(false)
        if (!accepted) sendSessionEvent("photo_failed")
    }

    private fun readEvent(caps: Caps): String? = runCatching {
        for (i in 0 until caps.size() - 1) {
            val key = caps.at(i)
            if (key.type() == Caps.Value.TYPE_STRING && key.string == "event") {
                val v = caps.at(i + 1)
                if (v.type() == Caps.Value.TYPE_STRING) return@runCatching v.string
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
        if (state == CxrConnState.CONNECTED && !appStarted) {
            appStarted = true
            Log.d(TAG, "appStart $GLASS_MAIN_ACTIVITY")
            cxrLink?.appStart(GLASS_MAIN_ACTIVITY, object : IGlassAppCbk {
                override fun onOpenAppResult(success: Boolean) {
                    Log.d(TAG, "onOpenAppResult: $success")
                    if (success) sendSessionEvent("session_open")
                }

                override fun onGlassAppResume(resume: Boolean) {
                    Log.d(TAG, "onGlassAppResume: $resume")
                    if (resume) sendSessionEvent("session_open")
                }
            })
        }
    }

    private fun sendSessionEvent(event: String) {
        val link = cxrLink ?: return
        if (event != "ping") Log.d(TAG, "send $event")
        val payload = Caps().apply {
            write("event")
            write(event)
            write("ts")
            writeInt64(System.currentTimeMillis())
        }.serialize()
        runCatching { link.sendCustomCmd(CHANNEL_TO_GLASS, payload) }
            .onFailure { Log.w(TAG, "sendSessionEvent($event) failed", it) }
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
            startForeground(
                NOTIF_ID,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
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
