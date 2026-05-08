package com.example.tapphoto.client

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.rokid.cxr.CXRServiceBridge
import com.rokid.cxr.Caps
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "GlassBridge"
private const val CHANNEL_FROM_PHONE = "rk_custom_client"
private const val CHANNEL_TO_PHONE = "rk_custom_key"
private const val PING_TIMEOUT_MS = 12_000L
private const val PHOTO_RESULT_RESET_MS = 2_000L

enum class BridgeStatus { DISCONNECTED, CONNECTING, CONNECTED }

enum class PhotoState { IDLE, CAPTURING, CAPTURED, FAILED }

object GlassBridge {
    private var bridge: CXRServiceBridge? = null

    private val _status = MutableStateFlow(BridgeStatus.DISCONNECTED)
    val status: StateFlow<BridgeStatus> = _status.asStateFlow()

    private val _sessionOpen = MutableStateFlow(false)
    val sessionOpen: StateFlow<Boolean> = _sessionOpen.asStateFlow()

    private val _photoState = MutableStateFlow(PhotoState.IDLE)
    val photoState: StateFlow<PhotoState> = _photoState.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pingTimeoutRunnable = Runnable {
        Log.d(TAG, "ping timeout, marking session closed")
        _sessionOpen.value = false
    }
    private val photoResetRunnable = Runnable {
        _photoState.value = PhotoState.IDLE
    }

    private fun armPingWatchdog() {
        mainHandler.removeCallbacks(pingTimeoutRunnable)
        mainHandler.postDelayed(pingTimeoutRunnable, PING_TIMEOUT_MS)
    }

    private fun stopPingWatchdog() {
        mainHandler.removeCallbacks(pingTimeoutRunnable)
    }

    fun init() {
        if (bridge != null) return
        bridge = CXRServiceBridge().apply {
            setStatusListener(object : CXRServiceBridge.StatusListener {
                override fun onConnected(p0: String?, p1: String?, p2: Int) {
                    Log.d(TAG, "onConnected pkg=$p0 device=$p1 type=$p2")
                    _status.value = BridgeStatus.CONNECTED
                }

                override fun onDisconnected() {
                    Log.d(TAG, "onDisconnected")
                    _status.value = BridgeStatus.DISCONNECTED
                    _sessionOpen.value = false
                    stopPingWatchdog()
                }

                override fun onConnecting(p0: String?, p1: String?, p2: Int) {
                    Log.d(TAG, "onConnecting pkg=$p0 device=$p1 type=$p2")
                    _status.value = BridgeStatus.CONNECTING
                }

                override fun onARTCStatus(p0: Float, p1: Boolean) {}
                override fun onRokidAccountChanged(p0: String?) {}
            })
            subscribe(CHANNEL_FROM_PHONE, object : CXRServiceBridge.MsgCallback {
                override fun onReceive(name: String?, args: Caps?, bytes: ByteArray?) {
                    val event = readEvent(args)
                    if (event != "ping") Log.d(TAG, "received on $name: event=$event")
                    when (event) {
                        "session_open" -> {
                            _sessionOpen.value = true
                            armPingWatchdog()
                        }
                        "session_close" -> {
                            _sessionOpen.value = false
                            stopPingWatchdog()
                        }
                        "ping" -> {
                            // ping is proof the phone is alive in an active session.
                            // Treat it as session_open in case we missed the original session_open
                            // (race when glass app is just launching and hasn't subscribed yet).
                            _sessionOpen.value = true
                            armPingWatchdog()
                        }
                        "photo_done" -> setPhotoResult(PhotoState.CAPTURED)
                        "photo_failed" -> setPhotoResult(PhotoState.FAILED)
                    }
                }
            })
        }
    }

    private fun setPhotoResult(state: PhotoState) {
        _photoState.value = state
        when (state) {
            PhotoState.CAPTURED -> CameraSfx.playShutter()
            PhotoState.FAILED -> CameraSfx.playFail()
            else -> Unit
        }
        mainHandler.removeCallbacks(photoResetRunnable)
        mainHandler.postDelayed(photoResetRunnable, PHOTO_RESULT_RESET_MS)
    }

    fun sendCaps(caps: Caps): Int {
        val b = bridge
        if (b == null) {
            Log.w(TAG, "sendCaps: bridge not initialized")
            return -1
        }
        val rc = b.sendMessage(CHANNEL_TO_PHONE, caps)
        Log.d(TAG, "sendMessage($CHANNEL_TO_PHONE) -> $rc")
        return rc
    }

    fun requestPhoto() {
        if (_status.value != BridgeStatus.CONNECTED || !_sessionOpen.value) {
            Log.d(TAG, "requestPhoto skipped (status=${_status.value} sessionOpen=${_sessionOpen.value})")
            return
        }
        if (_photoState.value == PhotoState.CAPTURING) {
            Log.d(TAG, "requestPhoto skipped (already capturing)")
            return
        }
        mainHandler.removeCallbacks(photoResetRunnable)
        _photoState.value = PhotoState.CAPTURING
        val caps = Caps().apply {
            write("event")
            write("request_photo")
            write("ts")
            writeInt64(System.currentTimeMillis())
        }
        sendCaps(caps)
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
