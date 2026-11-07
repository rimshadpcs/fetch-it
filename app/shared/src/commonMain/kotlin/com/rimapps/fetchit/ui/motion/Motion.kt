package com.rimapps.fetchit.ui.motion

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

// The scrapbook motion kit. Rules: one-shot animations stay under ~600 ms (only Scout's idle loops run longer),
// nothing here blocks input, and with reduce motion on everything shows its end state at once.

/** True when the system asks for reduced motion; provided once at the app root. */
val LocalReduceMotion = staticCompositionLocalOf { false }

/** 0 → 1 over [durationMs] after [delayMs], restarted when [key] changes. Already 1 with reduce motion (unless [fadeOnly]). */
@Composable
fun rememberProgress(key: Any? = Unit, durationMs: Int = 450, delayMs: Int = 0, easing: Easing = FastOutSlowInEasing, fadeOnly: Boolean = false): State<Float> {
    val reduce = LocalReduceMotion.current
    // fadeOnly: the caller turns this into a plain fade under reduce motion, so keep animating (it's not motion).
    val snap = reduce && !fadeOnly
    val a = remember(key) { Animatable(if (snap) 1f else 0f) }
    LaunchedEffect(key, reduce) {
        if (snap) { a.snapTo(1f); return@LaunchedEffect }
        if (delayMs > 0) delay(delayMs.toLong())
        a.animateTo(1f, tween(durationMs, easing = easing))
    }
    return a.asState()
}

/**
 * Milliseconds since this composable appeared, ticking every frame (0 forever with reduce motion). Read it in a
 * draw lambda so loops redraw without recomposing.
 */
@Composable
fun rememberClock(running: Boolean = true): State<Long> {
    val reduce = LocalReduceMotion.current
    val t = remember { mutableLongStateOf(0L) }
    LaunchedEffect(running, reduce) {
        if (!running || reduce) return@LaunchedEffect
        val start = withFrameMillis { it } - t.longValue
        while (true) withFrameMillis { t.longValue = it - start }
    }
    return t
}

/** A short horizontal shake of the whole screen (a stamp landing). */
class ScreenShake {
    internal val x = Animatable(0f)
    suspend fun shake() {
        x.snapTo(0f)
        x.animateTo(0f, keyframes { durationMillis = 300; 7f at 40; -6f at 100; 4f at 160; -2f at 220 })
    }
}

val LocalScreenShake = staticCompositionLocalOf { ScreenShake() }

/** Applied once at the root, around every screen. */
fun Modifier.shakeable(shake: ScreenShake): Modifier = graphicsLayer { translationX = shake.x.value * 1.dp.toPx() }

/** Every buzz in the app, by meaning. */
class Haptics(private val h: HapticFeedback) {
    fun stamp() = h.performHapticFeedback(HapticFeedbackType.LongPress)
    fun undo() = h.performHapticFeedback(HapticFeedbackType.ToggleOff)
    fun approve() = h.performHapticFeedback(HapticFeedbackType.Confirm)
    fun block() = h.performHapticFeedback(HapticFeedbackType.Reject)
    /** Light tap: black pill pressed. */
    fun tap() = h.performHapticFeedback(HapticFeedbackType.VirtualKey)
    /** Segmented control / size picked. */
    fun tick() = h.performHapticFeedback(HapticFeedbackType.SegmentTick)
    /** A primary action finished (check drawn). */
    fun success() = h.performHapticFeedback(HapticFeedbackType.Confirm)
    fun toggle(on: Boolean) = h.performHapticFeedback(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
}

/** Spring used by every press / select / slide: dampingRatio 0.6–0.7 per the motion rules. */
fun <T> uiSpring(stiffness: Float = 600f, damping: Float = 0.65f) = androidx.compose.animation.core.spring<T>(dampingRatio = damping, stiffness = stiffness)

@Composable
fun rememberHaptics(): Haptics {
    val h = LocalHapticFeedback.current
    return remember(h) { Haptics(h) }
}

// ---- Shared elements between screens (the user's photo: Fetch → Which one? → Results header) ----

@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedScope = compositionLocalOf<SharedTransitionScope?> { null }
val LocalScreenScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * Same element across screens even when it's drawn differently on each (big Scout → the small Scout peeking over the
 * viewfinder): the bounds morph and the two drawings cross-fade. No-op with reduce motion.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedShape(key: Any): Modifier {
    val shared = LocalSharedScope.current ?: return this
    val screen = LocalScreenScope.current ?: return this
    if (LocalReduceMotion.current) return this
    return with(shared) {
        this@sharedShape.sharedBounds(
            rememberSharedContentState(key), screen,
            boundsTransform = { _, _ -> tween(450, easing = FastOutSlowInEasing) },
            resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(),
        )
    }
}

/** Marks this as the same element as every other `sharedPhoto(key)` on the next / previous screen. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedPhoto(key: Any): Modifier {
    val shared = LocalSharedScope.current ?: return this
    val screen = LocalScreenScope.current ?: return this
    if (LocalReduceMotion.current) return this
    return with(shared) {
        this@sharedPhoto.sharedElement(
            rememberSharedContentState(key), screen,
            boundsTransform = { _, _ -> tween(420, easing = FastOutSlowInEasing) },
        )
    }
}
