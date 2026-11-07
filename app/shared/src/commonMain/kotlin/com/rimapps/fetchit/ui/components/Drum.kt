package com.rimapps.fetchit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rimapps.fetchit.ui.Ink
import com.rimapps.fetchit.ui.body
import com.rimapps.fetchit.ui.motion.LocalReduceMotion
import com.rimapps.fetchit.ui.motion.rememberHaptics
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A 3D drum (wheel) picker: values roll past a highlighted middle row; off-centre values tip away (rotationX),
 * shrink, fade and blur. Flings snap to a value; a tick haptic as each value crosses the middle; [onSelect] when
 * it settles. A [selected] that isn't in [values] (typed) joins the list. With reduce motion: no tip or blur.
 */
@Composable
fun DrumPicker(
    values: List<Double>,
    selected: Double,
    onSelect: (Double) -> Unit,
    label: (Double) -> String,
    contentDescription: String,
    modifier: Modifier = Modifier,
    itemHeight: Dp = 40.dp,
    visible: Int = 5,
) {
    val all = remember(values, selected) { (values + selected).distinct().sorted() }
    val startIndex = all.indexOf(selected).coerceAtLeast(0)
    val state = rememberLazyListState(startIndex)
    val haptics = rememberHaptics()
    val reduce = LocalReduceMotion.current
    val itemPx = with(LocalDensity.current) { itemHeight.toPx() }
    val centered by remember(all) { derivedStateOf { (state.firstVisibleItemIndex + (state.firstVisibleItemScrollOffset / itemPx).roundToInt()).coerceIn(0, all.lastIndex) } }

    // External change (typed, or a preset elsewhere): roll to it.
    LaunchedEffect(selected) { val i = all.indexOf(selected); if (i >= 0 && i != centered) state.animateScrollToItem(i) }
    // Tick as values cross the middle; report once the wheel settles.
    LaunchedEffect(all) { snapshotFlow { centered }.distinctUntilChanged().collect { if (state.isScrollInProgress) haptics.tick() } }
    LaunchedEffect(all) {
        snapshotFlow { state.isScrollInProgress }.distinctUntilChanged().filter { !it }.collect {
            all.getOrNull(centered)?.let { if (it != selected) onSelect(it) }
        }
    }

    Box(
        modifier.height(itemHeight * visible).semantics {
            this.contentDescription = contentDescription
            stateDescription = label(all.getOrElse(centered) { selected })
        },
        contentAlignment = Alignment.Center,
    ) {
        // The middle row's paper band.
        Box(Modifier.fillMaxWidth().height(itemHeight).clip(RoundedCornerShape(12.dp)).background(Ink.Pill))
        LazyColumn(
            state = state, flingBehavior = rememberSnapFlingBehavior(state),
            contentPadding = PaddingValues(vertical = itemHeight * (visible / 2)),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth(),
        ) {
            itemsIndexed(all) { i, v ->
                val d = run {
                    val info = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == i } ?: return@run visible / 2f
                    val center = (state.layoutInfo.viewportStartOffset + state.layoutInfo.viewportEndOffset) / 2f
                    (info.offset + info.size / 2f - center) / itemPx
                }
                val far = abs(d).coerceAtMost(3f)
                Box(
                    Modifier.height(itemHeight).fillMaxWidth()
                        .graphicsLayer {
                            alpha = 1f - 0.3f * far
                            if (!reduce) { rotationX = -d * 24f; val s = 1f - 0.1f * far; scaleX = s; scaleY = s; cameraDistance = 10f * density }
                        }
                        .then(if (!reduce && far > 0.4f) Modifier.blur((far * 1.6f).dp) else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label(v), style = body(if (far < 0.5f) 24.sp else 19.sp, if (far < 0.5f) FontWeight.SemiBold else FontWeight.Medium, if (far < 0.5f) Ink.Ink else Ink.Muted))
                }
            }
        }
    }
}
