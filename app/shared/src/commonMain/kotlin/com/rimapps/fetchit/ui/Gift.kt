package com.rimapps.fetchit.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rimapps.fetchit.AppState
import com.rimapps.fetchit.RuleFocus
import com.rimapps.fetchit.Screen
import com.rimapps.fetchit.data.Place
import com.rimapps.fetchit.ui.components.PillButton
import com.rimapps.fetchit.ui.icons.Doodle
import com.rimapps.fetchit.ui.icons.DoodleIcon

/** A gift being set up on Results: who (a saved place) and the note. */
data class GiftChoice(val placeId: String, val to: String, val note: String)

/** Who a gift is for, as the server names them: the place's label unless it's Home/Work/Other, else the first name. */
fun Place.giftName(): String = label.trim().takeIf { it.isNotEmpty() && it.lowercase() !in setOf("home", "work", "other") }
    ?: fullName.substringBefore(' ').ifBlank { "them" }

@Composable
fun GiftChip(gift: GiftChoice?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(999.dp)).background(if (gift != null) Ink.Accent else Ink.Pill)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val fg = if (gift != null) Ink.White else Ink.Ink
        DoodleIcon(Doodle.Heart, null, size = 16.dp, color = if (gift != null) Ink.White else Ink.Accent, strokeWidth = 4.4f)
        Text(if (gift != null) "gift for ${gift.to}" else "gift", style = body(14.sp, FontWeight.SemiBold, fg), maxLines = 1)
    }
}

/**
 * "Send it as a gift": pick whose place it ships to and write a note. Paid from the gift budget (house rules), never
 * the user's own sizes. Not a gift any more → [onPick] with null.
 */
@Composable
fun BoxScope.GiftSheet(app: AppState, open: Boolean, current: GiftChoice?, onPick: (GiftChoice?) -> Unit, onDismiss: () -> Unit) {
    AnimatedVisibility(open, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
        Box(Modifier.fillMaxSize().background(Ink.Ink.copy(alpha = 0.35f)).clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss))
    }
    AnimatedVisibility(
        open, modifier = Modifier.align(Alignment.BottomCenter),
        enter = slideInVertically(tween(300)) { it } + fadeIn(tween(150)), exit = slideOutVertically(tween(250)) { it } + fadeOut(tween(150)),
    ) {
        val places = app.savedPlaces
        var pick by remember(open) { mutableStateOf(current?.placeId ?: places.firstOrNull { !it.isDefault }?.placeId) }
        var note by remember(open) { mutableStateOf(current?.note ?: "") }
        val budget = app.rules?.giftBudget ?: 0.0
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)).background(Ink.Background)
                .clickable(remember { MutableInteractionSource() }, indication = null) {}
                .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 34.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).width(44.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(Ink.TrackOff))
            Text("Send it as a gift", style = hand(28.sp))
            if (budget <= 0) {
                Text("Gifts are off in your house rules. Set a gift budget first.", style = body(14.sp, color = Ink.Muted))
                PillButton("Set a gift budget", onClick = { onDismiss(); app.go(Screen.Rules(RuleFocus.Gifts)) })
                return@Column
            }
            Text("Who's it for?", style = body(14.sp, FontWeight.SemiBold, Ink.Muted))
            Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                places.forEach { p ->
                    val on = p.placeId == pick
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (on) Ink.Ink else Ink.Pill)
                            .clickable { pick = p.placeId }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(p.giftName(), style = body(16.sp, FontWeight.SemiBold, if (on) Ink.White else Ink.Ink))
                            Text("${p.fullName} · ${p.city}, ${p.state}", style = body(12.sp, color = if (on) Ink.White.copy(alpha = 0.7f) else Ink.Muted), maxLines = 1)
                        }
                    }
                }
                Text(
                    "+ add someone's address", style = body(14.sp, FontWeight.SemiBold, Ink.Muted),
                    modifier = Modifier.clickable { onDismiss(); app.go(Screen.Places) }.padding(vertical = 6.dp),
                )
            }
            Text("Note (goes with the order)", style = body(14.sp, FontWeight.SemiBold, Ink.Muted))
            Box(Modifier.fillMaxWidth().border(1.dp, Ink.NoteBorder, RoundedCornerShape(12.dp)).background(Ink.Note).padding(12.dp)) {
                if (note.isEmpty()) Text("Happy birthday!", style = hand(20.sp, Ink.LightGrey))
                BasicTextField(
                    note, { note = it.take(200) }, textStyle = hand(20.sp),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text("Comes out of your ${money(budget)} gift budget, not your monthly one. I'll ask which size.", style = body(13.sp, color = Ink.Muted))
            val chosen = places.firstOrNull { it.placeId == pick }
            PillButton(chosen?.let { "Gift it to ${it.giftName()}" } ?: "Pick someone", enabled = chosen != null, onClick = {
                onPick(GiftChoice(chosen!!.placeId, chosen.giftName(), note.trim()))
            })
            if (current != null) Text(
                "not a gift", style = body(14.sp, FontWeight.SemiBold, Ink.Muted),
                modifier = Modifier.align(Alignment.CenterHorizontally).clickable { onPick(null) }.padding(6.dp),
            )
        }
    }
}
