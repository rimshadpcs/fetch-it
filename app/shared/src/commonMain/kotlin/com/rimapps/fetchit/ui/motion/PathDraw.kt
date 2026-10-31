package com.rimapps.fetchit.ui.motion

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rimapps.fetchit.ui.Ink
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Paths measured once, so a progress value can be drawn as "the first N% of the line" (pen order = list order). */
class MeasuredPaths(val paths: List<Path>) {
    private val lengths = paths.map { p -> PathMeasure().apply { setPath(p, false) }.length }
    val total = lengths.sum()

    fun draw(scope: DrawScope, progress: Float, color: Color, stroke: Stroke) {
        if (progress >= 1f) { paths.forEach { scope.drawPath(it, color, style = stroke) }; return }
        var left = total * progress.coerceIn(0f, 1f)
        paths.forEachIndexed { i, p ->
            if (left <= 0f) return
            val len = lengths[i]
            if (left >= len) scope.drawPath(p, color, style = stroke)
            else {
                val seg = Path()
                PathMeasure().apply { setPath(p, false) }.getSegment(0f, left, seg, true)
                scope.drawPath(seg, color, style = stroke)
            }
            left -= len
        }
    }
}

/** Ink line style: round caps and joins, optional dashes. */
fun inkStroke(width: Float, dashed: Boolean = false, dash: Float = 10f) = Stroke(
    width = width, cap = StrokeCap.Round, join = StrokeJoin.Round,
    pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(dash, dash * 0.8f)) else null,
)

/**
 * Draws the paths from [build] (sized to this element) as if by pen, over [durationMs] after [delayMs].
 * Use as an overlay: it fills its parent's size via [modifier].
 */
@Composable
fun PathDraw(
    modifier: Modifier,
    color: Color = Ink.Ink,
    strokeWidth: Dp = 2.5.dp,
    durationMs: Int = 450,
    delayMs: Int = 0,
    dashed: Boolean = false,
    key: Any? = Unit,
    easing: Easing = FastOutSlowInEasing,
    build: (Size) -> List<Path>,
) {
    val progress = rememberProgress(key, durationMs, delayMs, easing)
    Box(modifier.drawWithCache {
        val measured = MeasuredPaths(build(size))
        val stroke = inkStroke(strokeWidth.toPx(), dashed, 6.dp.toPx())
        onDrawBehind { measured.draw(this, progress.value, color, stroke) }
    })
}

// ---- Doodles: hand-drawn shapes as paths ----

/** A pen loop around [r]: an ellipse with a wobbly radius that overshoots its start, like circling something. */
fun wobblyLoop(r: Rect, seed: Int = 1, overshoot: Float = 0.12f): Path {
    val c = r.center
    val steps = 72
    val turns = 1f + overshoot
    return Path().apply {
        for (i in 0..steps) {
            val t = i / steps.toFloat()
            val a = (-PI / 2 - 0.35 + t * turns * 2 * PI).toFloat()
            val wobble = 1f + 0.035f * sin(a * 3f + seed) + 0.02f * sin(a * 5f + seed * 2) + 0.03f * t // spirals out a touch
            val p = Offset(c.x + cos(a) * r.width / 2 * wobble, c.y + sin(a) * r.height / 2 * wobble)
            if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
        }
    }
}

/** A curved arrow from [from] to [to], bending by [bend] (fraction of the length, sign = side), plus its head. */
fun doodleArrow(from: Offset, to: Offset, bend: Float = 0.25f, head: Float = 9f): List<Path> {
    val d = to - from
    val len = sqrt(d.x * d.x + d.y * d.y)
    val normal = Offset(-d.y / len, d.x / len)
    val ctrl = from + d / 2f + normal * (len * bend)
    val shaft = Path().apply { moveTo(from.x, from.y); quadraticTo(ctrl.x, ctrl.y, to.x, to.y) }
    val angle = atan2(to.y - ctrl.y, to.x - ctrl.x)
    fun wing(off: Float) = Offset(to.x - head * cos(angle + off), to.y - head * sin(angle + off))
    val w1 = wing(0.5f); val w2 = wing(-0.5f)
    val tip = Path().apply { moveTo(w1.x, w1.y); lineTo(to.x, to.y); lineTo(w2.x, w2.y) }
    return listOf(shaft, tip)
}

/** A quick ✗ over [r]: two slightly curved strokes. */
fun doodleCross(r: Rect): List<Path> = listOf(
    Path().apply { moveTo(r.left, r.top); quadraticTo(r.center.x + r.width * 0.06f, r.center.y - r.height * 0.06f, r.right, r.bottom) },
    Path().apply { moveTo(r.right, r.top + r.height * 0.04f); quadraticTo(r.center.x - r.width * 0.04f, r.center.y - r.height * 0.08f, r.left + r.width * 0.03f, r.bottom) },
)

/** A rounded box around a detected item. */
fun outlineBox(r: Rect, radius: Float): Path = Path().apply { addRoundRect(RoundRect(r, CornerRadius(radius))) }

/** A little delivery truck (≈ 28 × 18 units, origin top-left), ink lines. */
fun DrawScope.drawTruck(origin: Offset, unit: Float, color: Color = Ink.Ink) {
    val s = inkStroke(1.8f * unit)
    fun o(x: Float, y: Float) = Offset(origin.x + x * unit, origin.y + y * unit)
    val body = Path().apply {
        moveTo(o(1f, 2f).x, o(1f, 2f).y); lineTo(o(17f, 2f).x, o(17f, 2f).y); lineTo(o(17f, 13f).x, o(17f, 13f).y); lineTo(o(1f, 13f).x, o(1f, 13f).y); close()
    }
    val cab = Path().apply {
        moveTo(o(17f, 6f).x, o(17f, 6f).y); lineTo(o(23f, 6f).x, o(23f, 6f).y); lineTo(o(27f, 10f).x, o(27f, 10f).y)
        lineTo(o(27f, 13f).x, o(27f, 13f).y); lineTo(o(17f, 13f).x, o(17f, 13f).y)
    }
    drawPath(body, Color.White); drawPath(body, color, style = s)
    drawPath(Path().apply { addPath(cab); close() }, Color.White); drawPath(cab, color, style = s)
    drawLine(Ink.Accent, o(4f, 7.5f), o(13f, 7.5f), 1.8f * unit, StrokeCap.Round)
    listOf(o(6f, 14.5f), o(22f, 14.5f)).forEach { drawCircle(Color.White, 2.6f * unit, it); drawCircle(color, 2.6f * unit, it, style = s) }
}

/** Fingerprint doodle: concentric arcs, centered in [size]. */
fun DrawScope.drawFingerprint(color: Color, width: Float) {
    val c = center
    val s = inkStroke(width)
    val r0 = size.minDimension / 2
    listOf(0.95f to 200f, 0.72f to 230f, 0.5f to 250f, 0.28f to 260f).forEachIndexed { i, (k, sweep) ->
        val r = r0 * k
        drawArc(color, startAngle = 180f + (300f - sweep) / 2 - 30f + i * 8f, sweepAngle = sweep, useCenter = false,
            topLeft = Offset(c.x - r, c.y - r * 1.1f), size = Size(r * 2, r * 2.2f), style = s)
    }
    drawLine(color, Offset(c.x, c.y - r0 * 0.05f), Offset(c.x, c.y + r0 * 0.35f), width, StrokeCap.Round)
}
