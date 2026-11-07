package com.rimapps.fetchit.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.rimapps.fetchit.data.Offer
import com.rimapps.fetchit.ui.icons.Doodle
import com.rimapps.fetchit.ui.icons.DoodleIcon
import com.rimapps.fetchit.ui.motion.LocalReduceMotion
import com.rimapps.fetchit.ui.motion.rememberHaptics
import com.rimapps.fetchit.ui.motion.uiSpring
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Save for later: the doodle heart, an ink outline when off, filled red when saved. Saving pops it (1.35 → 1)
 * with a little burst of pen strokes and a tick; with reduce motion it just changes colour.
 */
@Composable
fun HeartButton(saved: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val reduce = LocalReduceMotion.current
    val haptics = rememberHaptics()
    val pop = remember { Animatable(1f) }
    val burst = remember { Animatable(1f) }
    var was by remember { mutableStateOf(saved) }
    LaunchedEffect(saved) {
        if (saved && !was && !reduce) {
            launch { burst.snapTo(0f); burst.animateTo(1f, tween(360)) }
            pop.snapTo(1.35f); pop.animateTo(1f, uiSpring(stiffness = 600f, damping = 0.5f))
        }
        was = saved
    }
    Box(
        modifier.size(40.dp).clip(CircleShape).background(Ink.White.copy(alpha = 0.92f))
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Checkbox) { haptics.tick(); onToggle() }
            .semantics { contentDescription = "Save for later"; stateDescription = if (saved) "saved" else "not saved" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(40.dp)) {
            val b = burst.value
            if (b in 0.01f..0.99f) repeat(7) { i ->
                val a = (i / 7f) * 2f * PI.toFloat() - PI.toFloat() / 2
                val r0 = size.minDimension * (0.32f + 0.18f * b)
                val r1 = r0 + size.minDimension * 0.12f * (1f - b)
                drawLine(Ink.Accent.copy(alpha = 1f - b), center + Offset(cos(a) * r0, sin(a) * r0), center + Offset(cos(a) * r1, sin(a) * r1), 2.dp.toPx(), StrokeCap.Round)
            }
        }
        DoodleIcon(
            Doodle.Heart, null, Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value },
            size = 24.dp, color = if (saved) Ink.Accent else Ink.Ink, strokeWidth = if (saved) 4.6f else 4f, filled = saved,
        )
    }
}

/**
 * "More matches" as a fan of tilted polaroids: swipe sideways to bring one to the front (it springs to the
 * nearest card); tap the front card to pick it, a side card to bring it forward. The picked one has a price tag.
 */
@Composable
fun PolaroidFan(offers: List<Offer>, picked: Offer?, onPick: (Offer) -> Unit, modifier: Modifier = Modifier) {
    if (offers.isEmpty()) return
    val reduce = LocalReduceMotion.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val current = remember(offers) { Animatable(0f) }
    val stepPx = with(LocalDensity.current) { 92.dp.toPx() }
    fun settle(to: Float) = scope.launch {
        val target = to.roundToInt().coerceIn(0, offers.lastIndex).toFloat()
        if (target != current.value.roundToInt().toFloat()) haptics.tick()
        if (reduce) current.snapTo(target) else current.animateTo(target, uiSpring(stiffness = 420f))
    }
    Box(
        modifier.fillMaxWidth().height(250.dp).pointerInput(offers) {
            detectHorizontalDragGestures(
                onDragEnd = { settle(current.value) },
                onHorizontalDrag = { change, dx -> change.consume(); scope.launch { current.snapTo((current.value - dx / stepPx).coerceIn(-0.4f, offers.lastIndex + 0.4f)) } },
            )
        },
        contentAlignment = Alignment.Center,
    ) {
        offers.forEachIndexed { i, o ->
            val rel = i - current.value
            if (abs(rel) > 3.2f) return@forEachIndexed
            Column(
                Modifier.zIndex(10f - abs(rel)).width(150.dp)
                    .graphicsLayer {
                        translationX = rel * stepPx
                        rotationZ = rel * 7f + if (i % 2 == 0) -1.5f else 1.5f
                        val s = 1f - 0.08f * abs(rel).coerceAtMost(3f); scaleX = s; scaleY = s
                        alpha = 1f - 0.18f * abs(rel).coerceAtMost(3f)
                    }
                    .shadow(4.dp).background(Ink.White)
                    .clickable { if (abs(rel) < 0.5f) onPick(o) else settle(i.toFloat()) }
                    .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 10.dp),
            ) {
                ProductImage(o.imageUrl, Modifier.fillMaxWidth().height(150.dp))
                Text(money(o.price, o.currency), style = serif(18.sp), modifier = Modifier.padding(top = 6.dp))
                Text(o.merchant.removePrefix("www."), style = body(12.sp, FontWeight.SemiBold, Ink.Muted), maxLines = 1)
                if (o == picked) Text("picked", style = hand(17.sp, Ink.Accent))
            }
        }
    }
}
