package com.rimapps.fetchit.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private fun path(d: String) = PathParser().parsePathString(d).toPath()

// Ink-line dog from the design canvas (viewBox 0 0 100 112): floppy black ears, eye patch, red tongue and collar.
private val body = path("M30 62 C22 82 24 104 38 106 H66 C80 104 80 82 72 62 Z")
private val head = path("M20 32 C19 15 61 15 60 32 C62 49 52 61 40 61 C28 61 18 49 20 32 Z")
private val earL = path("M23 22 C10 16 3 30 7 43 C10 49 18 44 21 33 Z")
private val earR = path("M57 22 C70 16 77 30 73 43 C70 49 62 44 59 33 Z")
private val mouth = path("M40 48.5 V52 M34.5 51.5 Q40 57 45.5 51.5")
private val tongue = path("M38 53.5 Q40 60 42 53.5 Z")
private val collar = path("M34 64 Q51 72 68 64")
private val tail = path("M75 96 C88 97 94 87 90 77")
private val legs = path("M43 88 V105 M59 88 V105")

/** The fetcher. `wag` animates the tail (off when the user prefers reduced motion). */
@Composable
fun Mascot(size: Dp = 120.dp, modifier: Modifier = Modifier, wag: Boolean = true) {
    val angle by rememberInfiniteTransition(label = "wag").animateFloat(
        initialValue = -8f, targetValue = 10f, label = "tail",
        animationSpec = infiniteRepeatable(tween(420, easing = FastOutSlowInEasing), RepeatMode.Reverse),
    )
    Canvas(modifier.size(size, size * 1.12f)) {
        scale(this.size.width / 100f, this.size.height / 112f, pivot = Offset.Zero) { drawDog(if (wag) angle else 0f) }
    }
}

private fun DrawScope.drawDog(tailAngle: Float) {
    val ink = Ink.Ink
    val line = Stroke(width = 2.6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    rotate(tailAngle, pivot = Offset(76f, 95f)) { drawPath(tail, ink, style = line) }
    drawPath(body, Color.White)
    drawPath(body, ink, style = line)
    drawOval(ink, Offset(62f - 6f, 84f - 4.5f), Size(12f, 9f))
    drawPath(legs, ink, style = line)
    drawPath(collar, Ink.Accent, style = Stroke(3f, cap = StrokeCap.Round))
    drawCircle(Ink.Accent, 3.5f, Offset(51f, 72f))
    translate(11f, 2f) {
        drawPath(head, Color.White)
        drawPath(head, ink, style = line)
        drawPath(earL, ink)
        drawPath(earR, ink)
        drawOval(ink, Offset(49f - 7f, 34f - 6f), Size(14f, 12f))
        drawCircle(Color.White, 1.4f, Offset(50f, 34f))
        drawCircle(ink, 3f, Offset(32f, 35f))
        drawOval(ink, Offset(40f - 4f, 45f - 3f), Size(8f, 6f))
        drawPath(tongue, Ink.Accent)
        drawPath(mouth, ink, style = Stroke(2.2f, cap = StrokeCap.Round))
    }
}
