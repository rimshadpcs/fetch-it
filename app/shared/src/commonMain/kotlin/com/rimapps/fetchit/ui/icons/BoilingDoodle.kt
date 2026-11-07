package com.rimapps.fetchit.ui.icons

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rimapps.fetchit.ui.motion.LocalReduceMotion
import com.rimapps.fetchit.ui.motion.rememberClock
import kotlin.math.cos
import kotlin.math.sin

// The set's animated variant, recreated in Compose for one icon (the Fetch button's camera): each stroke draws on in
// order (the SVG's timings and cubic-bezier(.65,.05,.36,1)), then the line keeps "boiling": an feTurbulence +
// feDisplacementMap wobble whose noise seed steps 6 times per 0.86 s, forever. Static with reduce motion.

private val DRAW_EASE = CubicBezierEasing(0.65f, 0.05f, 0.36f, 1f)
private const val BOIL_STEP_MS = 143L // 0.86 s / 6 seeds
private const val BOIL = 2f // ± grid units (displacement scale 4 → ±2)

/** Start delay and duration (ms) of each stroke's draw-on, in path order. */
private val CAMERA_TIMING = listOf(0 to 700, 490 to 310, 700 to 150)

/** One path sampled into points (or a single point for the camera's flash dot). */
private class Sampled(val points: List<Offset>, val isDot: Boolean)

private fun sample(d: String): Sampled {
    val path = PathParser().parsePathString(d).toPath()
    val m = PathMeasure().apply { setPath(path, false) }
    if (m.length < 0.5f) return Sampled(listOf(m.getPosition(0f)), isDot = true)
    val n = (m.length / 0.8f).toInt().coerceIn(12, 160)
    return Sampled((0..n).map { m.getPosition(m.length * it / n) }, isDot = false)
}

/** Smooth noise displacement for a point, different for each seed (stands in for the SVG's fractal noise). */
private fun boil(p: Offset, seed: Int): Offset {
    val s = seed * 1.913f
    val dx = sin(p.x * 0.31f + s) * cos(p.y * 0.23f - s * 0.7f) + 0.5f * sin(p.y * 0.57f + s * 1.3f)
    val dy = cos(p.y * 0.29f + s * 1.1f) * sin(p.x * 0.27f + s * 0.4f) + 0.5f * cos(p.x * 0.61f - s)
    return Offset(dx, dy) * (BOIL / 1.5f)
}

/** The Fetch button's camera, drawing itself on and then boiling forever. */
@Composable
fun BoilingCamera(size: Dp, color: Color, strokeWidth: Float = 4.2f, modifier: Modifier = Modifier) {
    val reduce = LocalReduceMotion.current
    val clock = rememberClock(running = !reduce)
    val paths = remember { Doodle.Camera.paths.map(::sample) }
    Canvas(modifier.size(size)) {
        val t = if (reduce) Long.MAX_VALUE / 4 else clock.value
        val seed = if (reduce) 0 else (t / BOIL_STEP_MS % 6).toInt() + 1
        val stroke = Stroke(strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        scale(this.size.width / 48f, this.size.height / 48f, pivot = Offset.Zero) {
            paths.forEachIndexed { i, sp ->
                val (start, dur) = CAMERA_TIMING.getOrElse(i) { 0 to 1 }
                val f = DRAW_EASE.transform(((t - start).toFloat() / dur).coerceIn(0f, 1f))
                if (f <= 0f) return@forEachIndexed
                val pts = sp.points.map { it + if (reduce) Offset.Zero else boil(it, seed) }
                if (sp.isDot) { drawCircle(color, strokeWidth / 2, pts[0]); return@forEachIndexed }
                val upTo = (f * (pts.size - 1)).toInt().coerceAtLeast(1)
                val line = Path().apply {
                    moveTo(pts[0].x, pts[0].y)
                    for (k in 1..upTo) lineTo(pts[k].x, pts[k].y)
                }
                drawPath(line, color, style = stroke)
            }
        }
    }
}
