package com.example.tapphoto.client

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result observed via checkSelfPermission at capture time */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        GlassBridge.init(applicationContext)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
        setContent {
            val bridgeStatus by GlassBridge.status.collectAsState()
            val sessionOpen by GlassBridge.sessionOpen.collectAsState()
            val captureState by GlassBridge.captureState.collectAsState()
            val mode by GlassBridge.mode.collectAsState()
            CaptureScreen(
                bridgeStatus = bridgeStatus,
                sessionOpen = sessionOpen,
                captureState = captureState,
                mode = mode,
            )
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val handled = when (event.keyCode) {
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT -> true
            else -> false
        }
        if (!handled) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_ENTER -> GlassBridge.onTap()
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT -> GlassBridge.toggleMode()
            }
        }
        return true
    }
}

@Composable
fun CaptureScreen(
    bridgeStatus: BridgeStatus,
    sessionOpen: Boolean,
    captureState: CaptureState,
    mode: CaptureMode,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        val fullyConnected = bridgeStatus == BridgeStatus.CONNECTED && sessionOpen
        when {
            !fullyConnected && bridgeStatus == BridgeStatus.CONNECTING -> {
                Text(text = "Connecting…", color = Color(0xFFAAAAAA), fontSize = 32.sp)
            }
            !fullyConnected -> {
                Text(text = "Phone not connected", color = Color(0xFFC04040), fontSize = 32.sp)
            }
            else -> CaptureFx(state = captureState, mode = mode)
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000, widthDp = 480, heightDp = 270)
@Composable
private fun IdlePreview() {
    CaptureScreen(BridgeStatus.CONNECTED, sessionOpen = true, captureState = CaptureState.IDLE, mode = CaptureMode.PHOTO)
}

@Preview(showBackground = true, backgroundColor = 0xFF000000, widthDp = 480, heightDp = 270)
@Composable
private fun CapturingPreview() {
    CaptureScreen(BridgeStatus.CONNECTED, sessionOpen = true, captureState = CaptureState.CAPTURING, mode = CaptureMode.PHOTO)
}

@Preview(showBackground = true, backgroundColor = 0xFF000000, widthDp = 480, heightDp = 270)
@Composable
private fun CapturedPreview() {
    CaptureScreen(BridgeStatus.CONNECTED, sessionOpen = true, captureState = CaptureState.CAPTURED, mode = CaptureMode.PHOTO)
}

@Preview(showBackground = true, backgroundColor = 0xFF000000, widthDp = 480, heightDp = 270)
@Composable
private fun DisconnectedPreview() {
    CaptureScreen(BridgeStatus.DISCONNECTED, sessionOpen = false, captureState = CaptureState.IDLE, mode = CaptureMode.PHOTO)
}
