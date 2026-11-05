package com.rimapps.fetchit.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.random.Random

/** A tilt between ±[max]° (at least a third of it), the same every time for the same [seed]. */
fun pinTilt(seed: Int, max: Float = 1.8f): Float {
    val r = Random(seed * 7919 + 13)
    val t = max * (0.35f + 0.65f * r.nextFloat())
    return if (r.nextBoolean()) t else -t
}

/**
 * Pinned to the board: drops from above, overshoots, settles at [tilt]. Staggered by [index] (70 ms, capped).
 * Springy but settled in ~450 ms.
 */
@Composable
fun Modifier.pinIn(index: Int, tilt: Float = pinTilt(index)): Modifier {
    val reduce = LocalReduceMotion.current
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(70L * index.coerceAtMost(6))
        a.animateTo(1f, if (reduce) androidx.compose.animation.core.tween(200) else spring(dampingRatio = 0.5f, stiffness = 520f))
    }
    return graphicsLayer {
        val v = a.value
        if (reduce) { alpha = v; rotationZ = tilt; return@graphicsLayer }
        alpha = (v * 4f).coerceIn(0f, 1f)
        translationY = (1f - v) * -34.dp.toPx()
        val s = 1f + (1f - v) * 0.07f
        scaleX = s; scaleY = s
        rotationZ = tilt * v
    }
}
