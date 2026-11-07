package com.rimapps.fetchit.ui

import com.rimapps.fetchit.ui.motion.riseIn
import com.rimapps.fetchit.ui.motion.PathDraw
import androidx.compose.ui.graphics.Path
import com.rimapps.fetchit.ui.motion.blurIn
import androidx.compose.ui.draw.blur
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.heightIn
import com.rimapps.fetchit.RuleFocus
import com.rimapps.fetchit.data.Reason
import com.rimapps.fetchit.platform.rememberNotificationPermission
import com.rimapps.fetchit.ui.components.grayPress
import com.rimapps.fetchit.ui.icons.Doodle
import com.rimapps.fetchit.ui.icons.DoodleIcon
import com.rimapps.fetchit.ui.motion.CountUpMoney
import com.rimapps.fetchit.ui.components.PillButton
import com.rimapps.fetchit.ui.components.Chip
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
    MainScaffold {
        Column(Modifier.fillMaxSize()) {
            Text("What I've been up to", style = hand(32.sp), modifier = Modifier.padding(start = 24.dp, top = 58.dp))
            Text(today(), style = serif(20.sp, Ink.Muted), modifier = Modifier.padding(start = 24.dp, top = 2.dp))
            Row(Modifier.padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("All", "Bought", "Waiting", "Blocked").forEach { f -> Chip(f, filter == f, onClick = { filter = f }) }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.Divider))
            if (!app.activityLoaded) {
                // Still loading: blurred placeholder notes instead of a spinner; the real cards build in sharp.
                Column(Modifier.padding(20.dp).blur(10.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    repeat(3) { i -> PlaceholderNote(if (i % 2 == 0) -0.8f else 0.8f) }
                }
            } else if (cards.isEmpty()) {
                Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Mascot(110.dp)
                    Text(if (app.activity.isEmpty()) "Nothing yet. Snap something!" else "Nothing here.", style = hand(24.sp, Ink.Muted))
                }
            } else LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                itemsIndexed(cards, key = { _, e -> e.id }) { i, e -> Box(Modifier.blurIn(i)) { ActivityCard(app, e, if (i % 2 == 0) -0.8f else 0.8f) } }
            }
        }
    }
}

/**
 * The old price gets a pen line struck through it (300 ms), then the new price rises in beside it, counting down
 * from the old one. With reduce motion both just show, the old one struck.
 */
@Composable
private fun PriceDrop(was: Double, now: Double, currency: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box {
            Text(money(was, currency), style = body(14.sp, FontWeight.SemiBold, Ink.Grey))
            PathDraw(Modifier.matchParentSize(), color = Ink.Accent, strokeWidth = 2.dp, durationMs = 300, delayMs = 250) { sz ->
                listOf(Path().apply { moveTo(-2f, sz.height * 0.6f); quadraticTo(sz.width * 0.5f, sz.height * 0.42f, sz.width + 2f, sz.height * 0.5f) })
            }
        }
        CountUpMoney(now, serif(17.sp, Ink.Ink), Modifier.riseIn(250, distance = 8.dp), currency = currency, from = was)
    }
}

/** The shape of an Activity card with gray bars for text: drawn blurred while Activity loads. */
@Composable
private fun PlaceholderNote(tilt: Float) {
    PaperNote(Modifier.fillMaxWidth(), tilt = tilt) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(72.dp).background(Ink.TrackOff))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.fillMaxWidth(0.6f).height(12.dp).background(Ink.TrackOff))
                Box(Modifier.fillMaxWidth(0.9f).height(16.dp).background(Ink.TrackOff))
                Box(Modifier.fillMaxWidth(0.75f).height(10.dp).background(Ink.TrackOff))
            }
        }
    }
}

@Composable
private fun ActivityCard(app: AppState, e: ActivityEntry, tilt: Float) {
    val label = statusLabel(e.status)
    // A watched item bought after a drop: "Bought · $59.99 → $42.99", the old price struck through.
    val dropped = e.priceWas != null && e.price != null && e.price < e.priceWas - 0.005
    val heading = if (dropped) "$label ·" else listOfNotNull(label, e.price?.let { money(it, e.currency ?: "USD") }).joinToString(" · ")
    PaperNote(Modifier.fillMaxWidth(), tilt = tilt) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (e.imageUrl != null) ProductImage(e.imageUrl, Modifier.size(72.dp).rotate(-tilt * 2))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (e.status == "needs_approval" || e.status == "blocked") Box(Modifier.padding(end = 6.dp).size(8.dp).clip(CircleShape).background(Ink.Accent))
                    if (e.status in setOf("completed", "approved", "confirmed", "shipped", "delivered")) DoodleIcon(Doodle.Check, null, Modifier.padding(end = 4.dp), size = 16.dp, color = Ink.Muted, strokeWidth = 4.6f)
                    if (dropped) Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Text(heading, style = body(14.sp, FontWeight.SemiBold, Ink.Muted))
                        PriceDrop(e.priceWas!!, e.price!!, e.currency ?: "USD", Modifier.padding(start = 6.dp))
                    } else Text(heading, style = body(14.sp, FontWeight.SemiBold, Ink.Muted), modifier = Modifier.weight(1f))
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
            e.status == "blocked" -> PillButton("Change rule", dark = false, height = 44.dp, onClick = { app.go(changeRuleTarget(e.reasons)) })
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

