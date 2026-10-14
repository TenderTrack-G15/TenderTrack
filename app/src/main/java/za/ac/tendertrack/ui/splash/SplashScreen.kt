package za.ac.tendertrack.ui.splash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import za.ac.tendertrack.ui.theme.AppColor
import za.ac.tendertrack.ui.theme.AppType
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/*
 * The animated splash shown when the app starts:
 *
 *   0.0 – 1.0 s  a man and a woman in business suits walk in from either side
 *   0.9 – 1.35 s they reach out to each other
 *   1.35 – 2.15 s they shake hands (two pumps, with a small "deal" spark)
 *   2.15 – 2.85 s they fade out while the briefcase logo zooms in from their hands
 *   2.65 – 3.3 s  "TenderTrack" appears under the logo
 *   3.3 – 3.8 s  the logo zooms towards the viewer and the splash fades,
 *                revealing the landing page underneath
 *
 * Tapping anywhere skips it. If the phone has animations turned off
 * (Settings → Accessibility → Remove animations), it finishes straight away.
 *
 * Everything is drawn in "design units" on a 320 × 240 stage, scaled to the
 * screen, so it looks the same on every phone. The suits, shirts, tie and
 * spark use the app's own colours (AppColor: navy, slate, white and indigo);
 * only skin and hair have colours of their own.
 */

private const val TOTAL_MS = 3800

/** The point where the two hands meet, and where the logo appears (design units). */
private const val CX = 160f
private const val CY = 124f

private val SkinMan = Color(0xFF8D5524)
private val SkinWoman = Color(0xFFC68642)
private val HairMan = Color(0xFF1E293B)
private val HairWoman = Color(0xFF2B1B12)

// The outfits, all from the app's colour scheme.
private val ManSuit = AppColor.Ink               // navy suit
private val ManLapel = Color(0xFF1E293B)         // a shade lighter than the suit, so the lapels show
private val ManShoes = AppColor.InkSoft
private val WomanBlazer = AppColor.InkSoft       // slate blazer
private val WomanLapel = AppColor.NeutralInk
private val WomanSkirt = AppColor.Ink            // navy pencil skirt and court shoes
private val Shirt = Color.White
private val Tie = AppColor.InfoInk               // indigo, the app's accent
private val PocketSquare = AppColor.InfoBg
private val Buttons = AppColor.MutedLight
private val Spark = AppColor.InfoInk

@Composable
fun TenderTrackSplash(onFinished: () -> Unit) {
    val time = remember { Animatable(0f) }
    val finish by rememberUpdatedState(onFinished)
    var done by remember { mutableStateOf(false) }
    fun end() {
        if (!done) {
            done = true
            finish()
        }
    }

    LaunchedEffect(Unit) {
        time.animateTo(TOTAL_MS.toFloat(), animationSpec = tween(TOTAL_MS, easing = LinearEasing))
        end()
    }

    val textMeasurer = rememberTextMeasurer()
    val wordmark = remember(textMeasurer) { textMeasurer.measure("TenderTrack", AppType.H1) }

    Box(
        Modifier
            .fillMaxSize()
            // The whole splash fades at the end, so the landing page shows through.
            .graphicsLayer { alpha = 1f - seg(time.value, 3450f, 3800f) }
            .background(AppColor.Background)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = "Skip"
            ) { end() }
            .semantics { contentDescription = "TenderTrack" }
    ) {
        // The two people, faded as one picture.
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = seg(time.value, 0f, 400f) * (1f - seg(time.value, 2150f, 2550f)) }
        ) { drawPeople(time.value) }

        // The briefcase logo, also faded as one picture.
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = seg(time.value, 2250f, 2400f) }
        ) { drawLogo(time.value) }

        // "TenderTrack" under the logo.
        Canvas(Modifier.fillMaxSize()) { drawWordmark(time.value, wordmark) }
    }
}

// ---------------------------------------------------------------------------
// Timeline helpers
// ---------------------------------------------------------------------------

