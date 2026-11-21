package com.ereader.app.ui.splash

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.ereader.app.R
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// ─────────────────────────────────────────────────────────────────────────────
// Colors
// ─────────────────────────────────────────────────────────────────────────────

private val BgNavy      = Color(0xFF060D30)   // deep navy bookcase background
private val VineGold    = Color(0xFFD4AF37)   // main gold vine
private val VineGoldDim = Color(0xFF9A7510)   // dimmer gold for branches
private val VineBlue    = Color(0xFF3060CC)   // bright blue accent vine
private val VineBlueDim = Color(0xFF1B3A8A)   // dimmer blue for branches
private val LeafGold    = Color(0xFFB8960C)   // gold leaf fill
private val GlowGold    = Color(0x44D4AF37)   // semi-transparent gold glow

// ─────────────────────────────────────────────────────────────────────────────
// Splash Screen
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Full-screen animated splash screen.
 *
 * Sequence:
 *  1. Logo fades in (0–0.6s)
 *  2. Gold vines grow outward from the logo toward the four corners (0.6–3.2s)
 *  3. Blue accent vines follow along the edge midpoints (0.9–3.2s)
 *  4. Branches and leaves bloom once the main vine reaches them
 *  5. A spiral curl appears at each vine tip
 *  6. Brief pause → [onSplashComplete] fires
 *
 * [onSplashComplete] — called when animation finishes; navigation happens there.
 */
