package com.example.tapphoto.client

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val BracketColor = Color(0xFF4DFF6F)
private val LiveDotColor = Color(0xFFFF4D4D)
private const val BRACKET_HEIGHT_FRACTION = 0.42f
private const val BRACKET_ARM_FRACTION = 0.14f
private val BRACKET_STROKE = 3.dp
private val CHECK_STROKE = 4.dp
private val CHECK_SIZE = 56.dp

private const val BRACKET_ENTER_MS = 220
private const val BRACKET_EXIT_MS = 220
private const val CHECK_DELAY_MS = 90
private const val CHECK_ENTER_MS = 180
private const val FLASH_IN_MS = 60
private const val FLASH_OUT_MS = 160
private const val FLASH_PEAK_ALPHA = 0.45f

@Composable
fun CaptureFx(state: CaptureState, mode: CaptureMode) {
    // Camera-related visuals (brackets) appear during PHOTO capture and during
    // RUNNING when the mode uses the camera (VIDEO / MOVIE).
    val showBrackets = state == CaptureState.CAPTURING ||
        state == CaptureState.CAPTURED ||
        (state == CaptureState.RUNNING && mode.usesCamera())

    Box(modifier = Modifier.fillMaxSize()) {
        ModeBadge(
            mode = mode,
            running = state == CaptureState.RUNNING,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 12.dp),
        )

        if (state == CaptureState.RUNNING) {
            StopHint(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp),
            )
        }

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedVisibility(
                visible = showBrackets,
                enter = fadeIn(tween(BRACKET_ENTER_MS, easing = LinearEasing)) +
                    scaleIn(
                        initialScale = 1.06f,
                        animationSpec = tween(BRACKET_ENTER_MS, easing = FastOutSlowInEasing),
                    ),
                exit = fadeOut(tween(BRACKET_EXIT_MS)),
            ) {
                CornerBrackets(
                    modifier = Modifier
                        .fillMaxHeight(BRACKET_HEIGHT_FRACTION)
                        .aspectRatio(1f),
                )
            }

            AnimatedVisibility(
                visible = state == CaptureState.CAPTURED,
                enter = fadeIn(tween(CHECK_ENTER_MS, delayMillis = CHECK_DELAY_MS)) +
                    scaleIn(
                        initialScale = 0.6f,
                        animationSpec = tween(
                            CHECK_ENTER_MS,
                            delayMillis = CHECK_DELAY_MS,
                            easing = FastOutSlowInEasing,
                        ),
                    ),
                exit = fadeOut(tween(160)),
            ) {
                CheckIcon(modifier = Modifier.size(CHECK_SIZE))
            }

            if (state == CaptureState.CAPTURED) {
                FlashOverlay(
                    modifier = Modifier
                        .fillMaxHeight(BRACKET_HEIGHT_FRACTION)
                        .aspectRatio(1f),
                )
            }

            CenterText(state = state, mode = mode)
        }
    }
}

@Composable
private fun CenterText(state: CaptureState, mode: CaptureMode) {
    when {
        state == CaptureState.IDLE -> Text(
            text = mode.idlePrompt(),
            color = Color(0xFFCCCCCC),
            fontSize = 32.sp,
        )
        state == CaptureState.RUNNING && mode == CaptureMode.AUDIO -> RecText()
        state == CaptureState.FAILED -> Text(
            text = mode.failedLabel(),
            color = Color(0xFFC04040),
            fontSize = 32.sp,
        )
        else -> Unit
    }
}

private fun CaptureMode.idlePrompt(): String = when (this) {
    CaptureMode.PHOTO -> "タップで撮影"
    CaptureMode.VIDEO -> "タップでビデオ"
    CaptureMode.AUDIO -> "タップで録音"
    CaptureMode.MOVIE -> "タップでムービー"
}

private fun CaptureMode.failedLabel(): String = when (this) {
    CaptureMode.PHOTO -> "撮影失敗"
    CaptureMode.VIDEO -> "ビデオ失敗"
    CaptureMode.AUDIO -> "録音失敗"
    CaptureMode.MOVIE -> "ムービー失敗"
}

@Composable
private fun ModeBadge(
    mode: CaptureMode,
    running: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (mode != CaptureMode.PHOTO) LiveDot(active = running)
        Text(
            text = mode.name,  // "PHOTO" / "VIDEO" / "AUDIO" / "MOVIE"
            color = Color(0xFF888888),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun RecText() {
    val transition = rememberInfiniteTransition(label = "rec-text")
    val alpha by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "rec-text-alpha",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(LiveDotColor)
                .alpha(alpha),
        )
        Text(
            text = "REC",
            color = Color(0xFFEEEEEE),
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun StopHint(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "stop-hint")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "stop-hint-alpha",
    )
    Text(
        text = "タップで停止",
        color = Color(0xFFCCCCCC),
        fontSize = 22.sp,
        modifier = modifier.alpha(alpha),
    )
}

@Composable
private fun LiveDot(active: Boolean) {
    val alpha = if (active) {
        val transition = rememberInfiniteTransition(label = "live-dot")
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(
                animation = tween(700, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "live-dot-alpha",
        ).value
    } else {
        0.4f
    }
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(LiveDotColor)
            .alpha(alpha),
    )
}

@Composable
private fun CornerBrackets(
    modifier: Modifier,
    color: Color = BracketColor,
    armFraction: Float = BRACKET_ARM_FRACTION,
    strokeWidth: Dp = BRACKET_STROKE,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val sw = strokeWidth.toPx()
        val inset = sw / 2f
        val arm = w * armFraction
        val style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)

        fun lShape(cornerX: Float, cornerY: Float, dx: Float, dy: Float): Path =
            Path().apply {
                moveTo(cornerX + dx, cornerY)
                lineTo(cornerX, cornerY)
                lineTo(cornerX, cornerY + dy)
            }

        drawPath(lShape(inset, inset, arm, arm), color, style = style)
        drawPath(lShape(w - inset, inset, -arm, arm), color, style = style)
        drawPath(lShape(inset, h - inset, arm, -arm), color, style = style)
        drawPath(lShape(w - inset, h - inset, -arm, -arm), color, style = style)
    }
}

@Composable
private fun CheckIcon(
    modifier: Modifier,
    color: Color = BracketColor,
    strokeWidth: Dp = CHECK_STROKE,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val sw = strokeWidth.toPx()
        val style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val path = Path().apply {
            moveTo(w * 0.18f, h * 0.55f)
            lineTo(w * 0.43f, h * 0.78f)
            lineTo(w * 0.82f, h * 0.28f)
        }
        drawPath(path, color, style = style)
    }
}

@Composable
private fun FlashOverlay(modifier: Modifier = Modifier) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        alpha.animateTo(FLASH_PEAK_ALPHA, tween(FLASH_IN_MS, easing = LinearEasing))
        alpha.animateTo(0f, tween(FLASH_OUT_MS, easing = LinearEasing))
    }
    Box(
        modifier = modifier.background(Color.White.copy(alpha = alpha.value)),
    )
}
