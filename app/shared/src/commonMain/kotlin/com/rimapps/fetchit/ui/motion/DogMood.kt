package com.rimapps.fetchit.ui.motion

import androidx.compose.animation.core.VectorConverter
import androidx.compose.ui.draw.clipToBounds
import kotlinx.coroutines.delay
import kotlin.random.Random
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rimapps.fetchit.ui.Ink
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/** What Scout is up to. Every mood is the same ink drawing, posed differently. */
enum class DogMood { Idle, Wag, Sniffing, Excited, HeadShake, Sleeping }

private fun path(d: String) = PathParser().parsePathString(d).toPath()

// Ink-line dog from the design canvas (viewBox 0 0 100 112): floppy black ears, eye patch, red tongue and collar.
private val body = path("M30 62 C22 82 24 104 38 106 H66 C80 104 80 82 72 62 Z")
private val head = path("M20 32 C19 15 61 15 60 32 C62 49 52 61 40 61 C28 61 18 49 20 32 Z")
private val earL = path("M23 22 C10 16 3 30 7 43 C10 49 18 44 21 33 Z")
private val earR = path("M57 22 C70 16 77 30 73 43 C70 49 62 44 59 33 Z")
// The face matches ScoutHero (big outlined eyes, patch behind the right one), scaled into the head.
private val mouth = path("M40 44.8 V48.6 M35.4 47.8 Q40 52.6 44.6 47.8")
private val tongue = path("M38.2 50.2 Q40 55.4 41.8 50.2 Z")
private val tongueOut = path("M37.7 50.2 Q40 61 42.3 50.2 Z")
private val collar = path("M34 64 Q51 72 68 64")
private val tail = path("M75 96 C88 97 94 87 90 77")
private val legs = path("M43 88 V105 M59 88 V105")
private val closedEyeL = path("M26.6 33.8 Q31 37.6 35.4 33.8")
private val closedEyeR = path("M44.6 33.4 Q49 37.2 53.4 33.4")
private val bone = path("M30 50.5 C26.5 47.5 24.5 53 28.5 53.5 C24.5 54.5 27 59.5 30.5 56.5 L49.5 56.5 C53 59.5 55.5 54.5 51.5 53.5 C55.5 53 53.5 47.5 50 50.5 Z")
private val zed = path("M0 0 H6 L0 7 H6")

/** One frame of Scout. Angles in degrees, offsets in viewBox units. */
internal data class DogPose(
    val tail: Float = 0f,
    val earL: Float = 0f,
    val earR: Float = 0f,
    val headTilt: Float = 0f,
    val headDx: Float = 0f,
    val headDy: Float = 0f,
    val breathe: Float = 1f,
    val dy: Float = 0f,
    val squash: Float = 1f,
    val lean: Float = 0f,
    val nose: Float = 1f,
    val eyesClosed: Boolean = false,
    val tongueOut: Boolean = false,
    val bone: Boolean = false,
    /** Running: how far each leg is kicked back (0 planted … 1), null = standing legs. */
    val run: Pair<Float, Float>? = null,
    /** Sleeping: 0…1 through the Zzz cycle, null = none. */
    val zzz: Float? = null,
    /** Where the eyes look: -1…1 on each axis (pupils shift ~2 units). */
    val look: Offset = Offset.Zero,
)

