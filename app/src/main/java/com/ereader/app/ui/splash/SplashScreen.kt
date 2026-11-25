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
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.ereader.app.R
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// ─────────────────────────────────────────────────────────────────────────────
// Colors
// ─────────────────────────────────────────────────────────────────────────────

private val BgNavy        = Color(0xFF060D30)   // deep navy bookcase background
private val VineGold      = Color(0xFFD4AF37)   // main gold vine
private val VineGoldDim   = Color(0xFF9A7510)   // dimmer gold for side shoots
private val VineBlue      = Color(0xFF3060CC)   // bright blue accent vine
private val VineBlueDim   = Color(0xFF1B3A8A)   // dimmer blue for side shoots
private val GlowGold      = Color(0x44D4AF37)   // semi-transparent gold glow

// Leaves are shaded from a light tip to a deeper base.
private val LeafGoldLight = Color(0xFFEBCB62)
private val LeafGoldDeep  = Color(0xFF8C6D12)
private val LeafBlueLight = Color(0xFF6A92EE)
private val LeafBlueDeep  = Color(0xFF1B3A8A)

// ─────────────────────────────────────────────────────────────────────────────
// Splash Screen
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Full-screen animated splash screen.
 *
 * Sequence:
 *  1. Logo fades in (0–0.7 s)
 *  2. Vines grow outward from the logo: gold to the four corners, blue to the
 *     edge midpoints, with smaller side shoots. Each vine is thick at the root,
 *     tapers to a fine glowing tip, and waves gently like a real stem.
 *  3. As the growing tip passes each leaf joint, a leaf sprouts: it starts as a
 *     closed bud lying along the stem, then unfurls outward with a little spring.
 *     Leaves alternate sides; some joints also put out a curling tendril.
 *  4. Each vine ends in a spiral curl. Leaves sway slightly while the logo glows.
 *  5. Brief pause → [onSplashComplete] fires
 *
 * [onSplashComplete] — called when the animation finishes; navigation happens there.
 */
