package com.rimapps.fetchit.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rimapps.fetchit.ui.motion.Dog
import com.rimapps.fetchit.ui.motion.DogMood

/** The fetcher, wagging (or just breathing when `wag` is off). Moods and running live in motion/DogMood.kt. */
@Composable
fun Mascot(size: Dp = 120.dp, modifier: Modifier = Modifier, wag: Boolean = true) =
    Dog(size, if (wag) DogMood.Wag else DogMood.Idle, modifier)