/** Pose for [mood] at [t] ms (t = 0 is the still pose used with reduce motion). [shake] is HeadShake's 0→1 progress. */
private fun pose(mood: DogMood, t: Long, bone: Boolean, shake: Float): DogPose {
    val s = t / 1000f
    fun wave(period: Float, phase: Float = 0f) = sin(2f * PI.toFloat() * (s / period + phase))
    val breathe = 1f + 0.015f * wave(2.4f)
    return when (mood) {
        DogMood.Idle -> DogPose(tail = 4f * wave(1.6f), breathe = breathe, bone = bone)
        DogMood.Wag -> DogPose(tail = if (t == 0L) 0f else 1f + 9f * wave(0.84f), breathe = breathe, bone = bone)
        DogMood.Sniffing -> {
            // Nose down, searching side to side; the nose twitches in short bursts, ears bounce.
            val burst = (s % 1.2f) < 0.5f
            DogPose(
                tail = 6f * wave(0.6f), breathe = breathe,
                headDx = 2.5f * wave(1.4f), headDy = 2.5f + 1.2f * abs(wave(0.7f)), headTilt = 4f * wave(1.4f, 0.25f),
                earL = 5f * wave(0.35f), earR = -5f * wave(0.35f),
                nose = if (burst && t > 0) 1f + 0.14f * abs(wave(0.12f)) else 1f, bone = bone,
            )
        }
        DogMood.Excited -> {
            // A hop every 1.1 s (0.45 s in the air), fast wag, tongue out, ears fly up.
            val u = (s % 1.1f) / 0.45f
            val air = if (t > 0 && u < 1f) sin(PI.toFloat() * u) else 0f
            val land = if (t > 0 && u in 1f..1.35f) sin(PI.toFloat() * (u - 1f) / 0.35f) else 0f
            DogPose(
                tail = if (t == 0L) 8f else 2f + 13f * wave(0.28f), dy = -11f * air, squash = 1f - 0.06f * land + 0.03f * air,
                earL = 16f * air, earR = -16f * air, tongueOut = !bone, bone = bone,
            )
        }
        DogMood.HeadShake -> {
            val damp = 1f - shake
            val w = sin(shake * 3f * 2f * PI.toFloat())
            DogPose(
                headTilt = 13f * w * damp, headDx = 2f * w * damp,
                earL = -12f * sin(shake * 6f * PI.toFloat() - 0.7f) * damp, earR = -12f * sin(shake * 6f * PI.toFloat() - 0.7f) * damp,
                breathe = breathe, bone = bone,
            )
        }
        DogMood.Sleeping -> DogPose(
            breathe = 1f + 0.03f * wave(3.2f), headDy = 3f + 0.8f * wave(3.2f, 0.1f), headTilt = 5f,
            earL = 7f, earR = -7f, eyesClosed = true, zzz = if (t == 0L) 0.55f else (s / 2.4f) % 1f,
        )
    }
}

/** Scout in a [mood]. Loops run forever (they're idle loops); HeadShake shakes once, then breathes. [headTilt] adds a tilt (curious). */
@Composable
fun Dog(size: Dp = 120.dp, mood: DogMood = DogMood.Wag, modifier: Modifier = Modifier, bone: Boolean = false, headTilt: Float = 0f) {
    val clock = rememberClock()
    val shake = rememberProgress(mood, 620, 120)
    Canvas(modifier.size(size, size * 1.12f)) {
        val t = clock.value
        scale(this.size.width / 100f, this.size.height / 112f, pivot = Offset.Zero) {
            val p = pose(mood, t, bone, if (mood == DogMood.HeadShake) shake.value else 0f)
            drawDog(if (headTilt == 0f) p else p.copy(headTilt = p.headTilt + headTilt))
        }
    }
}

