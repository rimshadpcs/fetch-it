package com.rimapps.fetchit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rimapps.fetchit.AppState
import com.rimapps.fetchit.Screen
import com.rimapps.fetchit.data.ActivityEntry
import com.rimapps.fetchit.data.HouseRules
import com.rimapps.fetchit.friendly
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

fun statusLabel(status: String) = LABEL[status] ?: status.replace('_', ' ').replaceFirstChar { it.uppercase() }

private val LABEL = mapOf(
    "completed" to "Bought", "pending_undo" to "Buying", "approved" to "Paying", "charging" to "Paying",
    "needs_approval" to "Needs your OK", "blocked" to "Blocked", "declined" to "Declined", "undone" to "Undone",
    "failed" to "Failed", "refunded" to "Refunded", "placed" to "Ordered", "confirmed" to "Confirmed",
    "shipped" to "Shipped", "delivered" to "Delivered", "cancelled" to "Cancelled", "linked" to "PayPal linked",
    "watching" to "Watching", "triggered" to "Price dropped", "cancelling" to "Cancelling", "cancel_failed" to "Couldn't cancel",
)

private fun group(e: ActivityEntry) = when (e.status) {
    "completed", "placed", "confirmed", "shipped", "delivered", "refunded", "cancelled", "approved", "charging" -> "Bought"
    "needs_approval" -> "Waiting"
    "blocked", "declined", "failed", "undone" -> "Blocked"
    else -> "Other"
}

/**
 * One card per purchase (its latest entry: purchase → order placed → shipped …), plus PayPal/watch entries.
 * Activity is written by Functions; we poll while the screen is open so order progress shows up.
 */
@Composable
fun ActivityScreen(app: AppState) {
    var filter by remember { mutableStateOf("All") }
    LaunchedEffect(Unit) {
        while (true) { app.refreshActivity(); delay(10_000) }
    }
    val cards = app.activity.distinctBy { it.purchaseId ?: it.id }.filter { filter == "All" || group(it) == filter }
    MainScaffold(app) {
        Column(Modifier.fillMaxSize()) {
            Text("What I've been up to", style = hand(32.sp), modifier = Modifier.padding(start = 24.dp, top = 58.dp))
            Text(today(), style = serif(20.sp, Ink.Muted), modifier = Modifier.padding(start = 24.dp, top = 2.dp))
            Row(Modifier.padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("All", "Bought", "Waiting", "Blocked").forEach { f -> Chip(f, filter == f, onClick = { filter = f }) }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.Divider))
            if (cards.isEmpty()) {
                Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Mascot(110.dp)
                    Text(if (app.activity.isEmpty()) "Nothing yet. Snap something!" else "Nothing here.", style = hand(24.sp, Ink.Muted))
                }
            } else LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                itemsIndexed(cards, key = { _, e -> e.id }) { i, e -> RiseIn(i.coerceAtMost(6)) { ActivityCard(app, e, if (i % 2 == 0) -0.8f else 0.8f) } }
            }
        }
    }
}

@Composable
private fun ActivityCard(app: AppState, e: ActivityEntry, tilt: Float) {
    val label = statusLabel(e.status)
    val heading = listOfNotNull(label, e.price?.let { money(it, e.currency ?: "USD") }).joinToString(" · ")
    PaperNote(Modifier.fillMaxWidth(), tilt = tilt) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (e.imageUrl != null) ProductImage(e.imageUrl, Modifier.size(72.dp).rotate(-tilt * 2))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (e.status == "needs_approval" || e.status == "blocked") Box(Modifier.padding(end = 6.dp).size(8.dp).clip(CircleShape).background(Ink.Accent))
                    Text(heading, style = body(14.sp, FontWeight.SemiBold, Ink.Muted), modifier = Modifier.weight(1f))
                    Text(timeAgo(e.createdAt), style = serif(15.sp, Ink.Muted))
                }
                e.title?.let { Text(it.lowercase(), style = hand(19.sp), maxLines = 2) }
                val why = e.reasons.filter { !it.pass }.joinToString(". ") { it.message }
                Text(if (why.isNotBlank() && e.status != "completed") "$why. ${e.summary}" else e.summary, style = body(13.sp, color = Ink.Muted))
                if (e.retailer != null && e.type != "paypal") Text("${e.retailer} · demo store · sandbox", style = body(11.sp, color = Ink.Grey))
            }
        }
        when {
            e.status == "needs_approval" -> PillButton("Review", height = 44.dp, onClick = { app.go(Screen.Approve(e.purchaseId!!)) })
            e.status == "blocked" -> PillButton("Edit rules", dark = false, height = 44.dp, onClick = { app.reset(Screen.Rules) })
            // Paid: the order (and, until it ships, Cancel order).
            e.purchaseId != null && (e.type == "order" || e.status == "completed") -> PillButton(
                if (e.status in setOf("completed", "placed", "confirmed")) "Order · cancel" else "Order details",
                dark = false, height = 44.dp, onClick = { app.go(Screen.Order(e.purchaseId)) },
            )
        }
    }
}

