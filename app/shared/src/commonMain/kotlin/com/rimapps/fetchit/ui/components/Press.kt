package com.rimapps.fetchit.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.rimapps.fetchit.ui.motion.LocalReduceMotion
import com.rimapps.fetchit.ui.motion.uiSpring
import kotlinx.coroutines.launch

/** How far each kind of control sinks when pressed. */
object Press {
    const val PILL = 0.96f
    const val GRAY = 0.95f
    const val ROUND = 0.90f
}

/**
 * Sinks to [pressed] in 80 ms while [source] is pressed, springs back with a small overshoot on release.
 * [tilt]: also rotates ±6° once on release (round icon buttons), alternating sides. Off with reduce motion.
 * Visual only: it never delays or swallows the click.
 */
@Composable
fun Modifier.pressScale(source: InteractionSource, pressed: Float, tilt: Boolean = false): Modifier {
    if (LocalReduceMotion.current) return this
    val isPressed by source.collectIsPressedAsState()
    val scale = remember { Animatable(1f) }
    val rotation = remember { Animatable(0f) }
    var side by remember { mutableIntStateOf(1) }
    var wasPressed by remember { mutableIntStateOf(0) }
    LaunchedEffect(isPressed) {
        if (isPressed) { wasPressed = 1; scale.animateTo(pressed, tween(80)); return@LaunchedEffect }
        if (wasPressed == 0) return@LaunchedEffect
        launch { scale.animateTo(1f, uiSpring(stiffness = 700f, damping = 0.6f)) }
        if (tilt) {
            rotation.animateTo(6f * side, tween(90))
            rotation.animateTo(0f, uiSpring(stiffness = 500f, damping = 0.6f))
            side = -side
        }
    }
    return graphicsLayer { scaleX = scale.value; scaleY = scale.value; rotationZ = rotation.value }
}
