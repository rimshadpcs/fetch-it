package com.rimapps.fetchit.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import com.rimapps.fetchit.ui.money

/**
 * [target] animated over 500 ms: from [from] the first time (null = start at the target, so only later changes
 * animate), then from wherever it is.
 */
@Composable
fun animatedAmount(target: Double, from: Double? = 0.0, durationMs: Int = 500): Double {
    val reduce = LocalReduceMotion.current
    val a = remember { Animatable((if (reduce) target else from ?: target).toFloat()) }
    LaunchedEffect(target, reduce) {
        if (reduce) a.snapTo(target.toFloat()) else a.animateTo(target.toFloat(), tween(durationMs, easing = FastOutSlowInEasing))
    }
    return a.value.toDouble()
}

/** A money amount that counts up (or down) to its value. Null shows "—". */
@Composable
fun CountUpMoney(amount: Double?, style: TextStyle, modifier: Modifier = Modifier, currency: String = "USD", from: Double? = 0.0, suffix: String = "") {
    if (amount == null) { Text(money(null, currency) + suffix, style = style, modifier = modifier); return }
    Text(money(animatedAmount(amount, from), currency) + suffix, style = style, modifier = modifier)
}
