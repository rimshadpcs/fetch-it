package com.rimapps.fetchit.ui

import com.rimapps.fetchit.ui.icons.BoilingCamera
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rimapps.fetchit.AppState
import com.rimapps.fetchit.Screen
import com.rimapps.fetchit.platform.PhotoSource
import com.rimapps.fetchit.ui.components.Press
import com.rimapps.fetchit.ui.components.pressScale
import com.rimapps.fetchit.ui.icons.Doodle
import com.rimapps.fetchit.ui.icons.DoodleIcon
import com.rimapps.fetchit.ui.motion.LocalReduceMotion
import com.rimapps.fetchit.ui.motion.rememberHaptics
import com.rimapps.fetchit.ui.motion.uiSpring

/** The three tabs. Everything else is pushed on top of one of them. */
val TABS = setOf<Screen>(Screen.Activity, Screen.Fetch, Screen.Account)

/** Space a tab screen leaves at the bottom for the floating bar (bar + raised Fetch button + gutter). */
val NAV_SPACE: Dp = 118.dp

private val BAR_HEIGHT = 68.dp
private val RAISE = 28.dp // the bar starts this far down: the 80 dp Fetch button sits a third out of it
private val CENTER_GAP = 104.dp
private val PILL_WIDTH = 96.dp

/**
 * Floating pill bar: Activity · Fetch · You. The light pill slides between Activity and You (spring 0.65); on
 * Fetch the raised black button is the active one. Active icons pop (1.12 → 1) and go ink with a bolder stroke.
 * Tapping Fetch while on Fetch opens the camera. Activity carries a red dot while a purchase needs your OK.
 */
@Composable
fun BottomNav(app: AppState, photos: PhotoSource, modifier: Modifier = Modifier) {
    val current = app.screen
    val haptics = rememberHaptics()
    val reduce = LocalReduceMotion.current
    val needsOk = app.needsApproval
    BoxWithConstraints(modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 22.dp).height(BAR_HEIGHT + RAISE)) {
        val sideWidth = (maxWidth - CENTER_GAP) / 2
        val activeX = when (current) { Screen.Activity -> sideWidth / 2 - PILL_WIDTH / 2; Screen.Account -> maxWidth - sideWidth / 2 - PILL_WIDTH / 2; else -> null }
        var lastX by remember { mutableStateOf(activeX ?: 0.dp) }
        if (activeX != null) lastX = activeX
        val pillX by animateDpAsState(lastX, if (reduce) tween(0) else uiSpring(stiffness = 420f), label = "navPill")
        val pillAlpha by animateFloatAsState(if (activeX != null) 1f else 0f, tween(180), label = "navPillAlpha")

        // The bar.
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(BAR_HEIGHT).shadow(10.dp, RoundedCornerShape(34.dp)).clip(RoundedCornerShape(34.dp)).background(Ink.White)) {
            Box(Modifier.offset(x = pillX).align(Alignment.CenterStart).width(PILL_WIDTH).height(54.dp).graphicsLayer { alpha = pillAlpha }.clip(RoundedCornerShape(27.dp)).background(Ink.Pill))
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                SideTab("Activity", Doodle.Receipt, current == Screen.Activity, dot = needsOk, modifier = Modifier.width(sideWidth)) {
                    if (current != Screen.Activity) haptics.tick(); app.reset(Screen.Activity)
                }
                Box(Modifier.width(CENTER_GAP))
                SideTab("You", Doodle.User, current == Screen.Account, modifier = Modifier.width(sideWidth)) {
                    if (current != Screen.Account) haptics.tick(); app.reset(Screen.Account)
                }
            }
        }
        FetchButton(Modifier.align(Alignment.TopCenter)) {
            haptics.tap()
            if (current == Screen.Fetch && app.session == null) photos.takePhoto() else app.reset(Screen.Fetch)
        }
    }
}

@Composable
private fun SideTab(label: String, icon: Doodle, active: Boolean, modifier: Modifier, dot: Boolean = false, onClick: () -> Unit) {
    val reduce = LocalReduceMotion.current
    val color by animateColorAsState(if (active) Ink.Ink else Ink.Muted, tween(200), label = "tabColor")
    val pop = remember { Animatable(1f) }
    LaunchedEffect(active) { if (active && !reduce) { pop.snapTo(1.12f); pop.animateTo(1f, uiSpring(stiffness = 500f)) } }
    Column(
        modifier.fillMaxHeight()
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Tab, onClick = onClick)
            .semantics { contentDescription = label; selected = active },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Box {
            DoodleIcon(icon, null, Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value }, color = color, strokeWidth = if (active) 4.6f else 4f)
            AttentionDot(dot, Modifier.align(Alignment.TopEnd).offset(5.dp, (-3).dp))
        }
        Text(label, style = body(12.sp, if (active) FontWeight.SemiBold else FontWeight.Medium, color), modifier = Modifier.padding(top = 2.dp))
    }
}

/** 8 dp red dot with a 2 dp white ring; pops in when it appears (fades with reduce motion). */
@Composable
private fun AttentionDot(visible: Boolean, modifier: Modifier) {
    val reduce = LocalReduceMotion.current
    AnimatedVisibility(
        visible, modifier = modifier,
        enter = if (reduce) fadeIn(tween(150)) else scaleIn(uiSpring(stiffness = 500f, damping = 0.55f)) + fadeIn(tween(100)),
        exit = if (reduce) fadeOut(tween(150)) else scaleOut(tween(150)) + fadeOut(tween(150)),
    ) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(Ink.White).padding(2.dp).clip(CircleShape).background(Ink.Accent))
    }
}

/**
 * The center Fetch button: an 80 dp black circle sitting a third out of the bar, ringed in the bar's white so it
 * looks set into it, with the camera doodle in white. Press 0.92 with a spring back.
 */
@Composable
private fun FetchButton(modifier: Modifier, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Box(
        modifier.size(80.dp).pressScale(source, 0.92f)
            .shadow(12.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.35f)).clip(CircleShape).background(Ink.White).padding(5.dp)
            .clip(CircleShape).background(Ink.Ink)
            .clickable(source, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Fetch"; role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        BoilingCamera(34.dp, Ink.White) // the one animated icon: draws on, then boils
    }
}
