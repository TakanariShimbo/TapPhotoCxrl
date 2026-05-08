package com.example.tapphoto.client

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        GlassBridge.init()
        setContent {
            val bridgeStatus by GlassBridge.status.collectAsState()
            val sessionOpen by GlassBridge.sessionOpen.collectAsState()
            val photoState by GlassBridge.photoState.collectAsState()
            PhotoScreen(
                bridgeStatus = bridgeStatus,
                sessionOpen = sessionOpen,
                photoState = photoState,
            )
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_ENTER) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN) {
            GlassBridge.requestPhoto()
        }
        return true
    }
}

@Composable
fun PhotoScreen(
    bridgeStatus: BridgeStatus,
    sessionOpen: Boolean,
    photoState: PhotoState,
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
            else -> CaptureFx(state = photoState)
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000, widthDp = 480, heightDp = 270)
@Composable
private fun IdlePreview() {
    PhotoScreen(BridgeStatus.CONNECTED, sessionOpen = true, photoState = PhotoState.IDLE)
}

@Preview(showBackground = true, backgroundColor = 0xFF000000, widthDp = 480, heightDp = 270)
@Composable
private fun CapturingPreview() {
    PhotoScreen(BridgeStatus.CONNECTED, sessionOpen = true, photoState = PhotoState.CAPTURING)
}

@Preview(showBackground = true, backgroundColor = 0xFF000000, widthDp = 480, heightDp = 270)
@Composable
private fun CapturedPreview() {
    PhotoScreen(BridgeStatus.CONNECTED, sessionOpen = true, photoState = PhotoState.CAPTURED)
}

@Preview(showBackground = true, backgroundColor = 0xFF000000, widthDp = 480, heightDp = 270)
@Composable
private fun DisconnectedPreview() {
    PhotoScreen(BridgeStatus.DISCONNECTED, sessionOpen = false, photoState = PhotoState.IDLE)
}
