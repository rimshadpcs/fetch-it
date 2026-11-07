package com.rimapps.fetchit.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rimapps.fetchit.AppState
import com.rimapps.fetchit.Screen
import com.rimapps.fetchit.data.Offer
import com.rimapps.fetchit.data.SavedItem
import com.rimapps.fetchit.friendly
import com.rimapps.fetchit.ui.components.Chip
import com.rimapps.fetchit.ui.components.DashedSpinner
import com.rimapps.fetchit.ui.components.PillButton
import com.rimapps.fetchit.ui.motion.blurIn
import kotlinx.coroutines.delay
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * You → Saved: everything hearted, newest first. Tap one to open its buy panel (live price, sizes, ship-to);
 * the heart un-saves it.
 */
@Composable
fun SavedScreen(app: AppState) {
    var open by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    var placeId by remember { mutableStateOf(app.defaultPlace?.placeId) }
    // Watch price from a saved thing: the sheet takes the live offer (and size) its buy panel loaded.
    var watching by remember { mutableStateOf<Pair<Offer, String?>?>(null) }
    LaunchedEffect(Unit) { runCatching { app.saved = app.api.saved() } }
    Box(Modifier.fillMaxSize().dotted()) {
        Column(Modifier.fillMaxSize().padding(top = 56.dp, bottom = 34.dp)) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BackButton({ app.back() })
                Text("Saved for later", style = hand(30.sp))
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (app.saved.isEmpty()) Text("Nothing saved yet. Tap the heart on a result to keep it here.", style = hand(22.sp, Ink.Muted))
                app.saved.forEachIndexed { i, s ->
                    Column(Modifier.blurIn(i), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SavedNote(s, if (i % 2 == 0) -0.8f else 0.8f, onOpen = { open = if (open == s.id) null else s.id }) {
                            app.saved = app.saved.filterNot { it.id == s.id }
                            app.toggleSavedById(s)
                        }
                        AnimatedVisibility(open == s.id, enter = expandVertically(tween(300)) + fadeIn(tween(250)), exit = shrinkVertically(tween(200)) + fadeOut(tween(150))) {
                            BuyPanel(app, s, app.savedPlaces.firstOrNull { it.placeId == placeId } ?: app.defaultPlace, onPickPlace = { picking = true }, onWatch = { o, z -> watching = o to z })
                        }
                    }
                }
            }
        }
        PlacePickerSheet(app, picking, placeId, onPick = { placeId = it.placeId; picking = false }, onDismiss = { picking = false })
        WatchSheet(app, watching != null, watching?.first, watching?.second, placeId, onDismiss = { watching = null })
    }
}

@Composable
private fun SavedNote(s: SavedItem, tilt: Float, onOpen: () -> Unit, onUnsave: () -> Unit) {
    PaperNote(Modifier.fillMaxWidth().clickable(onClick = onOpen), tilt = tilt) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            ProductImage(s.imageUrl, Modifier.size(72.dp))
            Column(Modifier.weight(1f)) {
                Text(s.title.lowercase(), style = hand(19.sp), maxLines = 2)
                Text(s.merchant.removePrefix("www."), style = body(13.sp, FontWeight.SemiBold, Ink.Muted))
                Text(money(s.price, s.currency), style = serif(17.sp))
            }
            HeartButton(true, onUnsave)
        }
    }
}

/** The saved thing at its live price (savedOffer), sizes if it has them, where it ships, and Buy. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalUuidApi::class)
@Composable
private fun BuyPanel(app: AppState, s: SavedItem, place: com.rimapps.fetchit.data.Place?, onPickPlace: () -> Unit, onWatch: (Offer, String?) -> Unit) {
    var offer by remember { mutableStateOf<Offer?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var size by remember { mutableStateOf<String?>(null) }
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(s.id) {
        runCatching { app.api.savedOffer(s.id) }.onSuccess { offer = it }.onFailure { error = it.friendly() }
        delay(800); armed = true // same guard as Buy on Results: no buy from a tap meant for the row above
    }
    PaperNote(Modifier.fillMaxWidth(), tilt = 0f) {
        val o = offer
        when {
            error != null -> ErrorNote(error)
            o == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DashedSpinner(Ink.Ink, Modifier.size(20.dp)); Text("Checking today's price…", style = body(14.sp, color = Ink.Muted))
            }
            o.availability == "OutOfStock" -> Text("Not available at ${o.merchant.removePrefix("www.")} right now.", style = hand(20.sp, Ink.Warning))
            else -> {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(money(o.price, o.currency), style = body(28.sp, FontWeight.Medium))
                    if (s.price != null && o.price != s.price) Text("  was ${money(s.price, s.currency)}", style = serif(16.sp, Ink.Grey))
                }
                o.sizes?.takeIf { it.isNotEmpty() }?.let { sizes ->
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        sizes.forEach { z -> Chip(z.label, z.label == size, onClick = { size = z.label }) }
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "ship to ${place?.label ?: "add a place"} ▾", style = body(14.sp, FontWeight.SemiBold, Ink.Muted),
                        modifier = Modifier.weight(1f).clickable(onClick = onPickPlace).padding(vertical = 6.dp),
                    )
                    WatchChip(app.watchFor(o), onClick = { onWatch(o, size) })
                }
                PillButton("Buy · ${money(o.price, o.currency)}", enabled = armed && place != null, onClick = {
                    app.go(Screen.Buying(o, size, Uuid.random().toHexString(), place!!.placeId))
                })
            }
        }
    }
}
