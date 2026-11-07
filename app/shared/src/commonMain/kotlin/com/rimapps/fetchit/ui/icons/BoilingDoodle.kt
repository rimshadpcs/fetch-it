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

// The set's animated variant, recreated in Compose for one icon (the Fetch button's camera): each stroke draws on in
// order (the SVG's timings and cubic-bezier(.65,.05,.36,1)), then the line keeps "boiling": the SVG's own feTurbulence
// (ported, SvgTurbulence) displacing the strokes like feDisplacementMap, seed stepping 1…6 every 0.86 s, forever.
// Static with reduce motion.

private val DRAW_EASE = CubicBezierEasing(0.65f, 0.05f, 0.36f, 1f)
private const val BOIL_STEP_MS = 143L // 0.86 s / 6 seeds
private const val SCALE = 4.0 // feDisplacementMap scale

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

/** The six noises the SVG cycles through (`<animate attributeName="seed" values="1;2;3;4;5;6">`), built once. */
private val TURBULENCE = (1..6).associateWith { SvgTurbulence(it.toLong()) }

/**
 * Where a stroke point ends up under `feDisplacementMap scale="4"` over `feTurbulence type="fractalNoise"
 * baseFrequency="0.055" numOctaves="2"`: the filter shows at pixel q the source from q + 4(noise(q) − ½), so the
 * line point p appears at the q solving q + d(q) = p. Solved by a few fixed-point steps (the noise is smooth);
 * against Chrome's render this lands within ~0.1 grid unit of the drawn stroke.
 */
private fun boiled(p: Offset, seed: Int): Offset {
    val t = TURBULENCE.getValue(seed)
    var x = p.x.toDouble(); var y = p.y.toDouble()
    repeat(6) {
        val r = t.fractalNoise(0, x, y, 0.055, 2)
        val g = t.fractalNoise(1, x, y, 0.055, 2)
        x = p.x - SCALE * (r - 0.5); y = p.y - SCALE * (g - 0.5)
    }
    return Offset(x.toFloat(), y.toFloat())
}

/** The Fetch button's camera, drawing itself on and then boiling forever. */
@Composable
fun BoilingCamera(size: Dp, color: Color, strokeWidth: Float = 3.4f, modifier: Modifier = Modifier) {
    val reduce = LocalReduceMotion.current
    val clock = rememberClock(running = !reduce)
    val paths = remember { Doodle.Camera.paths.map(::sample) }
    // Each seed's displaced points, worked out once (the noise only changes when the seed steps).
    val boiled = remember(paths) { (1..6).associateWith { seed -> paths.map { sp -> sp.points.map { boiled(it, seed) } } } }
    Canvas(modifier.size(size)) {
        val t = if (reduce) Long.MAX_VALUE / 4 else clock.value
        val seed = if (reduce) 0 else (t / BOIL_STEP_MS % 6).toInt() + 1
        val stroke = Stroke(strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        scale(this.size.width / 48f, this.size.height / 48f, pivot = Offset.Zero) {
            paths.forEachIndexed { i, sp ->
                val (start, dur) = CAMERA_TIMING.getOrElse(i) { 0 to 1 }
                val f = DRAW_EASE.transform(((t - start).toFloat() / dur).coerceIn(0f, 1f))
                if (f <= 0f) return@forEachIndexed
                val pts = if (reduce) sp.points else boiled.getValue(seed)[i]
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
