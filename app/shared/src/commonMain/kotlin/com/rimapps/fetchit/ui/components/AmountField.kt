package com.rimapps.fetchit.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rimapps.fetchit.ui.Ink
import com.rimapps.fetchit.ui.body
import com.rimapps.fetchit.ui.motion.CountUpMoney
import com.rimapps.fetchit.ui.motion.LocalReduceMotion
import com.rimapps.fetchit.ui.serif

/**
 * A dollar amount you can type: shows the value counting up to it; tap to edit (the underline turns black while
 * focused); Done or leaving the field commits whole dollars (1 – 100,000). [suffix] is the serif tail
 * (" a month"). [warning] slides down under it with a pulsing red dot.
 */
@Composable
fun AmountField(value: Double, onChange: (Double) -> Unit, suffix: String, modifier: Modifier = Modifier, warning: String? = null, style: TextStyle = body(34.sp, FontWeight.Medium)) {
    var editing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val underline by animateColorAsState(if (editing) Ink.Ink else Ink.TrackOff, tween(200), label = "underline")
    val thickness by animateDpAsState(if (editing) 2.dp else 1.dp, tween(200), label = "thick")
    fun commit() {
        text.filter { it.isDigit() }.take(6).toIntOrNull()?.takeIf { it > 0 }?.let { onChange(it.toDouble()) }
        editing = false
    }
    LaunchedEffect(editing) { if (editing) focus.requestFocus() }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.width(IntrinsicSize.Max)) {
            Row(verticalAlignment = Alignment.Bottom) {
                if (editing) {
                    Text("$", style = style)
                    BasicTextField(
                        text, { text = it.filter(Char::isDigit).take(6) },
                        singleLine = true, textStyle = style,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { commit(); focusManager.clearFocus() }),
                        // Sized to the digits typed: inside the intrinsic-width column a free-width field got squeezed and cut 4+ digits off.
                        modifier = Modifier.width((text.length.coerceAtLeast(2) * style.fontSize.value * 0.64f + 4).dp).focusRequester(focus).onFocusChanged { if (!it.isFocused && editing) commit() },
                    )
                } else {
                    CountUpMoney(
                        value, style, from = null,
                        modifier = Modifier.clickable(remember { MutableInteractionSource() }, indication = null) {
                            text = value.toLong().toString(); editing = true
                        },
                    )
                }
                Text(suffix, style = serif(20.sp, Ink.Grey), modifier = Modifier.height(IntrinsicSize.Min))
            }
            Box(Modifier.fillMaxWidth().height(thickness).background(underline))
        }
        AnimatedVisibility(warning != null, enter = slideInVertically(tween(250)) { -it } + fadeIn(tween(250)), exit = shrinkVertically(tween(200)) + fadeOut(tween(150))) {
            WarningNote(warning ?: "")
        }
    }
}

/** A warning line with a pulsing red dot (the dot just fades with reduce motion). */
@Composable
fun WarningNote(message: String, modifier: Modifier = Modifier) {
    val reduce = LocalReduceMotion.current
    val pulse by rememberInfiniteTransition(label = "dot").animateFloat(1f, 1.45f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "pulse")
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).graphicsLayer {
            if (reduce) alpha = 2f - pulse else { scaleX = pulse; scaleY = pulse }
        }.clip(CircleShape).background(Ink.Accent))
        Text(message, style = body(14.sp, FontWeight.Medium, Ink.Warning))
    }
}
