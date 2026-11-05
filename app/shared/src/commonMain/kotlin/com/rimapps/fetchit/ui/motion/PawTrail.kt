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