@Composable
fun SplashScreen(onSplashComplete: () -> Unit) {

    // ── Animation state ───────────────────────────────────────────────────────
    val logoAlpha = remember { Animatable(0f) }
    val vineProgress = remember { Animatable(0f) }

    // Infinite shimmer on the logo glow — keeps running while vines grow
    val infiniteTransition = rememberInfiniteTransition(label = "glow")
    val glowPulse by infiniteTransition.animateFloat(
        initialValue   = 0.3f,
        targetValue    = 0.9f,
        animationSpec  = infiniteRepeatable(
            animation  = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowPulse"
    )

    LaunchedEffect(Unit) {
        // 1. Fade in logo
        logoAlpha.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
        // 2. Grow vines (ease-in so they start slow, then accelerate)
        vineProgress.animateTo(
            targetValue   = 1f,
            animationSpec = tween(
                durationMillis = 2500,
                easing         = CubicBezierEasing(0.05f, 0f, 0.35f, 1f)
            )
        )
        // 3. Hold at full extension before navigating
        delay(450)
        onSplashComplete()
    }

    Box(
        modifier         = Modifier
            .fillMaxSize()
            .background(BgNavy),
        contentAlignment = Alignment.Center
    ) {

        // ── Vine Canvas ───────────────────────────────────────────────────────
        // Drawn first so the logo renders on top of the vines
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width  * 0.5f
            val cy = size.height * 0.46f   // logo sits slightly above vertical centre
            val p  = vineProgress.value
            drawAllVines(cx, cy, p)
        }

        // ── Pulsing glow ring behind the logo ─────────────────────────────────
        // Gives a sense of "energy" emanating from the logo while vines grow
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width  * 0.5f
            val cy = size.height * 0.46f
            val radius = size.width * 0.28f * glowPulse
            drawCircle(
                brush    = Brush.radialGradient(
                    colors  = listOf(GlowGold, Color.Transparent),
                    center  = Offset(cx, cy),
                    radius  = radius
                ),
                center   = Offset(cx, cy),
                radius   = radius
            )
        }

        // ── OmniTome logo ─────────────────────────────────────────────────────
        Image(
            painter            = painterResource(R.drawable.logo_omnitome),
            contentDescription = "OmniTome",
            contentScale       = ContentScale.Fit,
            alpha              = logoAlpha.value,
            modifier           = Modifier
                .height(300.dp)
                .wrapContentWidth()
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Vine drawing — all paths emanate from (cx, cy)
// ─────────────────────────────────────────────────────────────────────────────

private fun DrawScope.drawAllVines(cx: Float, cy: Float, progress: Float) {
    val w = size.width
    val h = size.height

    // ── Helper offsets ────────────────────────────────────────────────────────
    // Precompute key points on the main corner vines so branches can attach to them
    val tlP0 = Offset(cx, cy);            val tlP1 = Offset(cx - w*0.28f, cy - h*0.06f)
    val tlP2 = Offset(w*0.10f, h*0.14f); val tlP3 = Offset(w*0.02f,  h*0.02f)

    val trP0 = Offset(cx, cy);            val trP1 = Offset(cx + w*0.28f, cy - h*0.06f)
    val trP2 = Offset(w*0.90f, h*0.14f); val trP3 = Offset(w*0.98f,  h*0.02f)

    val brP0 = Offset(cx, cy);            val brP1 = Offset(cx + w*0.22f, cy + h*0.08f)
    val brP2 = Offset(w*0.90f, h*0.86f); val brP3 = Offset(w*0.98f,  h*0.98f)

    val blP0 = Offset(cx, cy);            val blP1 = Offset(cx - w*0.22f, cy + h*0.08f)
    val blP2 = Offset(w*0.10f, h*0.86f); val blP3 = Offset(w*0.02f,  h*0.98f)

    // ── 1. GOLD CORNER VINES ─────────────────────────────────────────────────
    // Each corner vine gets: glow shadow → main vine → branch → leaf → spiral

    // Top-left
    drawVineGlow(tlP0, tlP1, tlP2, tlP3, progress, 0f)
    drawGrowingCubic(tlP0, tlP1, tlP2, tlP3, progress, VineGold, 3.5f, 0f)
    val tlMid = cubicPoint(tlP0, tlP1, tlP2, tlP3, 0.52f)
    val tlBranchEnd = Offset(tlMid.x - w*0.10f, tlMid.y - h*0.17f)
    drawGrowingCubic(
        p0 = tlMid,
        p1 = Offset(tlMid.x - w*0.04f, tlMid.y - h*0.08f),
        p2 = Offset(tlMid.x - w*0.08f, tlMid.y - h*0.13f),
        p3 = tlBranchEnd,
        progress = progress, color = VineGoldDim, strokeWidth = 2f, startProgress = 0.40f
    )
    drawLeaf(tlBranchEnd, progress, startProgress = 0.78f, color = LeafGold, size = 14f, angle = -45f)
    drawSpiral(tlBranchEnd, progress, startProgress = 0.84f, color = VineGold)
    drawSpiral(tlP3, progress, startProgress = 0.88f, color = VineGold, clockwise = false)

    // Top-right
    drawVineGlow(trP0, trP1, trP2, trP3, progress, 0f)
    drawGrowingCubic(trP0, trP1, trP2, trP3, progress, VineGold, 3.5f, 0f)
    val trMid = cubicPoint(trP0, trP1, trP2, trP3, 0.52f)
    val trBranchEnd = Offset(trMid.x + w*0.10f, trMid.y - h*0.17f)
    drawGrowingCubic(
        p0 = trMid,
        p1 = Offset(trMid.x + w*0.04f, trMid.y - h*0.08f),
        p2 = Offset(trMid.x + w*0.08f, trMid.y - h*0.13f),
        p3 = trBranchEnd,
        progress = progress, color = VineGoldDim, strokeWidth = 2f, startProgress = 0.40f
    )
    drawLeaf(trBranchEnd, progress, startProgress = 0.78f, color = LeafGold, size = 14f, angle = 45f)
    drawSpiral(trBranchEnd, progress, startProgress = 0.84f, color = VineGold, clockwise = false)
    drawSpiral(trP3, progress, startProgress = 0.88f, color = VineGold)

    // Bottom-right
    drawVineGlow(brP0, brP1, brP2, brP3, progress, 0f)
    drawGrowingCubic(brP0, brP1, brP2, brP3, progress, VineGold, 3.5f, 0f)
    val brMid = cubicPoint(brP0, brP1, brP2, brP3, 0.52f)
    val brBranchEnd = Offset(brMid.x + w*0.10f, brMid.y + h*0.17f)
    drawGrowingCubic(
        p0 = brMid,
        p1 = Offset(brMid.x + w*0.04f, brMid.y + h*0.08f),
        p2 = Offset(brMid.x + w*0.08f, brMid.y + h*0.13f),
        p3 = brBranchEnd,
        progress = progress, color = VineGoldDim, strokeWidth = 2f, startProgress = 0.40f
    )
    drawLeaf(brBranchEnd, progress, startProgress = 0.78f, color = LeafGold, size = 14f, angle = 135f)
    drawSpiral(brBranchEnd, progress, startProgress = 0.84f, color = VineGold)
    drawSpiral(brP3, progress, startProgress = 0.88f, color = VineGold, clockwise = false)

    // Bottom-left
    drawVineGlow(blP0, blP1, blP2, blP3, progress, 0f)
    drawGrowingCubic(blP0, blP1, blP2, blP3, progress, VineGold, 3.5f, 0f)
    val blMid = cubicPoint(blP0, blP1, blP2, blP3, 0.52f)
    val blBranchEnd = Offset(blMid.x - w*0.10f, blMid.y + h*0.17f)
    drawGrowingCubic(
        p0 = blMid,
        p1 = Offset(blMid.x - w*0.04f, blMid.y + h*0.08f),
        p2 = Offset(blMid.x - w*0.08f, blMid.y + h*0.13f),
        p3 = blBranchEnd,
        progress = progress, color = VineGoldDim, strokeWidth = 2f, startProgress = 0.40f
    )
    drawLeaf(blBranchEnd, progress, startProgress = 0.78f, color = LeafGold, size = 14f, angle = -135f)
    drawSpiral(blBranchEnd, progress, startProgress = 0.84f, color = VineGold, clockwise = false)
    drawSpiral(blP3, progress, startProgress = 0.88f, color = VineGold)

    // ── 2. BLUE EDGE VINES ───────────────────────────────────────────────────
    // Grow slightly after gold vines (startProgress = 0.12) to layer the effect

    // Top edge
    val tP0 = Offset(cx, cy); val tP1 = Offset(cx + w*0.05f, cy - h*0.16f)
    val tP2 = Offset(cx - w*0.08f, h*0.07f); val tP3 = Offset(cx, h*0.01f)
    drawGrowingCubic(tP0, tP1, tP2, tP3, progress, VineBlue, 2.5f, 0.12f)
    val tMid = cubicPoint(tP0, tP1, tP2, tP3, 0.5f)
    drawGrowingCubic(
        p0 = tMid,
        p1 = Offset(tMid.x + w*0.07f, tMid.y - h*0.05f),
        p2 = Offset(tMid.x + w*0.12f, tMid.y - h*0.10f),
        p3 = Offset(tMid.x + w*0.09f, tMid.y - h*0.18f),
        progress = progress, color = VineBlueDim, strokeWidth = 1.5f, startProgress = 0.50f
    )
    drawLeaf(Offset(tMid.x + w*0.09f, tMid.y - h*0.18f), progress, startProgress = 0.82f, color = VineBlue, size = 10f, angle = 20f)

    // Right edge
    val rP0 = Offset(cx, cy); val rP1 = Offset(cx + w*0.18f, cy + h*0.04f)
    val rP2 = Offset(w*0.93f, cy - h*0.06f); val rP3 = Offset(w*0.99f, cy)
    drawGrowingCubic(rP0, rP1, rP2, rP3, progress, VineBlue, 2.5f, 0.12f)
    val rMid = cubicPoint(rP0, rP1, rP2, rP3, 0.5f)
    drawGrowingCubic(
        p0 = rMid,
        p1 = Offset(rMid.x + w*0.05f, rMid.y + h*0.07f),
        p2 = Offset(rMid.x + w*0.10f, rMid.y + h*0.12f),
        p3 = Offset(rMid.x + w*0.05f, rMid.y + h*0.20f),
        progress = progress, color = VineBlueDim, strokeWidth = 1.5f, startProgress = 0.50f
    )
    drawLeaf(Offset(rMid.x + w*0.05f, rMid.y + h*0.20f), progress, startProgress = 0.82f, color = VineBlue, size = 10f, angle = 110f)

    // Bottom edge
    val bP0 = Offset(cx, cy); val bP1 = Offset(cx - w*0.05f, cy + h*0.16f)
    val bP2 = Offset(cx + w*0.08f, h*0.93f); val bP3 = Offset(cx, h*0.99f)
    drawGrowingCubic(bP0, bP1, bP2, bP3, progress, VineBlue, 2.5f, 0.12f)
    val bMid = cubicPoint(bP0, bP1, bP2, bP3, 0.5f)
    drawGrowingCubic(
        p0 = bMid,
        p1 = Offset(bMid.x - w*0.07f, bMid.y + h*0.05f),
        p2 = Offset(bMid.x - w*0.12f, bMid.y + h*0.10f),
        p3 = Offset(bMid.x - w*0.09f, bMid.y + h*0.18f),
        progress = progress, color = VineBlueDim, strokeWidth = 1.5f, startProgress = 0.50f
    )
    drawLeaf(Offset(bMid.x - w*0.09f, bMid.y + h*0.18f), progress, startProgress = 0.82f, color = VineBlue, size = 10f, angle = -160f)

    // Left edge
    val lP0 = Offset(cx, cy); val lP1 = Offset(cx - w*0.18f, cy - h*0.04f)
    val lP2 = Offset(w*0.07f, cy + h*0.06f); val lP3 = Offset(w*0.01f, cy)
    drawGrowingCubic(lP0, lP1, lP2, lP3, progress, VineBlue, 2.5f, 0.12f)
    val lMid = cubicPoint(lP0, lP1, lP2, lP3, 0.5f)
    drawGrowingCubic(
        p0 = lMid,
        p1 = Offset(lMid.x - w*0.05f, lMid.y - h*0.07f),
        p2 = Offset(lMid.x - w*0.10f, lMid.y - h*0.12f),
        p3 = Offset(lMid.x - w*0.05f, lMid.y - h*0.20f),
        progress = progress, color = VineBlueDim, strokeWidth = 1.5f, startProgress = 0.50f
    )
    drawLeaf(Offset(lMid.x - w*0.05f, lMid.y - h*0.20f), progress, startProgress = 0.82f, color = VineBlue, size = 10f, angle = -70f)

    // ── 3. INNER ACCENT TENDRILS near the logo ───────────────────────────────
    // Short curving wisps close to centre that give detail around the logo

    drawGrowingCubic(
        p0 = Offset(cx - w*0.06f, cy - h*0.03f),
        p1 = Offset(cx - w*0.12f, cy - h*0.10f),
        p2 = Offset(cx - w*0.08f, cy - h*0.16f),
        p3 = Offset(cx - w*0.04f, cy - h*0.20f),
        progress = progress, color = VineGoldDim, strokeWidth = 1.2f, startProgress = 0.20f
    )
    drawGrowingCubic(
        p0 = Offset(cx + w*0.06f, cy - h*0.03f),
        p1 = Offset(cx + w*0.12f, cy - h*0.10f),
        p2 = Offset(cx + w*0.08f, cy - h*0.16f),
        p3 = Offset(cx + w*0.04f, cy - h*0.20f),
        progress = progress, color = VineGoldDim, strokeWidth = 1.2f, startProgress = 0.20f
    )
    drawGrowingCubic(
        p0 = Offset(cx - w*0.05f, cy + h*0.04f),
        p1 = Offset(cx - w*0.10f, cy + h*0.10f),
        p2 = Offset(cx - w*0.06f, cy + h*0.16f),
        p3 = Offset(cx - w*0.02f, cy + h*0.20f),
        progress = progress, color = VineBlueDim, strokeWidth = 1.2f, startProgress = 0.25f
    )
    drawGrowingCubic(
        p0 = Offset(cx + w*0.05f, cy + h*0.04f),
        p1 = Offset(cx + w*0.10f, cy + h*0.10f),
        p2 = Offset(cx + w*0.06f, cy + h*0.16f),
        p3 = Offset(cx + w*0.02f, cy + h*0.20f),
        progress = progress, color = VineBlueDim, strokeWidth = 1.2f, startProgress = 0.25f
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Drawing primitives
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Draws a cubic bezier vine that grows progressively from p0 toward p3.
 *
 * [startProgress] — the overall progress value at which this vine begins to grow.
 * The vine's local progress is remapped so startProgress maps to 0 and 1 maps to 1.
 *
 * The vine is sampled at [steps] discrete t-values and connected with line segments
 * so any bezier shape works correctly (PathMeasure-based trimming is avoided for
 * simplicity and reliability across all Compose versions).
 */
private fun DrawScope.drawGrowingCubic(
    p0: Offset, p1: Offset, p2: Offset, p3: Offset,
    progress: Float,
    color: Color,
    strokeWidth: Float,
    startProgress: Float = 0f,
    steps: Int = 48
) {
    if (progress <= startProgress) return
    val localT = ((progress - startProgress) / (1f - startProgress)).coerceIn(0f, 1f)
    if (localT <= 0f) return

    val path = Path()
    var first = true
    for (i in 0..steps) {
        val t = (i.toFloat() / steps) * localT
        val pt = cubicPoint(p0, p1, p2, p3, t)
        if (first) { path.moveTo(pt.x, pt.y); first = false } else path.lineTo(pt.x, pt.y)
    }
    drawPath(
        path  = path,
        color = color,
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
}

/**
 * Draws a wide, faint glow behind a vine to give it a luminous quality.
 * Called before the actual vine so the glow sits underneath.
 */
private fun DrawScope.drawVineGlow(
    p0: Offset, p1: Offset, p2: Offset, p3: Offset,
    progress: Float,
    startProgress: Float,
    steps: Int = 48
) {
    if (progress <= startProgress) return
    val localT = ((progress - startProgress) / (1f - startProgress)).coerceIn(0f, 1f)
    val path = Path()
    var first = true
    for (i in 0..steps) {
        val t = (i.toFloat() / steps) * localT
        val pt = cubicPoint(p0, p1, p2, p3, t)
        if (first) { path.moveTo(pt.x, pt.y); first = false } else path.lineTo(pt.x, pt.y)
    }
    drawPath(
        path  = path,
        color = GlowGold,
        style = Stroke(width = 12f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
}

/**
 * Draws a small almond-shaped leaf at [position].
 * The leaf scales up from zero when [progress] exceeds [startProgress].
 * [angle] rotates the leaf (degrees) to point in the right direction.
 */
private fun DrawScope.drawLeaf(
    position: Offset,
    progress: Float,
    startProgress: Float,
    color: Color,
    size: Float,
    angle: Float
) {
    if (progress <= startProgress) return
    val t = ((progress - startProgress) / (1f - startProgress)).coerceIn(0f, 1f)
    val s = size * t
    if (s < 0.5f) return

    val angleRad = angle * PI.toFloat() / 180f
    val dx = cos(angleRad) * s * 0.6f
    val dy = sin(angleRad) * s * 0.6f

    // Leaf as a path with two quadratic curves making an almond shape
    val path = Path()
    path.moveTo(position.x, position.y)
    path.quadraticBezierTo(
        position.x + dx * 1.5f - dy * 0.8f,
        position.y + dy * 1.5f + dx * 0.8f,
        position.x + dx * 2f,
        position.y + dy * 2f
    )
    path.quadraticBezierTo(
        position.x + dx * 1.5f + dy * 0.8f,
        position.y + dy * 1.5f - dx * 0.8f,
        position.x,
        position.y
    )
    drawPath(path, color = color.copy(alpha = t))
    drawPath(path, color = color.copy(alpha = t * 0.6f), style = Stroke(width = 1f))
}

/**
 * Draws a small spiral curl — the classic vine tendril end.
 * Grows outward from nothing as [progress] increases past [startProgress].
 * [clockwise] = true → spiral clockwise; false → counter-clockwise.
 */
private fun DrawScope.drawSpiral(
    center: Offset,
    progress: Float,
    startProgress: Float,
    color: Color,
    maxRadius: Float = 11f,
    turns: Float = 1.8f,
    clockwise: Boolean = true
) {
    if (progress <= startProgress) return
    val t = ((progress - startProgress) / (1f - startProgress)).coerceIn(0f, 1f)
    if (t <= 0f) return

    val dir = if (clockwise) 1f else -1f
    val steps = 36
    val path = Path()
    var first = true

    for (i in 0..steps) {
        val localT = (i.toFloat() / steps) * t
        val angle  = dir * localT * turns * 2f * PI.toFloat()
        val r      = localT * maxRadius
        val x      = center.x + r * cos(angle.toDouble()).toFloat()
        val y      = center.y + r * sin(angle.toDouble()).toFloat()
        if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
    }
    drawPath(path, color = color.copy(alpha = t), style = Stroke(width = 1.8f, cap = StrokeCap.Round))
}

// ─────────────────────────────────────────────────────────────────────────────
// Math helpers
// ─────────────────────────────────────────────────────────────────────────────

/** Evaluates a cubic bezier at parameter [t] ∈ [0, 1]. */
private fun cubicPoint(p0: Offset, p1: Offset, p2: Offset, p3: Offset, t: Float): Offset {
    val u = 1f - t
    return Offset(
        x = u*u*u*p0.x + 3*u*u*t*p1.x + 3*u*t*t*p2.x + t*t*t*p3.x,
        y = u*u*u*p0.y + 3*u*u*t*p1.y + 3*u*t*t*p2.y + t*t*t*p3.y
    )
}