/** House rules, pushed from You / the budget chip / a "Change rule" link ([Screen.Rules.focus] scrolls there). */
@Composable
fun RulesScreen(app: AppState, screen: Screen.Rules) {
    val scope = rememberCoroutineScope()
    val saved = app.rules ?: HouseRules()
    var draft by remember(app.rules) { mutableStateOf(saved) }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { app.refresh() }
    Column(Modifier.fillMaxSize().dotted().padding(top = 56.dp, bottom = 34.dp)) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BackButton({ app.back() })
            Text("House rules", style = hand(30.sp))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(app.dogName, style = body(16.sp, FontWeight.SemiBold, Ink.Muted))
                Text(" only ", style = serif(18.sp, Ink.Muted))
                Text("spends inside these", style = body(16.sp, FontWeight.SemiBold, Ink.Muted))
            }
            BudgetNote(app, saved)
            RulesEditor(saved, focus = screen.focus) { draft = it }
            ErrorNote(error)
        }
        if (draft != saved || done) PillButton("Save rules", loading = busy, done = done, enabled = draft.allowedCategories.isNotEmpty(), modifier = Modifier.padding(horizontal = 20.dp), onClick = {
            busy = true; error = null
            scope.launch {
                runCatching { app.api.setRules(draft) }
                    .onSuccess { busy = false; done = true; delay(600); app.rules = it; done = false }
                    .onFailure { error = it.friendly() }
                busy = false
            }
        })
    }
}

/** This month: what's left (counting), the bar, what's spent or reserved. */
@Composable
fun BudgetNote(app: AppState, rules: HouseRules, modifier: Modifier = Modifier) {
    PaperNote(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("This month", style = body(13.sp, FontWeight.SemiBold, Ink.Muted), modifier = Modifier.weight(1f))
            CountUpMoney(rules.monthlyBudget - app.monthToDate, hand(20.sp), from = null, suffix = " left")
        }
        BudgetBar(((rules.monthlyBudget - app.monthToDate) / rules.monthlyBudget).toFloat())
        Text("${money(app.monthToDate)} spent or reserved", style = body(13.sp, color = Ink.Muted))
    }
}

/**
 * The You tab: house rules first (summary card → Rules), then PayPal, saved places, sizes, the fetcher's name,
 * notifications, privacy, help and sign out. Long-press the name row for the hidden Motion lab.
 */
