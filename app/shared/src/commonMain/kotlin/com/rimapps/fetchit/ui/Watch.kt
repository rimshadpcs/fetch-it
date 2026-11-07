package com.rimapps.fetchit.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rimapps.fetchit.AppState
import com.rimapps.fetchit.data.Offer
import com.rimapps.fetchit.data.Watch
import com.rimapps.fetchit.friendly
import com.rimapps.fetchit.ui.components.AmountField
import com.rimapps.fetchit.ui.components.Chip
import com.rimapps.fetchit.ui.components.PillButton
import com.rimapps.fetchit.ui.icons.Doodle
import com.rimapps.fetchit.ui.icons.DoodleIcon
import com.rimapps.fetchit.ui.motion.blurIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.floor

/** The little "watch price" pill on Results (or "watching ≤ $40" once it is). */
@Composable
fun WatchChip(watch: Watch?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(999.dp)).background(if (watch != null) Ink.Ink else Ink.Pill)
            .grayPressClick(onClick).padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val fg = if (watch != null) Ink.White else Ink.Ink
        DoodleIcon(Doodle.PriceTag, null, size = 18.dp, color = fg, strokeWidth = 4.4f)
        Text(
            if (watch != null) "watching ≤ ${money(watch.targetPrice, watch.currency)}" else "watch price",
            style = body(14.sp, FontWeight.SemiBold, fg),
        )
        if (watch != null) Box(Modifier.size(8.dp).clip(CircleShape).background(if (watch.dropped) Ink.Drop else Ink.Accent))
    }
}

private fun Modifier.grayPressClick(onClick: () -> Unit) = clickable(onClick = onClick)

/** A sensible first target: about 10% under today's price, whole dollars. */
private fun suggestedTarget(price: Double) = floor(price * 0.9).coerceAtLeast(1.0)

/**
 * "Watch for a drop": pick a target under today's price; Scout checks hourly and buys it at or under the target,
 * through the house rules like any Buy (over the per-item limit he still asks). Already watching → change the
 * target or stop.
 */
@Composable
fun BoxScope.WatchSheet(app: AppState, open: Boolean, offer: Offer?, size: String?, placeId: String?, onDismiss: () -> Unit) {
    AnimatedVisibility(open, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
        Box(Modifier.fillMaxSize().background(Ink.Ink.copy(alpha = 0.35f)).clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss))
    }
    AnimatedVisibility(
        open && offer != null, modifier = Modifier.align(Alignment.BottomCenter),
        enter = slideInVertically(tween(300)) { it } + fadeIn(tween(150)), exit = slideOutVertically(tween(250)) { it } + fadeOut(tween(150)),
    ) {
        val o = offer ?: return@AnimatedVisibility
        val existing = app.watchFor(o)
        var target by remember(o, open) { mutableStateOf(existing?.targetPrice ?: suggestedTarget(o.price)) }
        var busy by remember(o, open) { mutableStateOf(false) }
        var done by remember(o, open) { mutableStateOf(false) }
        var error by remember(o, open) { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()
        val limit = app.rules?.perItemLimit
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)).background(Ink.Background)
                .clickable(remember { MutableInteractionSource() }, indication = null) {}
                .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 34.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).width(44.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(Ink.TrackOff))
            Text(if (existing != null) "Watching this one" else "Watch for a drop", style = hand(28.sp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                ProductImage(o.imageUrl, Modifier.size(56.dp))
                Column(Modifier.weight(1f)) {
                    Text(o.title.lowercase(), style = hand(18.sp), maxLines = 2)
                    Text("${o.merchant.removePrefix("www.")} · now ${money(o.price, o.currency)}", style = body(13.sp, FontWeight.SemiBold, Ink.Muted))
                }
            }
            Text("Buy it when it's", style = body(14.sp, FontWeight.SemiBold, Ink.Muted))
            AmountField(
                target, { target = it }, " or less",
                warning = when {
                    target >= o.price -> "That's today's price or more: I'd buy it at the next check."
                    limit != null && target > limit -> "Over your ${money(limit)} limit, so I'll still ask you first."
                    else -> null
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(10, 20, 30).forEach { pct ->
                    val t = floor(o.price * (100 - pct) / 100).coerceAtLeast(1.0)
                    Chip("$pct% off", target == t, onClick = { target = t })
                }
            }
            Text(
                "I check the price every hour. At ${money(target, o.currency)} or less I fetch it for you, within your house rules" +
                    (size?.let { ", size $it" } ?: "") + ".",
                style = hand(19.sp, Ink.Muted),
            )
            ErrorNote(error)
            PillButton(
                if (existing != null) "Change target" else "Watch it",
                enabled = target < o.price, loading = busy, done = done,
                onClick = {
                    busy = true; error = null
                    scope.launch {
                        app.startWatch(o, target, size, placeId)
                            .onSuccess { busy = false; done = true; delay(DONE_PAUSE); onDismiss() }
                            .onFailure { busy = false; error = it.friendly() }
                    }
                },
            )
            if (existing != null) PillButton("Stop watching", dark = false, height = 50.dp, onClick = {
                scope.launch { app.stopWatch(existing).onSuccess { onDismiss() }.onFailure { error = it.friendly() } }
            })
        }
    }
}

