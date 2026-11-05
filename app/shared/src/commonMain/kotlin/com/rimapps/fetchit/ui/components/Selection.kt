package com.rimapps.fetchit.ui.components

import com.rimapps.fetchit.ui.icons.DoodleIcon
import com.rimapps.fetchit.ui.icons.Doodle
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rimapps.fetchit.ui.Ink
import com.rimapps.fetchit.ui.body
import com.rimapps.fetchit.ui.motion.LocalReduceMotion
import com.rimapps.fetchit.ui.motion.PathDraw
import com.rimapps.fetchit.ui.motion.rememberHaptics
import com.rimapps.fetchit.ui.motion.uiSpring
import com.rimapps.fetchit.ui.motion.wobblyLoop
import kotlinx.coroutines.delay

/**
 * Segmented control: gray track, a white pill that slides (spring) to the selected segment, label colors
 * crossfade, a tick haptic on change. A value that isn't one of [options] hides the pill.
 */
@Composable
fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberHaptics()
    val reduce = LocalReduceMotion.current
    val index = options.indexOf(selected)
    BoxWithConstraints(modifier.clip(RoundedCornerShape(999.dp)).background(Ink.Pill).padding(4.dp)) {
        val gap = 4.dp
        val segment = (maxWidth - gap * (options.size - 1)) / options.size
        val x by animateDpAsState((segment + gap) * index.coerceAtLeast(0), if (reduce) tween(150) else uiSpring(stiffness = 500f), label = "pill")
        val pillAlpha by animateFloatAsState(if (index >= 0) 1f else 0f, tween(150), label = "pillAlpha")
        Box(Modifier.offset(x = x).width(segment).height(40.dp).graphicsLayer { alpha = pillAlpha }.shadow(2.dp, RoundedCornerShape(999.dp)).clip(RoundedCornerShape(999.dp)).background(Ink.White))
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            options.forEach { o ->
                val on = o == selected
                val color by animateColorAsState(if (on) Ink.Ink else Ink.NavGrey, tween(200), label = "segText")
                Box(
                    Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(999.dp))
                        .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Tab) { if (!on) { haptics.tick(); onSelect(o) } },
                    contentAlignment = Alignment.Center,
                ) { Text(label(o), style = body(15.sp, if (on) FontWeight.SemiBold else FontWeight.Medium, color)) }
            }
        }
    }
}

/**
 * Gray pill chip, black when selected. Press 0.95. Selecting pops (1.08 → 1); deselecting shrinks a touch and
 * fades back to gray. [check]: multi-select chips also draw a ✓ in front when selected.
 */
@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, check: Boolean = false, leading: Doodle? = null) {
    val reduce = LocalReduceMotion.current
    val bg by animateColorAsState(if (selected) Ink.Ink else Ink.Pill, tween(180), label = "chipBg")
    val fg by animateColorAsState(if (selected) Ink.White else Ink.NavGrey, tween(180), label = "chipFg")
    val pop = remember { Animatable(1f) }
    var was by remember { mutableStateOf(selected) }
    LaunchedEffect(selected) {
        if (selected != was && !reduce) {
            if (selected) { pop.snapTo(1.08f); pop.animateTo(1f, uiSpring(stiffness = 600f, damping = 0.6f)) }
            else { pop.animateTo(0.94f, tween(90)); pop.animateTo(1f, uiSpring(stiffness = 600f)) }
        }
        was = selected
    }
    Row(
        modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value }.height(40.dp)
            .grayPress(onClick, Role.Checkbox).background(bg).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (check) AnimatedVisibility(selected, enter = expandHorizontally(tween(160)) + fadeIn(tween(160)), exit = shrinkHorizontally(tween(140)) + fadeOut(tween(100))) {
            CheckMark(Ink.White, Modifier.padding(end = 6.dp).size(14.dp), durationMs = 220, delayMs = 60, strokeWidth = 2.dp)
        }
        if (leading != null) DoodleIcon(leading, null, Modifier.padding(end = 6.dp), size = 16.dp, color = fg, strokeWidth = 4.6f)
        Text(text, style = body(14.sp, FontWeight.SemiBold, fg))
    }
}

