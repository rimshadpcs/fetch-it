package com.rimapps.fetchit.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rimapps.fetchit.ui.Ink
import com.rimapps.fetchit.ui.body
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A rubber stamp slammed onto the page: scale 1.6 → 1 with a bounce, then a small screen shake and a haptic as it
 * lands. With reduce motion it's simply there (the haptic still plays).
 */
@Composable
fun Stamp(text: String, modifier: Modifier = Modifier, color: Color = Ink.Accent, rotation: Float = -10f, delayMs: Int = 0, haptic: Boolean = true, fontSize: TextUnit = 26.sp) {
    val reduce = LocalReduceMotion.current
    val shake = LocalScreenShake.current
    val haptics = rememberHaptics()
    val scale = remember { Animatable(if (reduce) 1f else 1.6f) }
    val alpha = remember { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (delayMs > 0) delay(delayMs.toLong())
        if (reduce) { if (haptic) haptics.stamp(); return@LaunchedEffect }
        coroutineScope {
            launch { alpha.animateTo(1f, tween(90)) }
            launch { scale.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 900f)) }
            launch { delay(120); if (haptic) haptics.stamp(); shake.shake() }
        }
    }
    Box(
        modifier
            .graphicsLayer { scaleX = scale.value; scaleY = scale.value; rotationZ = rotation; this.alpha = alpha.value * 0.92f }
            .border(3.dp, color, RoundedCornerShape(6.dp)).padding(3.dp)
            .border(1.2.dp, color, RoundedCornerShape(4.dp)).padding(horizontal = 12.dp, vertical = 2.dp),
    ) {
        Text(text, style = body(fontSize, FontWeight.Bold, color).copy(letterSpacing = fontSize * 0.11f))
    }
}
