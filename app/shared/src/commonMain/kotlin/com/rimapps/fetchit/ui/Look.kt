package com.rimapps.fetchit.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rimapps.fetchit.AppState
import com.rimapps.fetchit.Screen
import com.rimapps.fetchit.data.LookGroup
import com.rimapps.fetchit.data.Offer
import com.rimapps.fetchit.friendly
import com.rimapps.fetchit.ui.components.Chip
import com.rimapps.fetchit.ui.components.PillButton
import com.rimapps.fetchit.ui.motion.PawLoader
import com.rimapps.fetchit.ui.motion.blurIn
import kotlinx.coroutines.delay
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * "Goes well with": after a buy, up to 3 kinds of thing Scout thinks go with it (completeLook), two live-priced
 * offers each. Tapping one opens its price, sizes and Buy right below (same house rules as any purchase).
 */
@Composable
fun GoesWellWith(app: AppState, purchaseId: String, modifier: Modifier = Modifier) {
    var groups by remember { mutableStateOf<List<LookGroup>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf<Offer?>(null) }
    LaunchedEffect(purchaseId) {
        runCatching { app.api.completeLook(purchaseId) }.onSuccess { groups = it }.onFailure { error = it.friendly() }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("goes well with", style = hand(22.sp, Ink.Muted))
        val g = groups
        when {
            error != null -> ErrorNote(error)
            g == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PawLoader(Ink.Ink, Modifier.size(48.dp, 18.dp))
                Text("Sniffing out what goes with it…", style = body(14.sp, color = Ink.Muted))
            }
            g.isEmpty() -> Text("Nothing I'd pair with this one.", style = body(14.sp, color = Ink.Muted))
            else -> g.forEachIndexed { i, group ->
                Column(Modifier.blurIn(i), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(group.label, style = hand(22.sp))
                    if (group.why.isNotBlank()) Text(group.why, style = body(13.sp, color = Ink.Muted))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        group.offers.forEachIndexed { j, o ->
                            LookCard(o, selected = open?.offerId == o.offerId, tilt = if ((i + j) % 2 == 0) -1.5f else 1.5f, modifier = Modifier.weight(1f)) {
                                open = if (open?.offerId == o.offerId) null else o
                            }
                        }
                        if (group.offers.size == 1) Column(Modifier.weight(1f)) {}
                    }
                    val o = open
                    AnimatedVisibility(o != null && group.offers.any { it.offerId == o.offerId }, enter = expandVertically(tween(300)) + fadeIn(tween(250)), exit = shrinkVertically(tween(200)) + fadeOut(tween(150))) {
                        if (o != null) LookBuy(app, o)
                    }
                }
            }
        }
    }
}

@Composable
private fun LookCard(o: Offer, selected: Boolean, tilt: Float, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.rotate(tilt).background(Ink.Pill).then(if (selected) Modifier.border(2.dp, Ink.Ink) else Modifier)
            .clickable(onClick = onClick).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ProductImage(o.imageUrl, Modifier.fillMaxWidth().height(110.dp))
        Text(o.title.lowercase(), style = hand(16.sp), maxLines = 2)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(money(o.price, o.currency), style = serif(17.sp), modifier = Modifier.weight(1f))
            Text(o.merchant.removePrefix("www.").take(16), style = body(11.sp, color = Ink.Grey), maxLines = 1)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalUuidApi::class)
@Composable
private fun LookBuy(app: AppState, o: Offer) {
    var size by remember(o.offerId) { mutableStateOf<String?>(null) }
    var armed by remember(o.offerId) { mutableStateOf(false) }
    LaunchedEffect(o.offerId) { armed = false; delay(800); armed = true } // no buy from a tap meant for the card
    val place = app.defaultPlace
    PaperNote(Modifier.fillMaxWidth(), tilt = 0f) {
        Text(o.title, style = body(14.sp, FontWeight.SemiBold), maxLines = 2)
        o.sizes?.takeIf { it.isNotEmpty() }?.let { sizes ->
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                sizes.forEach { z -> Chip(z.label, z.label == size, onClick = { size = z.label }) }
            }
        }
        Text("ships to ${place?.label ?: "add a place in You"} · house rules apply", style = body(13.sp, color = Ink.Muted))
        PillButton("Buy · ${money(o.price, o.currency)}", enabled = armed && place != null, onClick = {
            app.go(Screen.Buying(o, size, Uuid.random().toHexString(), place!!.placeId))
        })
    }
}