/** A labelled row with the paw switch; the whole row toggles. */
@Composable
fun Toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null) { onChange(!on) }, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = body(15.sp, FontWeight.Medium), modifier = Modifier.weight(1f))
        PawSwitch(on, onChange)
    }
}

/** Switch: the knob springs across, the track fills ink, and turning on leaves a tiny paw print for 600 ms. */
@Composable
fun PawSwitch(on: Boolean, onChange: (Boolean) -> Unit) {
    val haptics = rememberHaptics()
    val reduce = LocalReduceMotion.current
    val knob by animateDpAsState(if (on) 22.dp else 0.dp, if (reduce) tween(120) else uiSpring(stiffness = 600f, damping = 0.6f), label = "knob")
    val track by animateColorAsState(if (on) Ink.Ink else Color(0xFFD6D6D2), tween(200), label = "track")
    var paw by remember { mutableStateOf(false) }
    var was by remember { mutableStateOf(on) }
    LaunchedEffect(on) {
        if (on && !was) { paw = true; delay(600); paw = false }
        was = on
    }
    val pawAlpha by animateFloatAsState(if (paw) 0.9f else 0f, tween(150), label = "paw")
    Box(
        Modifier.size(52.dp, 30.dp).clip(RoundedCornerShape(999.dp)).background(track)
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Switch) { haptics.toggle(!on); onChange(!on) }
            .padding(3.dp),
    ) {
        Canvas(Modifier.size(24.dp).graphicsLayer { alpha = pawAlpha }) {
            val c = center
            val r = size.minDimension * 0.17f
            drawOval(Ink.White, Offset(c.x - r * 0.6f, c.y - r * 0.15f), Size(r * 1.2f, r))
            listOf(-0.75f, -0.25f, 0.25f, 0.75f).forEach { k -> drawCircle(Ink.White, r * 0.3f, Offset(c.x + k * r, c.y - r * (if (k in -0.3f..0.3f) 0.9f else 0.6f))) }
        }
        Box(Modifier.offset(x = knob).size(24.dp).shadow(2.dp, CircleShape).clip(CircleShape).background(Ink.White))
    }
}

/**
 * Pick one of [options]: plain labels, the selected one gets a pen circle drawn around it (300 ms) and a tick.
 * [flipKey] changing (US → UK → EU) flips each label over like a card, staggered.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SizePicker(options: List<String>, selected: String?, onPick: (String) -> Unit, modifier: Modifier = Modifier, display: (String) -> String = { it }, flipKey: Any? = null) {
    val haptics = rememberHaptics()
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEachIndexed { i, o ->
            val on = o == selected
            Box(
                Modifier.size(width = if (display(o).length > 3) 70.dp else 50.dp, height = 46.dp)
                    .grayPress({ if (!on) { haptics.tick(); onPick(o) } }, Role.RadioButton, RoundedCornerShape(23.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (on) PathDraw(Modifier.matchParentSize(), strokeWidth = 2.2.dp, durationMs = 300, key = o) { s ->
                    listOf(wobblyLoop(Rect(Offset(s.width * 0.08f, s.height * 0.1f), Size(s.width * 0.84f, s.height * 0.8f)), seed = i + 3))
                }
                FlipText(display(o), flipKey, i * 30, body(16.sp, if (on) FontWeight.Bold else FontWeight.Medium, if (on) Ink.Ink else Ink.NavGrey))
            }
        }
    }
}

/** Text that flips over (rotationX 0 → 90, swap, -90 → 0; 240 ms) when [key] changes. */
@Composable
private fun FlipText(text: String, key: Any?, delayMs: Int, style: androidx.compose.ui.text.TextStyle) {
    val reduce = LocalReduceMotion.current
    val r = remember { Animatable(0f) }
    var shown by remember { mutableStateOf(text) }
    var lastKey by remember { mutableStateOf(key) }
    LaunchedEffect(text, key) {
        if (key == lastKey || reduce) { shown = text; lastKey = key; return@LaunchedEffect }
        lastKey = key
        delay(delayMs.toLong())
        r.animateTo(90f, tween(120))
        shown = text
        r.snapTo(-90f)
        r.animateTo(0f, tween(120))
    }
    Text(shown, style = style, modifier = Modifier.graphicsLayer { rotationX = r.value; cameraDistance = 12f * density })
}