/** Progress from 0 to 1 between [start] and [end] milliseconds. */
private fun seg(t: Float, start: Float, end: Float): Float = ((t - start) / (end - start)).coerceIn(0f, 1f)

private fun outCubic(x: Float) = 1f - (1f - x).pow(3)
private fun inCubic(x: Float) = x * x * x
private fun inOutCubic(x: Float) = if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).pow(3) / 2f
private fun outBack(x: Float): Float {
    val c1 = 1.70158f
    val c3 = c1 + 1f
    return 1f + c3 * (x - 1f).pow(3) + c1 * (x - 1f).pow(2)
}
private fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f

/** Screen pixels per design unit, and where design (0, 0) sits on screen. */
private class Stage(val scale: Float, val origin: Offset)

private fun DrawScope.stage(): Stage {
    val s = min(size.width / 250f, size.height / 460f)
    return Stage(s, Offset(size.width / 2f - CX * s, size.height * 0.42f - CY * s))
}

/** Draws [block] in design units. */
private inline fun DrawScope.onStage(stage: Stage, block: DrawScope.() -> Unit) {
    withTransform({
        translate(stage.origin.x, stage.origin.y)
        scale(stage.scale, stage.scale, pivot = Offset.Zero)
    }, block)
}

// ---------------------------------------------------------------------------
// The people
// ---------------------------------------------------------------------------

