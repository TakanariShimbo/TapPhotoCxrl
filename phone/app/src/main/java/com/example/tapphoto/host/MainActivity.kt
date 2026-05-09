package com.example.tapphoto.host

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.cxrglobal.auth.AuthResult
import com.example.cxrglobal.auth.AuthorizationHelper
import com.example.tapphoto.host.ui.theme.TapPhotoCxrlHostTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
private const val HI_ROKID_PKG = "com.rokid.sprite.global.aiapp"

private fun isPackageInstalled(context: Context, pkg: String): Boolean =
    try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

private const val AUTH_REQUEST_CODE = 1001

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        TokenStore.load(this)
        setContent {
            TapPhotoCxrlHostTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(
                        modifier = Modifier.padding(innerPadding),
                        onRequestAuth = {
                            AuthorizationHelper.requestAuthorization(this, AUTH_REQUEST_CODE)
                        },
                    )
                }
            }
        }
    }

    @Deprecated("required by AuthorizationHelper SDK callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != AUTH_REQUEST_CODE) return
        when (val result = AuthorizationHelper.parseAuthorizationResult(resultCode, data)) {
            is AuthResult.AuthSuccess -> {
                Log.d("TapPhotoCxrl", "auth success, token len=${result.token.length}")
                TokenStore.save(this, result.token)
            }
            is AuthResult.AuthFail -> Log.d("TapPhotoCxrl", "auth failed")
            is AuthResult.AuthCancel -> Log.d("TapPhotoCxrl", "auth cancelled")
        }
    }
}

@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    onRequestAuth: () -> Unit = {},
) {
    val context = LocalContext.current
    val hiRokidInstalled = remember { isPackageInstalled(context, HI_ROKID_PKG) }
    val token by TokenStore.token.collectAsState()
    val authorized = token != null
    val running by ConnectionService.running.collectAsState()
    val cxrState by ConnectionService.connState.collectAsState()
    val connection = when {
        !running -> ConnectionState.DISCONNECTED
        cxrState == CxrConnState.CONNECTED -> ConnectionState.CONNECTED
        else -> ConnectionState.CONNECTING
    }
    val photo by PhotoStore.latest.collectAsState()
    val capturedAt by PhotoStore.capturedAt.collectAsState()
    val fps by FpsTracker.fps.collectAsState()
    val glassMode by ConnectionService.glassMode.collectAsState()

    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) ConnectionService.start(context)
    }

    val startService = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            ConnectionService.start(context)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusCard(
            hiRokidInstalled = hiRokidInstalled,
            authorized = authorized,
            connection = connection,
            glassMode = glassMode,
            modeKnown = connection == ConnectionState.CONNECTED,
        )
        ActionButtons(
            authorized = authorized,
            connection = connection,
            onAuth = onRequestAuth,
            onReauth = {
                ConnectionService.stop(context)
                TokenStore.clear(context)
            },
            onConnect = startService,
            onDisconnect = { ConnectionService.stop(context) },
        )
        PhotoPanel(
            image = photo,
            capturedAt = capturedAt,
            fps = fps,
            onClear = PhotoStore::clear,
        )
    }
}

@Composable
private fun StatusCard(
    hiRokidInstalled: Boolean,
    authorized: Boolean,
    connection: ConnectionState,
    glassMode: GlassMode,
    modeKnown: Boolean,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("接続状態", style = MaterialTheme.typography.titleSmall)
            StatusRow("Hi Rokid", if (hiRokidInstalled) "installed" else "not installed")
            StatusRow("Authorization", if (authorized) "yes" else "no")
            StatusRow("Connection", connection.name.lowercase())
            StatusRow("Mode", if (modeKnown) glassMode.name.lowercase() else "—")
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        )
    }
}

@Composable
private fun ActionButtons(
    authorized: Boolean,
    connection: ConnectionState,
    onAuth: () -> Unit,
    onReauth: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onAuth, enabled = !authorized) { Text("認証") }
            FilledTonalButton(onClick = onReauth, enabled = authorized) { Text("再認証") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                onClick = onConnect,
                enabled = authorized && connection == ConnectionState.DISCONNECTED,
            ) { Text("接続開始") }
            FilledTonalButton(
                onClick = onDisconnect,
                enabled = connection != ConnectionState.DISCONNECTED,
            ) { Text("接続停止") }
        }
    }
}

@Composable
private fun PhotoPanel(
    image: ImageBitmap?,
    capturedAt: Long?,
    fps: Float,
    onClear: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val title = when {
                fps > 0.1f -> "ライブ映像 (%.1f fps)".format(fps)
                capturedAt != null -> "最新の写真 (${timeFmt.format(Date(capturedAt))})"
                else -> "最新の写真"
            }
            Text(title, style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = onClear, enabled = image != null) { Text("Clear") }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f),
            contentAlignment = Alignment.Center,
        ) {
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = "latest photo",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = "(まだ撮影なし)",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