private const val DONE_PAUSE = 350L

/** You → Price watches: each watch with its target, the last price seen, and Stop. Finished ones below. */
@Composable
fun WatchesScreen(app: AppState) {
    LaunchedEffect(Unit) { runCatching { app.watches = app.api.watches() } }
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize().dotted()) {
        Column(Modifier.fillMaxSize().padding(top = 56.dp, bottom = 34.dp)) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BackButton({ app.back() })
                Text("Price watches", style = hand(30.sp))
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                val live = app.watches.filter { it.watching }
                val past = app.watches.filter { it.status == "triggered" }.take(10)
                if (live.isEmpty() && past.isEmpty()) Text("Nothing watched yet. On a result, tap \"watch price\" and pick a target.", style = hand(22.sp, Ink.Muted))
                ErrorNote(error)
                live.forEachIndexed { i, w ->
                    Box(Modifier.blurIn(i)) {
                        WatchNote(w, if (i % 2 == 0) -0.8f else 0.8f) {
                            scope.launch { app.stopWatch(w).onFailure { error = it.friendly() } }
                        }
                    }
                }
                if (past.isNotEmpty()) {
                    Text("price dropped", style = hand(22.sp, Ink.Muted), modifier = Modifier.padding(top = 8.dp))
                    past.forEachIndexed { i, w -> Box(Modifier.blurIn(live.size + i)) { WatchNote(w, 0f, onStop = null) } }
                }
            }
        }
    }
}

@Composable
private fun WatchNote(w: Watch, tilt: Float, onStop: (() -> Unit)?) {
    PaperNote(Modifier.fillMaxWidth(), tilt = tilt) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            ProductImage(w.imageUrl, Modifier.size(64.dp))
            Column(Modifier.weight(1f)) {
                Text(w.title.lowercase(), style = hand(19.sp), maxLines = 2)
                Text(w.merchant.removePrefix("www."), style = body(13.sp, FontWeight.SemiBold, Ink.Muted))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Green: cheaper than when watching started; red: no drop yet.
                    if (w.watching) Box(Modifier.size(9.dp).clip(CircleShape).background(if (w.dropped) Ink.Drop else Ink.Accent))
                    Text("≤ ${money(w.targetPrice, w.currency)}", style = serif(18.sp))
                    Text(
                        if (w.watching) w.lastPrice?.let { "now ${money(it, w.currency)}" + if (w.dropped) " (was ${money(w.startPrice, w.currency)})" else "" } ?: "not listed right now"
                        else "dropped to ${money(w.lastPrice, w.currency)}" + (w.purchaseStatus?.let { " · ${it.replace('_', ' ')}" } ?: ""),
                        style = body(13.sp, FontWeight.SemiBold, if (w.watching) Ink.Muted else Ink.Accent),
                    )
                }
            }
        }
        if (onStop != null) Text(
            "stop watching", style = body(14.sp, FontWeight.SemiBold, Ink.Muted),
            modifier = Modifier.align(Alignment.End).clip(RoundedCornerShape(999.dp)).clickable(onClick = onStop).padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}
