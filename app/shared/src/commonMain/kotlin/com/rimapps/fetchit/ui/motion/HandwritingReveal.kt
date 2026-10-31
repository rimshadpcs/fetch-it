package com.rimapps.fetchit.ui.motion

import androidx.compose.animation.core.LinearEasing
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow

/** Gochi Hand text written on left to right (a moving clip), ~40 ms a letter, capped at 600 ms. */
@Composable
fun HandwritingReveal(text: String, style: TextStyle, modifier: Modifier = Modifier, delayMs: Int = 0, maxLines: Int = 1) {
    val duration = (180 + text.length * 40).coerceAtMost(600)
    val p = rememberProgress(text, duration, delayMs, LinearEasing)
    Text(
        text, style = style, maxLines = maxLines, overflow = TextOverflow.Clip,
        modifier = modifier.drawWithContent {
            val v = p.value
            if (v >= 1f) drawContent() else clipRect(right = size.width * v) { this@drawWithContent.drawContent() }
        },
    )
}
