package com.rimapps.fetchit.ui.motion

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.rimapps.fetchit.ui.Ink

/** Paw prints walking left to right, one after another, each fading as the next lands (a 2.4 s loop). */
@Composable
fun PawTrail(modifier: Modifier, color: Color = Ink.Ink, prints: Int = 6) {
    val clock = rememberClock()
    val reduce = LocalReduceMotion.current
    Canvas(modifier) {
        val t = (clock.value % 2400) / 2400f
        val step = size.width / prints
        for (i in 0 until prints) {
            val appear = i / (prints + 2f)
            val k = t - appear
            val alpha = if (reduce) 0.22f else if (k < 0f) 0f else (1f - k / (1f - appear)).coerceIn(0f, 1f) * 0.6f
            val y = size.height * if (i % 2 == 0) 0.3f else 0.7f
            drawPaw(Offset(step * (i + 0.5f), y), size.height * 0.27f, color.copy(alpha = alpha))
        }
    }
}

/** A paw print pointing right: a pad and four toes. */
private fun DrawScope.drawPaw(c: Offset, r: Float, color: Color) {
    if (color.alpha == 0f) return
    drawOval(color, Offset(c.x - r * 0.55f, c.y - r * 0.5f), Size(r * 1.1f, r * 1f))
    listOf(Offset(0.95f, -0.3f), Offset(0.95f, 0.3f), Offset(0.62f, -0.78f), Offset(0.62f, 0.78f)).forEach { (dx, dy) ->
        drawCircle(color, r * 0.27f, Offset(c.x + dx * r, c.y + dy * r))
    }
}

/**
 * Loading inside a button: four paw prints step in left to right (Scout walking), each popping in 0.6 → 1, holding,
 * then fading; a 1.2 s loop. With reduce motion the prints sit still and gently fade in and out together.
 */
@Composable
fun PawLoader(color: Color, modifier: Modifier = Modifier) {
    val clock = rememberClock()
    val reduce = LocalReduceMotion.current
    Canvas(modifier) {
        val prints = 4
        val step = size.width / prints
        val r = size.height * 0.26f
        for (i in 0 until prints) {
            val (alpha, scale) = if (reduce) {
                (0.55f + 0.45f * kotlin.math.sin(clock.value / 1000f * 3f)) to 1f
            } else {
                // Phase of this print in the loop: 0 → appears at its turn (i × 0.2), visible until 0.85, gone by 1.
                val p = ((clock.value % 1200) / 1200f - i * 0.17f)
                when {
                    p < 0f -> 0f to 0.6f
                    p < 0.12f -> (p / 0.12f) to (0.6f + 0.4f * p / 0.12f)
                    p < 0.55f -> 1f to 1f
                    p < 0.75f -> (1f - (p - 0.55f) / 0.2f) to 1f
                    else -> 0f to 1f
                }
            }
            val y = size.height * if (i % 2 == 0) 0.62f else 0.38f
            drawPaw(Offset(step * (i + 0.5f), y), r * scale, color.copy(alpha = alpha.coerceIn(0f, 1f)))
        }
    }
}