private fun DrawScope.drawPeople(t: Float) {
    if (t >= 2550f) return
    val walk = outCubic(seg(t, 0f, 1000f))
    val slide = 170f * (1f - walk)
    val bob = -abs(sin(PI.toFloat() * 4f * seg(t, 0f, 1000f))) * 3f * (1f - walk)
    val reach = inOutCubic(seg(t, 900f, 1350f))
    val shake = seg(t, 1350f, 2150f)
    val shaking = shake > 0f && shake < 1f
    val pump = if (shaking) -4f * sin(PI.toFloat() * 4f * shake) * (1f - shake * 0.4f) else 0f
    val spark = if (shaking) sin(PI.toFloat() * shake) else 0f
    val shrink = 1f - 0.08f * seg(t, 2150f, 2550f)

    onStage(stage()) {
        withTransform({ scale(shrink, shrink, pivot = Offset(CX, CY)) }) {
            // Shadows stay on the ground while the people walk in.
            drawOval(AppColor.Line, topLeft = Offset(88f - slide, 209f), size = Size(48f, 8f))
            drawOval(AppColor.Line, topLeft = Offset(184f + slide, 209f), size = Size(48f, 8f))

            translate(left = -slide, top = bob) { drawMan(reach, pump) }
            translate(left = slide, top = bob) { drawWoman(reach, pump) }

            if (spark > 0f) {
                val lines = listOf(
                    Offset(160f, 106f) to Offset(160f, 98f),
                    Offset(148f, 109f) to Offset(143f, 103f),
                    Offset(172f, 109f) to Offset(177f, 103f)
                )
                lines.forEach { (a, b) ->
                    drawLine(
                        Spark, a.copy(y = a.y + pump), b.copy(y = b.y + pump),
                        strokeWidth = 2.6f, cap = StrokeCap.Round, alpha = spark
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawMan(reach: Float, pump: Float) {
    // Back arm, hanging, in the suit's sleeve.
    limb(listOf(Offset(95f, 92f), Offset(91f, 118f), Offset(90f, 138f)), 9f, ManSuit)
    drawCircle(SkinMan, radius = 5f, center = Offset(90f, 141f))
    // Suit trousers and shoes.
    box(99f, 148f, 110f, 205f, 4f, ManSuit)
    box(114f, 148f, 125f, 205f, 4f, ManSuit)
    box(95f, 203f, 111f, 212f, 4f, ManShoes)
    box(113f, 203f, 129f, 212f, 4f, ManShoes)
    // Neck and suit jacket.
    box(107f, 72f, 117f, 88f, 3f, SkinMan)
    box(92f, 84f, 132f, 156f, 13f, ManSuit)
    // White shirt, lapels, tie, buttons and pocket square.
    poly(Shirt, 103f, 84f, 121f, 84f, 112f, 106f)
    poly(ManLapel, 103f, 84f, 112f, 108f, 107f, 113f, 97f, 89f)
    poly(ManLapel, 121f, 84f, 112f, 108f, 117f, 113f, 127f, 89f)
    poly(Tie, 110f, 88f, 114f, 88f, 115.5f, 103f, 112f, 108f, 108.5f, 103f)
    drawCircle(Buttons, radius = 1.7f, center = Offset(112f, 122f))
    drawCircle(Buttons, radius = 1.7f, center = Offset(112f, 134f))
    poly(PocketSquare, 120f, 98f, 127f, 98f, 123.5f, 102f)
    // Head and short hair.
    drawCircle(SkinMan, radius = 15f, center = Offset(112f, 61f))
    drawArc(HairMan, 180f, 180f, useCenter = true, topLeft = Offset(96f, 44f), size = Size(32f, 26f))
    // Front arm, reaching to the handshake point.
    val elbow = Offset(lerp(134f, 141f, reach), lerp(118f, 117f, reach) + pump * 0.5f)
    val hand = Offset(lerp(134f, 156.5f, reach), lerp(140f, 124f, reach) + pump)
    limb(listOf(Offset(129f, 92f), elbow, hand), 9f, ManSuit)
    drawCircle(SkinMan, radius = 5.5f, center = hand)
}

private fun DrawScope.drawWoman(reach: Float, pump: Float) {
    // Neat bob, behind the head.
    box(193f, 48f, 223f, 82f, 13f, HairWoman)
    // Back arm, hanging, in the blazer's sleeve.
    limb(listOf(Offset(221f, 94f), Offset(225f, 118f), Offset(226f, 138f)), 8f, WomanBlazer)
    drawCircle(SkinWoman, radius = 4.8f, center = Offset(226f, 141f))
    // Legs and court shoes.
    box(201f, 176f, 207f, 205f, 3f, SkinWoman)
    box(209f, 176f, 215f, 205f, 3f, SkinWoman)
    box(197f, 203f, 208f, 211f, 4f, WomanSkirt)
    box(208f, 203f, 219f, 211f, 4f, WomanSkirt)
    // Pencil skirt.
    poly(WomanSkirt, 197f, 146f, 219f, 146f, 217.5f, 181f, 198.5f, 181f)
    // Neck and fitted blazer.
    box(203f, 72f, 213f, 90f, 3f, SkinWoman)
    drawPath(Path().apply {
        moveTo(200f, 86f); lineTo(216f, 86f); quadraticBezierTo(222f, 86f, 222.5f, 92f)
        lineTo(220f, 126f); lineTo(222f, 150f); quadraticBezierTo(222f, 153f, 219f, 153f)
        lineTo(197f, 153f); quadraticBezierTo(194f, 153f, 194f, 150f)
        lineTo(196f, 126f); lineTo(193.5f, 92f); quadraticBezierTo(194f, 86f, 200f, 86f); close()
    }, WomanBlazer)
    // White blouse, lapels and button.
    poly(Shirt, 202.5f, 86f, 213.5f, 86f, 208f, 104f)
    poly(WomanLapel, 202.5f, 86f, 208f, 106f, 204f, 111f, 197f, 90f)
    poly(WomanLapel, 213.5f, 86f, 208f, 106f, 212f, 111f, 219f, 90f)
    drawCircle(Buttons, radius = 1.7f, center = Offset(208f, 118f))
    // Head and fringe.
    drawCircle(SkinWoman, radius = 14f, center = Offset(208f, 62f))
    drawArc(HairWoman, 180f, 180f, useCenter = true, topLeft = Offset(193f, 46f), size = Size(30f, 22f))
    // Front arm, reaching to the handshake point.
    val elbow = Offset(lerp(190f, 180f, reach), lerp(119f, 117f, reach) + pump * 0.5f)
    val hand = Offset(lerp(190f, 163.5f, reach), lerp(140f, 124f, reach) + pump)
    limb(listOf(Offset(195f, 94f), elbow, hand), 8f, WomanBlazer)
    drawCircle(SkinWoman, radius = 5.2f, center = hand)
}

/** A filled shape through the given x, y pairs. */
private fun DrawScope.poly(color: Color, vararg xy: Float) {
    val path = Path().apply {
        moveTo(xy[0], xy[1])
        var i = 2
        while (i < xy.size) {
            lineTo(xy[i], xy[i + 1]); i += 2
        }
        close()
    }
    drawPath(path, color)
}

private fun DrawScope.box(left: Float, top: Float, right: Float, bottom: Float, radius: Float, color: Color) {
    drawRoundRect(color, topLeft = Offset(left, top), size = Size(right - left, bottom - top), cornerRadius = CornerRadius(radius, radius))
}

private fun DrawScope.limb(points: List<Offset>, width: Float, color: Color) {
    val path = Path().apply {
        moveTo(points[0].x, points[0].y)
        points.drop(1).forEach { lineTo(it.x, it.y) }
    }
    drawPath(path, color, style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

// ---------------------------------------------------------------------------
// The logo and the name
// ---------------------------------------------------------------------------

private const val LOGO_RADIUS = 46f

private fun DrawScope.drawLogo(t: Float) {
    if (t < 2250f) return
    // Zooms in from the handshake with a small overshoot, then towards the viewer at the end.
    val zoom = if (t < 3300f) outBack(seg(t, 2250f, 2850f)) else 1f + 6f * inCubic(seg(t, 3300f, 3800f))
    if (zoom <= 0f) return
    onStage(stage()) {
        withTransform({ scale(zoom, zoom, pivot = Offset(CX, CY)) }) {
            drawCircle(AppColor.Ink, radius = LOGO_RADIUS, center = Offset(CX, CY))
            drawBriefcase(CX, CY, 2.15f)
        }
    }
}

/** The same briefcase as the app icon (a 24-unit drawing), centred on (cx, cy) and scaled by k. */
private fun DrawScope.drawBriefcase(cx: Float, cy: Float, k: Float) {
    fun p(x: Float, y: Float) = Offset(cx + (x - 12f) * k, cy + (y - 12f) * k)
    val stroke = Stroke(width = 1.8f * k)
    drawRoundRect(
        Color.White, topLeft = p(1f, 7f), size = Size(22f * k, 13f * k),
        cornerRadius = CornerRadius(2f * k, 2f * k), style = stroke
    )
    val handle = Path().apply {
        val a = p(8.5f, 7f); moveTo(a.x, a.y)
        val b = p(8.5f, 5.5f); lineTo(b.x, b.y)
        val c1 = p(8.5f, 4f); val c2 = p(10f, 4f); quadraticBezierTo(c1.x, c1.y, c2.x, c2.y)
        val d = p(14f, 4f); lineTo(d.x, d.y)
        val e1 = p(15.5f, 4f); val e2 = p(15.5f, 5.5f); quadraticBezierTo(e1.x, e1.y, e2.x, e2.y)
        val f = p(15.5f, 7f); lineTo(f.x, f.y)
    }
    drawPath(handle, Color.White, style = stroke)
    drawLine(Color.White, p(1f, 12f), p(23f, 12f), strokeWidth = 1.8f * k)
}

private fun DrawScope.drawWordmark(t: Float, wordmark: TextLayoutResult) {
    val alpha = seg(t, 2650f, 3000f) * (1f - seg(t, 3300f, 3450f))
    if (alpha <= 0f) return
    val stage = stage()
    val x = size.width / 2f - wordmark.size.width / 2f
    val y = stage.origin.y + (CY + LOGO_RADIUS + 18f) * stage.scale
    drawText(wordmark, topLeft = Offset(x, y), alpha = alpha)
}
