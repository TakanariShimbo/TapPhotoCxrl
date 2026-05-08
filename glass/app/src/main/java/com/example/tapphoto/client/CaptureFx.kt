package com.example.tapphoto.client

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val BracketColor = Color(0xFF4DFF6F)
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
fun CaptureFx(state: PhotoState) {
    val showBrackets = state == PhotoState.CAPTURING || state == PhotoState.CAPTURED

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
            visible = state == PhotoState.CAPTURED,
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

        if (state == PhotoState.CAPTURED) {
            FlashOverlay(
                modifier = Modifier
                    .fillMaxHeight(BRACKET_HEIGHT_FRACTION)
                    .aspectRatio(1f),
            )
        }

        when (state) {
            PhotoState.IDLE -> Text(
                text = "タップで撮影",
                color = Color(0xFFCCCCCC),
                fontSize = 32.sp,
            )
            PhotoState.FAILED -> Text(
                text = "撮影失敗",
                color = Color(0xFFC04040),
                fontSize = 36.sp,
            )
            else -> Unit
        }
    }
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
