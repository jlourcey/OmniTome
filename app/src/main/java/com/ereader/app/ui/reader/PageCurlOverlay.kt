package com.ereader.app.ui.reader

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlinx.coroutines.flow.SharedFlow

// ── Page turn direction ────────────────────────────────────────────────────────

enum class PageTurnDirection { FORWARD, BACKWARD }

// ── Page curl overlay ──────────────────────────────────────────────────────────

/**
 * PageCurlOverlay — a Canvas composable that renders a realistic-looking
 * page-curl animation whenever [pageTurnFlow] emits a direction.
 *
 * The animation shows:
 *  1. A "page peel" — the previous page curling away from the leading edge
 *  2. A soft shadow cast by the curl onto the incoming page
 *  3. The back of the curled page (slightly off-white, for depth)
 *
 * Because Readium has already completed the page transition by the time we
 * receive the [pageTurnFlow] event, the animation plays as a "flourish" over
 * the newly revealed content. It runs for ~500 ms and then disappears.
 *
 * @param pageTurnFlow  Emits a direction each time the reader moves to a new page.
 * @param modifier      Applied to the full-screen Canvas.
 */
@Composable
fun PageCurlOverlay(
    pageTurnFlow: SharedFlow<PageTurnDirection>,
    modifier: Modifier = Modifier
) {
    // Animatable gives us explicit coroutine-driven control over the animation.
    // Unlike animateFloatAsState, it doesn't start a reverse animation when we
    // snap the value back to 0 — we control exactly when it moves and how.
    val animatable = remember { Animatable(0f) }
    var direction   by remember { mutableStateOf(PageTurnDirection.FORWARD) }

    // One coroutine owns the whole animation lifecycle per page turn:
    //  1. Snap to 0 (in case the previous curl never finished)
    //  2. Animate forward 0 → 1  (curl sweeps across)
    //  3. Snap back to 0          (canvas goes invisible again — no reverse animation)
    LaunchedEffect(Unit) {
        pageTurnFlow.collect { dir ->
            direction = dir
            animatable.snapTo(0f)
            animatable.animateTo(
                targetValue   = 1f,
                animationSpec = tween(durationMillis = 480, easing = FastOutSlowInEasing)
            )
            animatable.snapTo(0f)   // instantly clear — no backward curl
        }
    }

    // Only allocate a Canvas composable while the animation is live
    if (animatable.value > 0f) {
        Canvas(modifier = modifier.fillMaxSize()) {
            drawPageCurl(progress = animatable.value, direction = direction)
        }
    }
}

// ── Drawing algorithm ──────────────────────────────────────────────────────────

/**
 * Draws the curl effect directly onto the canvas.
 *
 * [progress] goes from 0.0 → 1.0 as the animation runs.
 * At 0.0 the curl is just starting at the edge; at 1.0 it has swept off screen.
 *
 * Technique:
 *  - A vertical "fold line" sweeps across the page:
 *    • FORWARD: starts at the right edge and moves left (page flipping forward)
 *    • BACKWARD: starts at the left edge and moves right (page flipping back)
 *  - Left of the fold line: draw a white "back of page" strip with slight tilt
 *  - At the fold line: draw a gradient shadow (dark on the incoming side, fading to nothing)
 *  - The curl narrows as progress increases (the top of the page peels faster)
 */
private fun DrawScope.drawPageCurl(progress: Float, direction: PageTurnDirection) {
    val w = size.width
    val h = size.height

    // The fold X position sweeps from one edge to the other as progress goes 0→1
    val foldX: Float = when (direction) {
        PageTurnDirection.FORWARD  -> w * (1f - progress)          // right edge → left
        PageTurnDirection.BACKWARD -> w * progress                 // left edge  → right
    }

    // Width of the "back of page" visible strip (tapers as it moves off screen)
    val stripWidth = 80f * (1f - progress * 0.7f)

    // ── Draw the back-of-page white strip ─────────────────────────────────────
    val backPath = Path().apply {
        when (direction) {
            PageTurnDirection.FORWARD -> {
                moveTo(foldX, 0f)
                lineTo(foldX + stripWidth * 0.85f, 0f)
                lineTo(foldX + stripWidth, h * 0.15f)
                cubicTo(
                    foldX + stripWidth * 1.05f, h * 0.5f,
                    foldX + stripWidth * 1.05f, h * 0.5f,
                    foldX + stripWidth, h * 0.85f
                )
                lineTo(foldX + stripWidth * 0.85f, h)
                lineTo(foldX, h)
            }
            PageTurnDirection.BACKWARD -> {
                moveTo(foldX - stripWidth * 0.85f, 0f)
                lineTo(foldX, 0f)
                lineTo(foldX, h)
                lineTo(foldX - stripWidth * 0.85f, h)
                lineTo(foldX - stripWidth, h * 0.85f)
                cubicTo(
                    foldX - stripWidth * 1.05f, h * 0.5f,
                    foldX - stripWidth * 1.05f, h * 0.5f,
                    foldX - stripWidth, h * 0.15f
                )
            }
        }
        close()
    }

    // Back of page = slightly off-white with transparency
    drawPath(
        path  = backPath,
        color = Color(0xFFF2F0EB).copy(alpha = 0.92f - progress * 0.3f)
    )

    // ── Shadow gradient at the fold line ──────────────────────────────────────
    val shadowW = 48f

    val (shadowStart, shadowEnd) = when (direction) {
        PageTurnDirection.FORWARD  -> Pair(foldX, foldX - shadowW)
        PageTurnDirection.BACKWARD -> Pair(foldX, foldX + shadowW)
    }

    drawRect(
        brush     = Brush.horizontalGradient(
            colors    = listOf(
                Color.Black.copy(alpha = 0.28f * (1f - progress * 0.5f)),
                Color.Transparent
            ),
            startX = shadowStart,
            endX   = shadowEnd
        ),
        topLeft   = Offset(
            x = if (direction == PageTurnDirection.FORWARD) foldX - shadowW else foldX,
            y = 0f
        ),
        size      = Size(shadowW, h)
    )

    // ── Thin fold-line highlight ───────────────────────────────────────────────
    drawLine(
        brush       = Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.5f),
                Color.White.copy(alpha = 0.15f),
                Color.White.copy(alpha = 0.5f)
            )
        ),
        start       = Offset(foldX, 0f),
        end         = Offset(foldX, h),
        strokeWidth = 2f
    )
}