internal fun DrawScope.drawDog(p: DogPose) {
    val ink = Ink.Ink
    val line = Stroke(width = 2.6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    p.zzz?.let { drawZzz(it) }
    translate(0f, p.dy) {
        rotate(p.lean, pivot = Offset(50f, 114f)) {
            scale(1f + (1f - p.squash) * 0.6f, p.squash, pivot = Offset(50f, 106f)) {
                rotate(p.tail, pivot = Offset(76f, 95f)) { drawPath(tail, ink, style = line) }
                scale(1f, p.breathe, pivot = Offset(50f, 106f)) {
                    drawPath(body, Color.White)
                    drawPath(body, ink, style = line)
                    drawOval(ink, Offset(62f - 6f, 84f - 4.5f), Size(12f, 9f))
                }
                val run = p.run
                if (run == null) drawPath(legs, ink, style = line)
                else { runLeg(43f, run.first, line); runLeg(59f, run.second, line) }
                drawPath(collar, Ink.Accent, style = Stroke(3f, cap = StrokeCap.Round))
                drawCircle(Ink.Accent, 3.5f, Offset(51f, 72f))
                translate(11f + p.headDx, 2f + p.headDy) {
                    rotate(p.headTilt, pivot = Offset(40f, 56f)) { drawHead(p, line) }
                }
            }
        }
    }
}

/** Head, ears and face in head coordinates (x 3…77, y 15…63): shared by the whole dog and the peeking face. */
private fun DrawScope.drawHead(p: DogPose, line: Stroke) {
    val ink = Ink.Ink
    drawPath(head, Color.White)
    drawPath(head, ink, style = line)
    rotate(p.earL, pivot = Offset(22f, 24f)) { drawPath(earL, ink) }
    rotate(p.earR, pivot = Offset(58f, 24f)) { drawPath(earR, ink) }
    // Eye patch behind the right eye.
    rotate(-8f, pivot = Offset(49.5f, 33.4f)) { drawOval(ink, Offset(49.5f - 9.6f, 33.4f - 8.6f), Size(19.2f, 17.2f)) }
    if (p.eyesClosed) {
        drawPath(closedEyeL, ink, style = Stroke(2f, cap = StrokeCap.Round))
        drawPath(closedEyeR, Color.White, style = Stroke(2f, cap = StrokeCap.Round))
    } else {
        val dx = 2f * p.look.x
        val dy = 2.3f * p.look.y
        listOf(Offset(31f, 33.6f), Offset(49.5f, 33.3f)).forEach { c ->
            drawOval(Color.White, Offset(c.x - 5.2f, c.y - 6.3f), Size(10.4f, 12.6f))
            drawOval(ink, Offset(c.x - 5.2f, c.y - 6.3f), Size(10.4f, 12.6f), style = Stroke(1.3f))
            val pupil = Offset(c.x + dx, c.y + dy)
            drawCircle(ink, 2.8f, pupil)
            drawCircle(Color.White, 0.85f, pupil + Offset(-0.9f, -1.1f))
        }
    }
    scale(p.nose, pivot = Offset(40f, 42.4f)) { drawOval(ink, Offset(40f - 3.8f, 42.4f - 2.5f), Size(7.6f, 5f)) }
    if (p.bone) {
        drawPath(mouth, ink, style = Stroke(2f, cap = StrokeCap.Round))
        drawPath(bone, Color.White)
        drawPath(bone, ink, style = Stroke(2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    } else {
        drawPath(if (p.tongueOut) tongueOut else tongue, Ink.Accent)
        drawPath(mouth, ink, style = Stroke(2f, cap = StrokeCap.Round))
    }
}

// ---- Scout's face on its own: peeking over edges, watching ----

/**
 * Scout's face (ears, eyes, nose; no body), [width] wide. The eyes glance around and blink while [lookAt] is null;
 * given a target (-1…1 on each axis, e.g. Offset(0f, -1f) = up) they look there. With reduce motion the eyes
 * just look at the target (or ahead) and don't blink.
 */
@Composable
fun ScoutFace(width: Dp, modifier: Modifier = Modifier, lookAt: Offset? = null, glance: Boolean = true) {
    val reduce = LocalReduceMotion.current
    val look = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    var blink by remember { mutableStateOf(false) }
    LaunchedEffect(lookAt, glance, reduce) {
        val target = lookAt
        when {
            target != null -> if (reduce) look.snapTo(target) else look.animateTo(target, tween(140))
            !glance || reduce -> look.snapTo(Offset.Zero)
            else -> while (true) {
                look.animateTo(Offset(Random.nextFloat() * 2f - 1f, Random.nextFloat() * 1.4f - 0.7f), tween(120))
                delay(700L + Random.nextLong(900))
            }
        }
    }
    LaunchedEffect(reduce) {
        if (reduce) return@LaunchedEffect
        while (true) { delay(2400L + Random.nextLong(2200)); blink = true; delay(130); blink = false }
    }
    Canvas(modifier.size(width, width * (52f / 78f))) {
        // Face box: x 1…79, y 13…65 in head coordinates.
        scale(this.size.width / 78f, this.size.height / 52f, pivot = Offset.Zero) {
            translate(-1f, -13f) { drawHead(DogPose(look = look.value, eyesClosed = blink), Stroke(2.6f, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
        }
    }
}

/** A leg from the hip line; `lift` 0 = planted, 1 = kicked back. Drawn over the body like the standing legs. */
private fun DrawScope.runLeg(x: Float, lift: Float, line: Stroke) {
    val paw = Offset(x + 2f - 10f * lift, 112f - 8f * lift)
    drawPath(Path().apply { moveTo(x, 88f); quadraticTo(x + 4f * lift, 103f, paw.x, paw.y) }, Ink.Ink, style = line)
    drawLine(Ink.Ink, paw - Offset(1.5f, 0f), paw + Offset(2.5f, 0f), line.width, StrokeCap.Round)
}

/** Three Zs drifting up and right from the head, fading as they go. */
private fun DrawScope.drawZzz(cycle: Float) {
    for (i in 0 until 3) {
        val k = (cycle + i / 3f) % 1f
        val size = 0.7f + 0.6f * k
        translate(78f + 12f * k, 16f - 16f * k) {
            scale(size, pivot = Offset.Zero) { drawPath(zed, Ink.Ink.copy(alpha = 1f - k), style = Stroke(1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
        }
    }
}

// ---- Running ----

/**
 * Scout running in place, for loading states: leans into the run, legs kick back in turn, ears flap, tongue out
 * (or a bone), over a scrolling dashed ground with speed lines and dust (viewBox 0 0 100 120). One stride = 460 ms.
 */
@Composable
fun RunningDog(width: Dp = 120.dp, modifier: Modifier = Modifier, bone: Boolean = false, ground: Boolean = true) {
    val clock = rememberClock()
    Canvas(modifier.size(width, width * 1.2f)) {
        scale(this.size.width / 100f, this.size.height / 120f, pivot = Offset.Zero) { drawRunning(clock.value, bone, ground) }
    }
}

private fun DrawScope.drawRunning(ms: Long, bone: Boolean, ground: Boolean) {
    val t = (ms % 460) / 460f
    val g = (ms % 320) / 320f
    val ph = 2f * PI.toFloat() * t
    if (ground) {
        drawLine(Ink.LightGrey, Offset(2f, 117f), Offset(98f, 117f), 2f, StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(9f, 7f), g * 16f))
        listOf(70f to 0f, 82f to 0.33f, 94f to 0.66f).forEach { (y, o) ->
            val k = (t + o) % 1f
            drawLine(Ink.LightGrey.copy(alpha = 1f - k), Offset(14f - k * 12f, y), Offset(21f - k * 12f, y), 2f, StrokeCap.Round)
        }
        listOf(0f, 0.5f).forEach { o ->
            val k = (t + o) % 1f
            drawCircle(Ink.LightGrey.copy(alpha = 1f - k), 1.5f + k * 3.5f, Offset(28f - k * 20f, 113f - k * 5f), style = Stroke(1.6f))
        }
    }
    val a = sin(ph)
    val flap = 16f * sin(2f * ph)
    translate(0f, 2f) {
        drawDog(
            DogPose(
                dy = -3f * abs(a), lean = 9f, tail = 12f * sin(2f * ph), run = max(0f, a) to max(0f, -a),
                earL = 18f + flap, earR = -18f - flap, headDy = 1.2f * sin(2f * ph + 1f), tongueOut = !bone, bone = bone,
            ),
        )
    }
}

/**
 * Scout runs in from the left edge of the screen (≤ 600 ms) to where this is placed, then celebrates in [then]
 * mood. Takes [size] × 1.2 so the running and standing drawings line up.
 */
@Composable
fun ScoutRunsIn(size: Dp, modifier: Modifier = Modifier, bone: Boolean = false, then: DogMood = DogMood.Excited, delayMs: Int = 0) {
    val reduce = LocalReduceMotion.current
    val x = remember { Animatable(if (reduce) 0f else 1f) }
    var arrived by remember { mutableStateOf(reduce) }
    var fromLeft by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        if (reduce) return@LaunchedEffect
        if (delayMs > 0) kotlinx.coroutines.delay(delayMs.toLong())
        x.animateTo(0f, tween(600, easing = FastOutSlowInEasing))
        arrived = true
    }
    Box(modifier.size(size, size * 1.2f).onGloballyPositioned { fromLeft = it.positionInRoot().x + it.size.width }) {
        if (arrived) Dog(size, then, Modifier.align(Alignment.BottomCenter), bone = bone)
        else RunningDog(size, Modifier.graphicsLayer { translationX = -x.value * fromLeft }, bone = bone, ground = false)
    }
}
