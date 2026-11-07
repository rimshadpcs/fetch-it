package com.rimapps.fetchit.ui.motion

import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

// Entrances. Each is a one-shot ≤ 400 ms (after its delay); with reduce motion they're plain fades.

/** Fades in while rising [distance] after [delayMs] (staggered rows, a button rising into place). */
@Composable
fun Modifier.riseIn(delayMs: Int = 0, distance: Dp = 18.dp, durationMs: Int = 320): Modifier {
    val reduce = LocalReduceMotion.current
    val p = rememberProgress(Unit, if (reduce) 200 else durationMs, delayMs, FastOutSlowInEasing, fadeOnly = true)
    return graphicsLayer {
        alpha = p.value
        if (!reduce) translationY = (1f - p.value) * distance.toPx()
    }
}

/** Pops from small to full size with a little overshoot (chips appearing in turn). */
@Composable
fun Modifier.popIn(delayMs: Int = 0): Modifier {
    val reduce = LocalReduceMotion.current
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (delayMs > 0) delay(delayMs.toLong())
        a.animateTo(1f, if (reduce) tween(200) else uiSpring(stiffness = 500f, damping = 0.6f))
    }
    return graphicsLayer {
        alpha = a.value.coerceIn(0f, 1f)
        if (!reduce) { val s = 0.6f + 0.4f * a.value; scaleX = s; scaleY = s }
    }
}

/** A paper note sliding in from the right and settling at [tilt] (the delivery address card). */
@Composable
fun Modifier.noteSlideIn(delayMs: Int = 0, tilt: Float = -0.6f): Modifier {
    val reduce = LocalReduceMotion.current
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (delayMs > 0) delay(delayMs.toLong())
        a.animateTo(1f, if (reduce) tween(200) else uiSpring(stiffness = 380f, damping = 0.7f))
    }
    return graphicsLayer {
        val v = a.value
        alpha = v.coerceIn(0f, 1f)
        if (!reduce) { translationX = (1f - v) * 80.dp.toPx(); rotationZ = tilt + (1f - v) * 7f }
    }
}

/** One small wiggle after [idleMs] with nothing happening; [key] changing (any interaction) restarts the wait. */
@Composable
fun Modifier.wiggleAfterIdle(key: Any?, idleMs: Long = 5_000): Modifier {
    val reduce = LocalReduceMotion.current
    val r = remember { Animatable(0f) }
    LaunchedEffect(key, reduce) {
        r.snapTo(0f)
        if (reduce) return@LaunchedEffect
        delay(idleMs)
        r.animateTo(0f, keyframes { durationMillis = 420; 5f at 70; -5f at 170; 3f at 260; -1.5f at 340 })
    }
    return graphicsLayer { rotationZ = r.value }
}

/** A strip of masking tape that lands (scales down onto the page) after [delayMs]. */
@Composable
fun Tape(modifier: Modifier = Modifier, width: Dp = 84.dp, rotation: Float = -6f, delayMs: Int = 0) {
    val reduce = LocalReduceMotion.current
    val p = rememberProgress(Unit, if (reduce) 200 else 240, delayMs, fadeOnly = true)
    Box(
        modifier.size(width, 22.dp).graphicsLayer {
            val v = p.value
            alpha = v; rotationZ = rotation
            if (!reduce) { val s = 1.35f - 0.35f * v; scaleX = s; scaleY = s }
        }.background(Color(0xFFEDE6D3).copy(alpha = 0.88f)),
    )
}

/**
 * Page-turn rotation for the screen switcher: the incoming page swings in from a slight tilt around its bottom
 * edge while it slides (see App.screenTransition). [visible] is 0 entering … 1 settled … 2 gone.
 */
fun Modifier.pageTurn(visible: Float): Modifier = graphicsLayer {
    transformOrigin = TransformOrigin(0.5f, 1f)
    rotationZ = when {
        visible < 1f -> (1f - visible) * 3f
        else -> (visible - 1f) * -3f
    }
}

/**
 * Builds in from blurry to sharp (blur 14 → 0 dp, fading up, a slight rise), staggered by [index] (60 ms, capped):
 * Results and Activity cards arriving instead of a spinner. Plain fade with reduce motion (and below Android 12,
 * where blur isn't drawn, it's a fade-and-rise).
 */
@Composable
fun Modifier.blurIn(index: Int = 0, durationMs: Int = 360): Modifier {
    val reduce = LocalReduceMotion.current
    val p = rememberProgress(Unit, if (reduce) 200 else durationMs, 60 * index.coerceAtMost(8), FastOutSlowInEasing, fadeOnly = true)
    val v = p.value
    return this
        .then(if (!reduce && v < 1f) Modifier.blur(((1f - v) * 14f).dp, BlurredEdgeTreatment.Unbounded) else Modifier)
        .graphicsLayer {
            alpha = 0.15f + 0.85f * v
            if (!reduce) translationY = (1f - v) * 10.dp.toPx()
        }
}