@Composable
fun AccountScreen(app: AppState) {
    var confirmReset by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<String?>(null) }
    val askNotifications = rememberNotificationPermission()
    LaunchedEffect(Unit) { app.refresh() }
    MainScaffold {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 22.dp, end = 22.dp, top = 58.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("You & ${app.dogName}", style = hand(32.sp))
            // House rules first: what the fetcher may spend, one tap from changing it.
            app.rules?.let { r ->
                PaperNote(Modifier.fillMaxWidth().grayPress({ app.go(Screen.Rules()) }, shape = RoundedCornerShape(4.dp)), tilt = -0.6f) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("House rules", style = hand(26.sp), modifier = Modifier.weight(1f))
                        DoodleIcon(Doodle.Pencil, null, size = 20.dp, color = Ink.Muted)
                        Text(" edit", style = body(14.sp, FontWeight.SemiBold, Ink.Muted))
                    }
                    Row(verticalAlignment = Alignment.Bottom) {
                        CountUpMoney(r.monthlyBudget - app.monthToDate, body(28.sp, FontWeight.Medium), from = null)
                        Text(" left of ${money(r.monthlyBudget)}", style = serif(17.sp, Ink.Grey))
                    }
                    BudgetBar(((r.monthlyBudget - app.monthToDate) / r.monthlyBudget).toFloat())
                    Text(
                        listOfNotNull(
                            "auto-buy up to ${money(r.perItemLimit)}",
                            "${r.allowedCategories.size} categories",
                            if (r.askBeforeNewStore) "asks before new stores" else null,
                        ).joinToString(" · "),
                        style = body(13.sp, color = Ink.Muted),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DoodleIcon(Doodle.Gear, null, size = 20.dp, color = Ink.Muted)
                Text("settings", style = hand(22.sp, Ink.Muted))
            }
            PaperNote(Modifier.fillMaxWidth(), tilt = 0f) {
                SettingRow(Doodle.ShieldCheck, "PayPal", if (app.user.paypalLinked) "linked · ${app.user.paypalEmail ?: "sandbox"}" else "not linked") {
                    if (!app.user.paypalLinked) app.go(Screen.LinkPayPal)
                }
                SettingRow(Doodle.Camera, "Your snaps", if (app.snaps.isEmpty()) "photos you fetch, to search again" else "${app.snaps.size} on this phone · search again") { app.go(Screen.Snaps()) }
                SettingRow(Doodle.Heart, "Saved for later", if (app.saved.isEmpty()) "tap the heart on a result" else "${app.saved.size} saved") { app.go(Screen.Saved) }
                SettingRow(Doodle.MapPin, "Saved places", app.savedPlaces.joinToString(" · ") { it.label + if (it.isDefault) " (default)" else "" }.ifBlank { "none yet" }) { app.go(Screen.Places) }
                SettingRow(Doodle.Ruler, "My sizes", listOfNotNull(app.sizes.top, app.sizes.waist?.let { "waist $it" }, app.sizes.shoe, app.sizes.fit?.lowercase()?.let { "$it fit" }).joinToString(" · ").ifBlank { "not set" }) { app.go(Screen.OnbSizes) }
                SettingRow(Doodle.Dog, "${app.dogName}'s name", "rename your fetcher", onLongClick = { app.go(Screen.MotionLab) }) { app.go(Screen.Ready) }
                SettingRow(Doodle.Bell, "Notifications", "\"Needs your OK\" and price drops · tap to allow") { askNotifications() }
                SettingRow(Doodle.Lock, "Privacy", "what I keep, and what I don't") { open = if (open == "privacy") null else "privacy" }
                Expand(open == "privacy") {
                    Text(
                        "Your photos stay on this phone (Your snaps) so you can search again; they're only sent when you search, " +
                            "and the server holds them in memory just for that search (a crop of the item goes to the product search). " +
                            "I never see your PayPal password: PayPal gives me a token to pay with, kept on the server where the app can't read it. " +
                            "Your rules, places, sizes and activity are stored under your account only.",
                        style = body(13.sp, color = Ink.Muted),
                    )
                }
                SettingRow(Doodle.Help, "Help", "how fetching works") { open = if (open == "help") null else "help" }
                Expand(open == "help") {
                    Text(
                        "Snap or upload something you want. I find where to buy it and check your house rules: inside them I buy " +
                            "(with 5 seconds to undo); outside them I ask for your fingerprint; against a hard rule I don't buy. " +
                            "This is a sandbox build: PayPal sandbox money, a demo store, no real orders.",
                        style = body(13.sp, color = Ink.Muted),
                    )
                }
                SettingRow(Doodle.LogOut, if (confirmReset) "Tap again to sign out" else "Sign out", "start over on this phone") {
                    if (confirmReset) app.signOut() else confirmReset = true
                }
            }
            Text("Fetch It · sandbox build 0.1 · PayPal sandbox, demo store", style = body(12.sp, color = Ink.Grey), modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SettingRow(icon: Doodle, title: String, sub: String, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(12.dp))
            .combinedClickable(onLongClick = onLongClick, onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        DoodleIcon(icon, null, size = 26.dp)
        Column(Modifier.weight(1f)) {
            Text(title, style = body(16.sp, FontWeight.SemiBold))
            Text(sub, style = body(13.sp, color = Ink.Muted), maxLines = 2)
        }
        Text("›", style = body(20.sp, color = Ink.LightGrey))
    }
}

@Composable
private fun Expand(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(visible, enter = expandVertically(tween(250)) + fadeIn(tween(250)), exit = shrinkVertically(tween(200)) + fadeOut(tween(150))) {
        Box(Modifier.padding(start = 40.dp, bottom = 6.dp)) { content() }
    }
}

/** Where a "Change rule" link goes: the place list for a ship-to problem, else the matching rules section. */
fun changeRuleTarget(reasons: List<Reason>): Screen {
    val failing = reasons.filter { !it.pass }.map { it.rule }
    return if ("saved_place" in failing) Screen.Places else Screen.Rules(failing.firstNotNullOfOrNull { RuleFocus.forRule(it) })
}