@Composable
fun SplashScreen(onSplashComplete: () -> Unit) {

    // ── Animation state ───────────────────────────────────────────────────────
    val logoAlpha = remember { Animatable(0f) }
    val growth    = remember { Animatable(0f) }

    val infinite = rememberInfiniteTransition(label = "splash")
    // Shimmer on the logo glow — keeps running while the vines grow
    val glowPulse by infinite.animateFloat(
        initialValue  = 0.3f,
        targetValue   = 0.9f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label         = "glowPulse"
    )
    // Slow back-and-forth used to sway the leaves
    val sway by infinite.animateFloat(
        initialValue  = -1f,
        targetValue   = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label         = "sway"
    )

    LaunchedEffect(Unit) {
        // 1. Fade in logo
        logoAlpha.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
        // 2. Grow the vines and sprout the leaves (starts slow, like a shoot waking up)
        growth.animateTo(
            targetValue   = 1f,
            animationSpec = tween(durationMillis = 2900, easing = CubicBezierEasing(0.25f, 0.05f, 0.35f, 1f))
        )
        // 3. Hold at full growth before navigating
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
            val vines = buildVines(cx, cy, size.width, size.height)
            val g = growth.value
            // Glow under the gold vines first, then all vines, so glows never cover a stem.
            vines.filter { it.glow }.forEach { drawVineGlow(it, g) }
            vines.forEach { drawVine(it, g, sway) }
        }

        // ── Pulsing glow ring behind the logo ─────────────────────────────────
        // Gives a sense of "energy" emanating from the logo while vines grow
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width  * 0.5f
            val cy = size.height * 0.46f
            val radius = size.width * 0.28f * glowPulse
            drawCircle(
                brush  = Brush.radialGradient(
                    colors = listOf(GlowGold, Color.Transparent),
                    center = Offset(cx, cy),
                    radius = radius
                ),
                center = Offset(cx, cy),
                radius = radius
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
// Vine model
// ─────────────────────────────────────────────────────────────────────────────

/**
 * One vine: a cubic curve p0 → p3 that grows during [start]..[end] of the
 * overall animation (0–1).
 *
 * [width]      stroke width at the root (it tapers to ~25 % at the tip)
 * [leaves]     how many leaves sprout along it
 * [leafSize]   length of the first (largest) leaf, in px
 * [firstSide]  +1 / −1: which side the first leaf grows on (then they alternate)
 * [wave]       how far the stem waves side to side, in px
 * [tendrilEvery] every Nth leaf joint also grows a curly tendril (0 = none)
 * [curlAtTip]  draw a spiral curl at the end once fully grown
 */
private data class Vine(
    val p0: Offset, val p1: Offset, val p2: Offset, val p3: Offset,
    val start: Float, val end: Float,
    val color: Color,
    val leafLight: Color, val leafDeep: Color,
    val width: Float,
    val leaves: Int,
    val leafSize: Float,
    val firstSide: Float = 1f,
    val wave: Float = 6f,
    val waveTurns: Float = 1.6f,
    val tendrilEvery: Int = 0,
    val curlAtTip: Boolean = true,
    val curlClockwise: Boolean = true,
    val glow: Boolean = false
)

/** Every vine on the splash, laid out relative to the logo centre (cx, cy). */
private fun buildVines(cx: Float, cy: Float, w: Float, h: Float): List<Vine> {
    val c = Offset(cx, cy)
    val list = mutableListOf<Vine>()

    // ── 1. Gold corner vines (with a side shoot each) ────────────────────────
    data class Corner(val p1: Offset, val p2: Offset, val p3: Offset, val sx: Float, val sy: Float, val side: Float)
    val corners = listOf(
        Corner(Offset(cx - w*0.28f, cy - h*0.06f), Offset(w*0.10f, h*0.14f), Offset(w*0.02f, h*0.02f), -1f, -1f,  1f),
        Corner(Offset(cx + w*0.28f, cy - h*0.06f), Offset(w*0.90f, h*0.14f), Offset(w*0.98f, h*0.02f),  1f, -1f, -1f),
        Corner(Offset(cx + w*0.22f, cy + h*0.08f), Offset(w*0.90f, h*0.86f), Offset(w*0.98f, h*0.98f),  1f,  1f,  1f),
        Corner(Offset(cx - w*0.22f, cy + h*0.08f), Offset(w*0.10f, h*0.86f), Offset(w*0.02f, h*0.98f), -1f,  1f, -1f)
    )
    corners.forEachIndexed { i, k ->
        val main = Vine(
            p0 = c, p1 = k.p1, p2 = k.p2, p3 = k.p3,
            start = 0f, end = 0.78f,
            color = VineGold, leafLight = LeafGoldLight, leafDeep = LeafGoldDeep,
            width = 4.2f, leaves = 6, leafSize = 30f, firstSide = k.side,
            wave = w * 0.020f, tendrilEvery = 3, curlClockwise = i % 2 == 0, glow = true
        )
        list += main
        // Side shoot branching off about halfway along, once the main vine reaches it
        val mid = cubicPoint(main.p0, main.p1, main.p2, main.p3, 0.52f)
        list += Vine(
            p0 = mid,
            p1 = Offset(mid.x + k.sx*w*0.04f, mid.y + k.sy*h*0.08f),
            p2 = Offset(mid.x + k.sx*w*0.09f, mid.y + k.sy*h*0.13f),
            p3 = Offset(mid.x + k.sx*w*0.11f, mid.y + k.sy*h*0.18f),
            start = 0.44f, end = 0.86f,
            color = VineGoldDim, leafLight = LeafGoldLight, leafDeep = LeafGoldDeep,
            width = 2.4f, leaves = 3, leafSize = 20f, firstSide = -k.side,
            wave = w * 0.008f, curlClockwise = i % 2 != 0
        )
    }

    // ── 2. Blue edge vines (with a side shoot each) ──────────────────────────
    data class Edge(val p1: Offset, val p2: Offset, val p3: Offset, val s1: Offset, val s2: Offset, val s3: Offset, val side: Float)
    val edges = listOf(
        // Top
        Edge(Offset(cx + w*0.05f, cy - h*0.16f), Offset(cx - w*0.08f, h*0.07f), Offset(cx, h*0.01f),
             Offset(w*0.07f, -h*0.05f), Offset(w*0.12f, -h*0.10f), Offset(w*0.09f, -h*0.18f),  1f),
        // Right
        Edge(Offset(cx + w*0.18f, cy + h*0.04f), Offset(w*0.93f, cy - h*0.06f), Offset(w*0.99f, cy),
             Offset(w*0.05f, h*0.07f), Offset(w*0.10f, h*0.12f), Offset(w*0.05f, h*0.20f), -1f),
        // Bottom
        Edge(Offset(cx - w*0.05f, cy + h*0.16f), Offset(cx + w*0.08f, h*0.93f), Offset(cx, h*0.99f),
             Offset(-w*0.07f, h*0.05f), Offset(-w*0.12f, h*0.10f), Offset(-w*0.09f, h*0.18f),  1f),
        // Left
        Edge(Offset(cx - w*0.18f, cy - h*0.04f), Offset(w*0.07f, cy + h*0.06f), Offset(w*0.01f, cy),
             Offset(-w*0.05f, -h*0.07f), Offset(-w*0.10f, -h*0.12f), Offset(-w*0.05f, -h*0.20f), -1f)
    )
    edges.forEachIndexed { i, e ->
        val main = Vine(
            p0 = c, p1 = e.p1, p2 = e.p2, p3 = e.p3,
            start = 0.10f, end = 0.82f,
            color = VineBlue, leafLight = LeafBlueLight, leafDeep = LeafBlueDeep,
            width = 3.0f, leaves = 5, leafSize = 22f, firstSide = e.side,
            wave = w * 0.014f, waveTurns = 1.3f, tendrilEvery = 2, curlClockwise = i % 2 != 0
        )
        list += main
        val mid = cubicPoint(main.p0, main.p1, main.p2, main.p3, 0.5f)
        list += Vine(
            p0 = mid, p1 = mid + e.s1, p2 = mid + e.s2, p3 = mid + e.s3,
            start = 0.50f, end = 0.88f,
            color = VineBlueDim, leafLight = LeafBlueLight, leafDeep = LeafBlueDeep,
            width = 1.8f, leaves = 2, leafSize = 15f, firstSide = -e.side,
            wave = w * 0.004f, curlClockwise = i % 2 == 0
        )
    }

    // ── 3. Short tendrils hugging the logo ───────────────────────────────────
    listOf(
        listOf(Offset(cx - w*0.06f, cy - h*0.03f), Offset(cx - w*0.12f, cy - h*0.10f), Offset(cx - w*0.08f, cy - h*0.16f), Offset(cx - w*0.04f, cy - h*0.20f)),
        listOf(Offset(cx + w*0.06f, cy - h*0.03f), Offset(cx + w*0.12f, cy - h*0.10f), Offset(cx + w*0.08f, cy - h*0.16f), Offset(cx + w*0.04f, cy - h*0.20f)),
        listOf(Offset(cx - w*0.05f, cy + h*0.04f), Offset(cx - w*0.10f, cy + h*0.10f), Offset(cx - w*0.06f, cy + h*0.16f), Offset(cx - w*0.02f, cy + h*0.20f)),
        listOf(Offset(cx + w*0.05f, cy + h*0.04f), Offset(cx + w*0.10f, cy + h*0.10f), Offset(cx + w*0.06f, cy + h*0.16f), Offset(cx + w*0.02f, cy + h*0.20f))
    ).forEachIndexed { i, p ->
        val gold = i < 2
        list += Vine(
            p0 = p[0], p1 = p[1], p2 = p[2], p3 = p[3],
            start = if (gold) 0.20f else 0.26f, end = 0.70f,
            color = if (gold) VineGoldDim else VineBlueDim,
            leafLight = if (gold) LeafGoldLight else LeafBlueLight,
            leafDeep  = if (gold) LeafGoldDeep else LeafBlueDeep,
            width = 1.6f, leaves = 2, leafSize = 12f, firstSide = if (i % 2 == 0) 1f else -1f,
            wave = w * 0.003f, curlClockwise = i % 2 == 0
        )
    }
    return list
}

// ─────────────────────────────────────────────────────────────────────────────
// Drawing
// ─────────────────────────────────────────────────────────────────────────────

/** How far along its own length (0–1) a vine has grown at overall progress [g]. */
private fun Vine.grown(g: Float): Float = ((g - start) / (end - start)).coerceIn(0f, 1f)

/** A point on the vine including its gentle side-to-side wave. */
private fun Vine.point(t: Float): Offset {
    val base = cubicPoint(p0, p1, p2, p3, t)
    if (wave == 0f) return base
    val n = normalAt(t)
    // The wave fades out at both ends so the vine still starts and ends where it should.
    val amount = wave * sin(t * waveTurns * 2f * PI.toFloat()) * sin(t * PI.toFloat())
    return base + n * amount
}

/** Unit vector along the vine at [t]. */
private fun Vine.tangentAt(t: Float): Offset {
    val d = cubicDerivative(p0, p1, p2, p3, t.coerceIn(0.001f, 0.999f))
    val len = sqrt(d.x * d.x + d.y * d.y).coerceAtLeast(0.0001f)
    return Offset(d.x / len, d.y / len)
}

/** Unit vector pointing to the vine's left at [t]. */
private fun Vine.normalAt(t: Float): Offset {
    val tg = tangentAt(t)
    return Offset(-tg.y, tg.x)
}

/** Wide, faint glow behind a vine to give it a luminous quality. */
private fun DrawScope.drawVineGlow(v: Vine, g: Float) {
    val grown = v.grown(g)
    if (grown <= 0f) return
    val steps = 48
    val path = Path()
    for (i in 0..steps) {
        val pt = v.point(grown * i / steps)
        if (i == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
    }
    drawPath(path, color = GlowGold, style = Stroke(width = v.width * 3.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** The stem (tapered), its leaves, tendrils, the glowing bud at the tip and the end curl. */
private fun DrawScope.drawVine(v: Vine, g: Float, sway: Float) {
    val grown = v.grown(g)
    if (grown <= 0f) return

    // ── Stem: short segments, thick at the root and thin at the tip ───────────
    val steps = 64
    val count = (steps * grown).toInt().coerceAtLeast(1)
    var prev = v.point(0f)
    for (i in 1..count) {
        val t  = if (i == count) grown else i.toFloat() / steps
        val pt = v.point(t)
        // Thins along the whole vine, and extra-fine right at a still-growing tip.
        val alongTaper = 1f - 0.72f * t
        val tipTaper   = if (grown < 1f) ((grown - t) / 0.10f + 0.35f).coerceIn(0.35f, 1f) else 1f
        drawLine(
            color       = v.color,
            start       = prev,
            end         = pt,
            strokeWidth = (v.width * alongTaper * tipTaper).coerceAtLeast(0.8f),
            cap         = StrokeCap.Round
        )
        prev = pt
    }

    // ── Leaves (and tendrils) sprout as the tip passes each joint ─────────────
    for (k in 0 until v.leaves) {
        val at = 0.16f + k * (0.76f / v.leaves.coerceAtLeast(1))   // joint position along the vine
        if (grown <= at) continue
        // Time since the tip passed this joint, scaled to the vine's growth speed.
        val age  = ((grown - at) / 0.20f).coerceIn(0f, 1f)
        val side = if (k % 2 == 0) v.firstSide else -v.firstSide
        val node = v.point(at)
        val tg   = v.tangentAt(at)
        val stemAngle = atan2(tg.y, tg.x) * 180f / PI.toFloat()

        // Opens from 12° (a bud lying along the stem) to ~58°, with a springy overshoot.
        val open  = easeOutBack(age)
        val angle = stemAngle + side * (12f + 46f * open) + sway * 4f * age
        // Leaves get a little smaller toward the tip.
        val length = v.leafSize * (1f - 0.35f * at) * easeOutBack(age).coerceAtLeast(0f)
        if (length > 1f) drawLeaf(node, angle, length, v.leafLight, v.leafDeep, alpha = min(1f, age * 2.5f))

        // Some joints also curl out a tendril on the other side.
        if (v.tendrilEvery > 0 && k % v.tendrilEvery == v.tendrilEvery - 1) {
            val tAge = ((grown - at - 0.05f) / 0.30f).coerceIn(0f, 1f)
            if (tAge > 0f) {
                val n = v.normalAt(at) * (-side)
                drawTendril(node, n, tg, v.leafSize * 0.9f, tAge, v.color.copy(alpha = 0.85f), clockwise = side > 0)
            }
        }
    }

    // ── Glowing bud at a still-growing tip ────────────────────────────────────
    if (grown < 1f) {
        val tip = v.point(grown)
        drawCircle(
            brush  = Brush.radialGradient(listOf(v.leafLight.copy(alpha = 0.7f), Color.Transparent), center = tip, radius = v.width * 2.2f),
            radius = v.width * 2.2f,
            center = tip
        )
        drawCircle(color = v.leafLight, radius = (v.width * 0.6f).coerceAtLeast(1.2f), center = tip)
    }

    // ── Spiral curl at the end once fully grown ───────────────────────────────
    if (v.curlAtTip) {
        val curlAge = ((g - v.end) / 0.10f).coerceIn(0f, 1f)
        if (curlAge > 0f) {
            val endPt = v.point(1f)
            val tg = v.tangentAt(1f)
            drawCurl(endPt, atan2(tg.y, tg.x), v.width * 2.6f + 4f, curlAge, v.color, v.curlClockwise)
        }
    }
}

/**
 * One leaf: a pointed almond shape on a short leaf-stalk, shaded from a deep
 * base to a light tip, with a centre vein and two pairs of side veins.
 * [angleDeg] points from the stem out to the leaf tip.
 */
private fun DrawScope.drawLeaf(at: Offset, angleDeg: Float, length: Float, light: Color, deep: Color, alpha: Float) {
    val stalk = length * 0.22f
    val leafLen = length
    val half = leafLen * 0.30f   // half the leaf's width at its widest
    translate(at.x, at.y) {
        rotate(angleDeg, pivot = Offset.Zero) {
            // Leaf-stalk
            drawLine(deep.copy(alpha = alpha), Offset.Zero, Offset(stalk, 0f), strokeWidth = (length * 0.07f).coerceAtLeast(1f), cap = StrokeCap.Round)
            // Blade: base at (stalk, 0), tip at (stalk + leafLen, 0)
            val b = stalk
            val e = stalk + leafLen
            val blade = Path().apply {
                moveTo(b, 0f)
                cubicTo(b + leafLen * 0.25f, -half * 1.15f, b + leafLen * 0.70f, -half * 0.90f, e, 0f)
                cubicTo(b + leafLen * 0.70f,  half * 0.90f, b + leafLen * 0.25f,  half * 1.15f, b, 0f)
                close()
            }
            drawPath(
                blade,
                brush = Brush.linearGradient(listOf(deep, light), start = Offset(b, 0f), end = Offset(e, 0f)),
                alpha = alpha
            )
            // Thin bright edge
            drawPath(blade, color = light.copy(alpha = alpha * 0.55f), style = Stroke(width = 0.8f))
            // Centre vein
            val vein = deep.copy(alpha = alpha * 0.85f)
            drawLine(vein, Offset(b, 0f), Offset(e - leafLen * 0.08f, 0f), strokeWidth = (length * 0.035f).coerceAtLeast(0.7f), cap = StrokeCap.Round)
            // Side veins, angled toward the tip
            for (f in listOf(0.32f, 0.58f)) {
                val x = b + leafLen * f
                val reach = half * (if (f < 0.5f) 0.75f else 0.55f)
                drawLine(vein, Offset(x, 0f), Offset(x + reach * 0.9f, -reach), strokeWidth = 0.7f, cap = StrokeCap.Round)
                drawLine(vein, Offset(x, 0f), Offset(x + reach * 0.9f,  reach), strokeWidth = 0.7f, cap = StrokeCap.Round)
            }
        }
    }
}

/**
 * A curly tendril: reaches out from [from] along [out] (a unit vector away from
 * the stem), leaning forward along [forward], and coils into a spiral at its end.
 * [age] 0–1 = how far it has grown.
 */
private fun DrawScope.drawTendril(from: Offset, out: Offset, forward: Offset, reach: Float, age: Float, color: Color, clockwise: Boolean) {
    val steps = 40
    val dir = if (clockwise) 1f else -1f
    val path = Path()
    // Straight-ish reach for the first 45 %, then a tightening coil.
    val reachEnd = from + out * (reach * 0.55f) + forward * (reach * 0.35f)
    val baseAngle = atan2(reachEnd.y - from.y, reachEnd.x - from.x)
    var last = from
    path.moveTo(from.x, from.y)
    for (i in 1..steps) {
        val s = i.toFloat() / steps * age
        val pt = if (s < 0.45f) {
            val u = s / 0.45f
            // Gentle arc toward reachEnd
            val mid = from + out * (reach * 0.30f)
            quadPoint(from, mid, reachEnd, u)
        } else {
            val u = (s - 0.45f) / 0.55f
            val a = baseAngle + dir * u * 2.2f * PI.toFloat()
            val r = reach * 0.22f * (1f - 0.75f * u)
            val centre = reachEnd + Offset(cos(baseAngle + dir * PI.toFloat() / 2f), sin(baseAngle + dir * PI.toFloat() / 2f)) * (reach * 0.22f)
            centre + Offset(cos(a - dir * PI.toFloat() / 2f), sin(a - dir * PI.toFloat() / 2f)) * r
        }
        path.lineTo(pt.x, pt.y)
        last = pt
    }
    drawPath(path, color = color.copy(alpha = color.alpha * min(1f, age * 3f)), style = Stroke(width = 1.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** A spiral curl at a vine's end, continuing the vine's direction [angleRad]. */
private fun DrawScope.drawCurl(at: Offset, angleRad: Float, maxRadius: Float, age: Float, color: Color, clockwise: Boolean) {
    val dir = if (clockwise) 1f else -1f
    val turns = 1.6f
    val steps = 40
    // Centre of the curl sits off to one side so the curl leaves the vine smoothly.
    val centre = at + Offset(cos(angleRad + dir * PI.toFloat() / 2f), sin(angleRad + dir * PI.toFloat() / 2f)) * maxRadius
    val startA = angleRad - dir * PI.toFloat() / 2f
    val path = Path()
    for (i in 0..steps) {
        val u = i.toFloat() / steps * age
        val a = startA + dir * u * turns * 2f * PI.toFloat()
        val r = maxRadius * (1f - 0.8f * u)
        val pt = centre + Offset(cos(a), sin(a)) * r
        if (i == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
    }
    drawPath(path, color = color.copy(alpha = min(1f, age * 2f)), style = Stroke(width = 1.6f, cap = StrokeCap.Round))
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

/** First derivative (direction) of a cubic bezier at [t]. */
private fun cubicDerivative(p0: Offset, p1: Offset, p2: Offset, p3: Offset, t: Float): Offset {
    val u = 1f - t
    return Offset(
        x = 3*u*u*(p1.x - p0.x) + 6*u*t*(p2.x - p1.x) + 3*t*t*(p3.x - p2.x),
        y = 3*u*u*(p1.y - p0.y) + 6*u*t*(p2.y - p1.y) + 3*t*t*(p3.y - p2.y)
    )
}

/** Evaluates a quadratic bezier at [t]. */
private fun quadPoint(p0: Offset, p1: Offset, p2: Offset, t: Float): Offset {
    val u = 1f - t
    return Offset(u*u*p0.x + 2*u*t*p1.x + t*t*p2.x, u*u*p0.y + 2*u*t*p1.y + t*t*p2.y)
}

/** Springy ease: overshoots a little past 1 then settles — makes leaves "pop" open. */
private fun easeOutBack(x: Float): Float {
    if (x <= 0f) return 0f
    if (x >= 1f) return 1f
    val c1 = 1.70158f
    val c3 = c1 + 1f
    val y = x - 1f
    return 1f + c3 * y * y * y + c1 * y * y
}