@OptIn(ExperimentalTime::class)
private fun today(): String {
    val d = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    val day = d.day
    val suffix = if (day in 11..13) "th" else when (day % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }
    return "$day$suffix ${d.month.name.lowercase().replaceFirstChar { it.uppercase() }}"
}

@OptIn(ExperimentalTime::class)
internal fun timeAgo(millis: Long): String {
    if (millis == 0L) return ""
    val tz = TimeZone.currentSystemDefault()
    val then = Instant.fromEpochMilliseconds(millis).toLocalDateTime(tz)
    val now = Clock.System.now().toLocalDateTime(tz)
    val minutes = (Clock.System.now().toEpochMilliseconds() - millis) / 60_000
    return when {
        minutes < 1 -> "just now"
        then.date == now.date -> {
            val h = then.hour % 12
            "${if (h == 0) 12 else h}:${then.minute.toString().padStart(2, '0')} ${if (then.hour < 12) "AM" else "PM"}"
        }
        minutes < 48 * 60 -> "yesterday"
        else -> "${then.month.name.take(3).lowercase()} ${then.day}"
    }
}

@Composable
fun RulesScreen(app: AppState) {
    val scope = rememberCoroutineScope()
    val saved = app.rules ?: HouseRules()
    var draft by remember(app.rules) { mutableStateOf(saved) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { app.refresh() }
    MainScaffold(app) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 58.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("House rules", style = hand(34.sp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(app.dogName, style = body(16.sp, FontWeight.SemiBold, Ink.Muted))
                    Text(" only ", style = serif(18.sp, Ink.Muted))
                    Text("spends inside these", style = body(16.sp, FontWeight.SemiBold, Ink.Muted))
                }
                PaperNote(Modifier.fillMaxWidth()) {
                    Row { Text("This month", style = body(13.sp, FontWeight.SemiBold, Ink.Muted), modifier = Modifier.weight(1f)); Text("${money(saved.monthlyBudget - app.monthToDate)} left", style = hand(20.sp)) }
                    BudgetBar(((saved.monthlyBudget - app.monthToDate) / saved.monthlyBudget).toFloat())
                    Text("${money(app.monthToDate)} spent or reserved", style = body(13.sp, color = Ink.Muted))
                }
                RulesEditor(saved) { draft = it }
                ErrorNote(error)
            }
            if (draft != saved) PillButton("Save rules", loading = busy, enabled = draft.allowedCategories.isNotEmpty(), modifier = Modifier.padding(horizontal = 20.dp), onClick = {
                busy = true; error = null
                scope.launch {
                    runCatching { app.api.setRules(draft) }.onSuccess { app.rules = it }.onFailure { error = it.friendly() }
                    busy = false
                }
            })
        }
    }
}

@Composable
fun AccountScreen(app: AppState) {
    var confirmReset by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().dotted().verticalScroll(rememberScrollState()).padding(top = 56.dp, bottom = 34.dp)) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BackButton({ app.back() })
            Text("You & ${app.dogName}", style = hand(30.sp))
        }
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(Modifier.clickable { app.go(Screen.Ready) }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Mascot(80.dp)
                Column {
                    Text(app.dogName, style = hand(30.sp))
                    Text("rename", style = serif(16.sp, Ink.Muted))
                    Text(app.user.paypalEmail ?: "PayPal not linked", style = body(14.sp, color = Ink.Muted))
                }
            }
            Text("money & delivery", style = hand(22.sp, Ink.Muted))
            PaperNote(Modifier.fillMaxWidth(), tilt = 0f) {
                Row2("PayPal", if (app.user.paypalLinked) "linked · sandbox" else "not linked") { if (!app.user.paypalLinked) app.go(Screen.LinkPayPal) }
                Row2("Saved places", app.places.filter { !it.suggested }.joinToString(" · ") { "${it.label} · ${it.line1}" }.ifBlank { "none yet" }) { app.go(Screen.LinkPayPal) }
                Row2("My sizes", listOfNotNull(app.sizes.top, app.sizes.waist?.let { "waist $it" }, app.sizes.shoe, app.sizes.fit?.lowercase()?.let { "$it fit" }).joinToString(" · ").ifBlank { "not set" }) { app.go(Screen.OnbSizes) }
                Row2("House rules", app.rules?.let { "${money(it.monthlyBudget)} a month · auto-buy up to ${money(it.perItemLimit)}" } ?: "not set") { app.reset(Screen.Rules) }
            }
            PillButton(if (confirmReset) "Tap again to start over" else "Start over", dark = false, height = 52.dp, onClick = {
                if (confirmReset) app.signOut() else confirmReset = true
            })
            Text("Fetch It · sandbox build 0.1 · PayPal sandbox, demo store", style = body(12.sp, color = Ink.Grey), modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

@Composable
private fun Row2(title: String, sub: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp)) {
        Text(title, style = body(16.sp, FontWeight.SemiBold))
        Text(sub, style = body(13.sp, color = Ink.Muted))
    }
}
