package com.rimapps.fetchit.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rimapps.fetchit.ui.Ink
import kotlinx.coroutines.delay
import kotlin.math.sin
import kotlin.random.Random

// Big, bold Scout: the mascot simplified to a domed head, big eyes and a smile, drawn on a 400 × 260 box so it can
// fill a screen edge to edge (Welcome) or show just its dome and eyes peeking over something (the peeks).
// Same identity: white head, ink outline, black floppy ears, the eye patch, black nose, red tongue.

private fun path(d: String) = PathParser().parsePathString(d).toPath()

private const val W = 400f
private const val H = 260f
private val dome = path("M0 120 C30 40 110 6 200 6 C290 6 370 40 400 120 L400 260 L0 260 Z")
private val domeEdge = path("M0 120 C30 40 110 6 200 6 C290 6 370 40 400 120")
private val earL = path("M108 40 C48 18 -14 64 -6 150 C0 206 58 200 82 140 C92 112 100 80 108 40 Z")
private val earR = path("M292 40 C352 18 414 64 406 150 C400 206 342 200 318 140 C308 112 300 80 292 40 Z")
private val mouth = path("M176 168 Q200 192 224 168")
private val nosePhiltrum = path("M200 156 V176")
private val tongue = path("M191 180 Q200 206 209 180 Z")

/** Eyes: [look] -1…1 per axis moves the pupils; [blink] 0 open … 1 shut; [earSway] degrees. */
private fun DrawScope.drawHeroHead(look: Offset, blink: Float, earSway: Float) {
    val ink = Ink.Ink
    val outline = Stroke(5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    scale(size.width / W, size.width / W, pivot = Offset.Zero) {
        drawPath(dome, Color.White)
        drawPath(domeEdge, ink, style = outline)
        // The eye patch sits behind the right eye.
        rotate(-8f, Offset(254f, 96f)) { drawOval(ink, Offset(254f - 52f, 96f - 46f), Size(104f, 92f)) }
        listOf(150f to 98f, 254f to 96f).forEach { (cx, cy) ->
            val open = 1f - blink.coerceIn(0f, 1f) * 0.9f
            val rx = 28f; val ry = 34f * open
            drawOval(Color.White, Offset(cx - rx, cy - ry), Size(rx * 2, ry * 2))
            drawOval(ink, Offset(cx - rx, cy - ry), Size(rx * 2, ry * 2), style = Stroke(4.5f))
            if (open > 0.4f) {
                val p = Offset(cx + look.x * 11f, cy + look.y * 13f * open)
                drawCircle(ink, 15f * open.coerceAtMost(1f), p)
                drawCircle(Color.White, 4.5f, p + Offset(-5f, -6f))
            }
        }
        drawOval(ink, Offset(200f - 20f, 146f - 13f), Size(40f, 26f))
        drawPath(nosePhiltrum, ink, style = outline)
        drawPath(tongue, Ink.Accent)
        drawPath(mouth, ink, style = outline)
        rotate(earSway, Offset(108f, 40f)) { drawPath(earL, ink) }
        rotate(-earSway, Offset(292f, 40f)) { drawPath(earR, ink) }
    }
}

/** Glancing pupils + blinking eyelids + a slow ear sway, shared by the hero and the peeks. */
@Composable
private fun rememberHeroEyes(lookAt: Offset?, glance: Boolean): Triple<Offset, Float, () -> Float> {
    val reduce = LocalReduceMotion.current
    val look = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val blink = remember { Animatable(0f) }
    val clock = rememberClock()
    LaunchedEffect(lookAt, glance, reduce) {
        val target = lookAt
        when {
            target != null -> if (reduce) look.snapTo(target) else look.animateTo(target, tween(160))
            !glance || reduce -> look.snapTo(Offset.Zero)
            else -> while (true) {
                look.animateTo(Offset(Random.nextFloat() * 2f - 1f, Random.nextFloat() * 1.4f - 0.7f), tween(140))
                delay(650L + Random.nextLong(900))
            }
        }
    }
    LaunchedEffect(reduce) {
        if (reduce) return@LaunchedEffect
        while (true) {
            delay(2300L + Random.nextLong(2200))
            blink.animateTo(1f, tween(70)); blink.animateTo(0f, tween(110))
        }
    }
    return Triple(look.value, blink.value) { if (reduce) 0f else 2.5f * sin(clock.value / 1000f * 1.6f) }
}

/**
 * Welcome: Scout's head filling the bottom of the screen edge to edge, rising up from below after [delayMs], eyes
 * glancing around (or at [lookAt]) and blinking, ears swaying a little. [content] sits on his head (the button).
 */
@Composable
fun ScoutHero(modifier: Modifier = Modifier, lookAt: Offset? = null, delayMs: Int = 300, content: @Composable BoxScope.() -> Unit = {}) {
    val reduce = LocalReduceMotion.current
    val rise = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(delayMs.toLong()); rise.animateTo(1f, if (reduce) tween(200) else uiSpring(stiffness = 260f, damping = 0.7f)) }
    val (look, blink, sway) = rememberHeroEyes(lookAt, glance = true)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val h = maxWidth * (H / W)
        Box(
            Modifier.fillMaxWidth().height(h).graphicsLayer {
                if (reduce) alpha = rise.value else translationY = (1f - rise.value) * h.toPx()
            },
        ) {
            Canvas(Modifier.fillMaxSize()) { drawHeroHead(look, blink, sway()) }
            content()
        }
    }
}

/**
 * The same head, small, showing only the top [showing] of it (dome, ears, eyes) over an edge, rising into place
 * after [delayMs]. Eyes glance or look at [lookAt].
 */
@Composable
fun ScoutPeek(width: Dp, modifier: Modifier = Modifier, showing: Float = 0.62f, lookAt: Offset? = null, glance: Boolean = true, delayMs: Int = 0) {
    val reduce = LocalReduceMotion.current
    val rise = rememberProgress(Unit, if (reduce) 200 else 350, delayMs, fadeOnly = true)
    val (look, blink, sway) = rememberHeroEyes(lookAt, glance)
    val full = width * (H / W)
    Box(modifier.size(width, full * showing).clipToBounds()) {
        Canvas(
            Modifier.size(width, full).graphicsLayer {
                if (reduce) alpha = rise.value else translationY = (1f - rise.value) * full.toPx() * showing
            },
        ) { drawHeroHead(look, blink, sway()) }
    }
}
