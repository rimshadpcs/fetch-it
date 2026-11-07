package com.rimapps.fetchit.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rimapps.fetchit.ui.Ink
import com.rimapps.fetchit.ui.body
import com.rimapps.fetchit.ui.motion.PawLoader
import com.rimapps.fetchit.ui.motion.LocalReduceMotion
import com.rimapps.fetchit.ui.motion.PathDraw
import com.rimapps.fetchit.ui.motion.inkStroke
import com.rimapps.fetchit.ui.motion.rememberClock
import com.rimapps.fetchit.ui.motion.rememberHaptics
import com.rimapps.fetchit.ui.motion.uiSpring

/**
 * Black pill (58 dp) or gray pill. Pressed: sinks (0.96 black / 0.95 gray) with a tighter shadow and a light
 * haptic, springs back. [loading]: the label swaps to a dashed spinner. [done]: a checkmark draws in 300 ms with a
 * success haptic (the caller moves on ~350 ms later). Disabled → enabled fades 0.4 → 1 with a small arming bounce.
 */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    done: Boolean = false,
    dark: Boolean = true,
    height: Dp = 58.dp,
    leading: (@Composable () -> Unit)? = null,
) {
    // Disabled dark pills sit dim gray and light up to black once enabled (the choice is valid).
    val bg by animateColorAsState(if (!dark) Ink.Pill else if (enabled) Ink.Ink else Color(0xFFC9C9C5), tween(300), label = "pillBg")
    val fg = if (dark) Ink.White else Ink.Muted
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val haptics = rememberHaptics()
    val reduce = LocalReduceMotion.current
    val alpha by animateFloatAsState(if (enabled || dark) 1f else 0.4f, tween(250), label = "enabled")
    val shadow by animateDpAsState(if (!dark) 0.dp else if (pressed) 2.dp else 6.dp, tween(80), label = "shadow")
    // Arming bounce when it becomes enabled (not on first show).
    val arm = remember { Animatable(1f) }
    var wasEnabled by remember { mutableStateOf(enabled) }
    LaunchedEffect(enabled) {
        if (enabled && !wasEnabled && !reduce) { arm.snapTo(0.94f); arm.animateTo(1f, uiSpring(stiffness = 500f, damping = 0.6f)) }
        wasEnabled = enabled
    }
    LaunchedEffect(done) { if (done) haptics.success() }
    Box(
        modifier.fillMaxWidth().height(height)
            .graphicsLayer { scaleX = arm.value; scaleY = arm.value; this.alpha = alpha }
            .pressScale(source, if (dark) Press.PILL else Press.GRAY)
            .shadow(shadow, RoundedCornerShape(999.dp)).clip(RoundedCornerShape(999.dp)).background(bg)
            .clickable(source, indication = null, enabled = enabled && !loading && !done, role = Role.Button) {
                if (dark) haptics.tap()
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        val state = when { done -> 2; loading -> 1; else -> 0 }
        AnimatedContent(state, transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(100)) }, label = "pill") { s ->
            when (s) {
                1 -> PawLoader(fg, Modifier.size(64.dp, 22.dp))
                2 -> CheckMark(fg, Modifier.size(26.dp))
                else -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    leading?.invoke()
                    Text(text, style = body(if (dark) 17.sp else 16.sp, if (dark) FontWeight.SemiBold else FontWeight.Medium, fg))
                }
            }
        }
    }
}

/** A circle of ink dashes turning (one turn per 900 ms); with reduce motion it gently fades in and out instead. */
@Composable
fun DashedSpinner(color: Color, modifier: Modifier = Modifier) {
    val clock = rememberClock(running = true)
    val reduce = LocalReduceMotion.current
    val pulse by androidx.compose.animation.core.rememberInfiniteTransition(label = "spin").animateFloat(
        0.35f, 1f, androidx.compose.animation.core.infiniteRepeatable(tween(700), androidx.compose.animation.core.RepeatMode.Reverse), label = "fade",
    )
    Canvas(modifier) {
        val t = clock.value
        val stroke = inkStroke(2.4.dp.toPx(), dashed = true, dash = 4.dp.toPx())
        if (reduce) drawCircle(color.copy(alpha = 0.7f * pulse), size.minDimension / 2 - stroke.width, style = stroke)
        else rotate((t % 900) / 900f * 360f) { drawCircle(color, size.minDimension / 2 - stroke.width, style = stroke) }
    }
}

/** A hand-drawn ✓ drawn in 300 ms. */
@Composable
fun CheckMark(color: Color, modifier: Modifier = Modifier, durationMs: Int = 300, delayMs: Int = 0, strokeWidth: Dp = 3.dp) {
    PathDraw(modifier, color = color, strokeWidth = strokeWidth, durationMs = durationMs, delayMs = delayMs, easing = LinearEasing) { s ->
        listOf(Path().apply {
            moveTo(s.width * 0.12f, s.height * 0.55f)
            quadraticTo(s.width * 0.3f, s.height * 0.7f, s.width * 0.4f, s.height * 0.86f)
            quadraticTo(s.width * 0.6f, s.height * 0.4f, s.width * 0.92f, s.height * 0.14f)
        })
    }
}

/** Round icon button feel: press 0.90, spring back, a one-off ±6° turn. Put it on any circular clickable. */
@Composable
fun Modifier.roundButton(onClick: () -> Unit, enabled: Boolean = true, role: Role = Role.Button): Modifier {
    val source = remember { MutableInteractionSource() }
    return this.pressScale(source, Press.ROUND, tilt = true).clip(CircleShape)
        .clickable(source, indication = null, enabled = enabled, role = role, onClick = onClick)
}

/** Gray-pill press (0.95) for chips and other gray clickables. */
@Composable
fun Modifier.grayPress(onClick: () -> Unit, role: Role = Role.Button, shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(999.dp)): Modifier {
    val source = remember { MutableInteractionSource() }
    return this.pressScale(source, Press.GRAY).clip(shape).clickable(source, indication = null, role = role, onClick = onClick)
}
