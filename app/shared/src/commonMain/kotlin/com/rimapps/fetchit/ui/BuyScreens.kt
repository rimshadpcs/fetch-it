package com.rimapps.fetchit.ui

import com.rimapps.fetchit.ui.components.Toggle
import androidx.compose.foundation.clickable
import com.rimapps.fetchit.ui.icons.DoodleIcon
import com.rimapps.fetchit.ui.icons.Doodle
import com.rimapps.fetchit.ui.components.PillButton
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rimapps.fetchit.AppState
import com.rimapps.fetchit.Screen
import com.rimapps.fetchit.data.Purchase
import com.rimapps.fetchit.friendly
import com.rimapps.fetchit.platform.PushOpens
import com.rimapps.fetchit.platform.rememberApprover
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** purchaseOffer: the decision streams in first; an auto-buy shows Undo for its 5 s window, then the payment result. */
@Composable
fun BuyingScreen(app: AppState, screen: Screen.Buying) {
    var purchase by remember { mutableStateOf<Purchase?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(screen.requestId) {
        try {
            // Re-entering this screen joins the same call instead of starting another (AppState.buy).
            val final = app.buy(screen.offer, screen.placeId, screen.size, screen.requestId, screen.gift) { decision ->
                if (decision.status == "needs_approval") app.replace(Screen.Approve(decision.purchaseId)) else purchase = decision
            }.await()
            if (final.status != "needs_approval") purchase = final
        } catch (e: Exception) {
            error = e.friendly()
        }
    }
    val p = purchase
    when {
        error != null -> Outcome(app, null, title = "Hmm.", note = error!!)
        p == null -> Checking(app, screen.offer.title)
        else -> PurchaseView(app, p) { purchase = it }
    }
}

@Composable
fun DoneScreen(app: AppState, screen: Screen.Done) {
    val p = app.purchases.value[screen.purchaseId] ?: app.purchase(screen.purchaseId) ?: return
    PurchaseView(app, p) { app.remember(it) }
}

@Composable
private fun Checking(app: AppState, title: String) {
    Column(Modifier.fillMaxSize().dotted().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Mascot(140.dp)
        Gap(18.dp)
        Text("Checking your house rules…", style = hand(30.sp), textAlign = TextAlign.Center)
        Text(title, style = body(15.sp, color = Ink.Muted), textAlign = TextAlign.Center, maxLines = 2)
    }
}

@Composable
private fun PurchaseView(app: AppState, p: Purchase, onUpdate: (Purchase) -> Unit) {
    when (p.status) {
        "pending_undo", "approved", "charging" -> Paying(app, p, onUpdate)
        "completed" -> Bought(app, p)
        "blocked" -> Outcome(
            app, p, title = "Not this one.", note = "Blocked by your house rules. Nothing charged.",
            secondary = "Change rule" to { app.go(changeRuleTarget(p.reasons)) },
        )
        "undone" -> Outcome(app, p, title = "Undone.", note = "Nothing charged.")
        "declined" -> Outcome(app, p, title = "Okay, skipped.", note = "Nothing charged.")
        "failed" -> Outcome(app, p, title = "Payment didn't go through.", note = p.error ?: "Nothing charged.")
        "needs_approval" -> LaunchedEffect(p.purchaseId) { app.replace(Screen.Approve(p.purchaseId)) }
        else -> Outcome(app, p, title = p.status, note = p.summary)
    }
}

/** Auto-buy inside the undo window (countdown + Undo), then "Paying with PayPal…". */
@OptIn(ExperimentalTime::class)
@Composable
private fun Paying(app: AppState, p: Purchase, onUpdate: (Purchase) -> Unit) {
    val scope = rememberCoroutineScope()
    var secondsLeft by remember { mutableStateOf(0) }
    var undoing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(p.undoUntil) {
        while (true) {
            val left = ((p.undoUntil ?: 0) - Clock.System.now().toEpochMilliseconds())
            secondsLeft = ((left + 999) / 1000).toInt().coerceAtLeast(0)
            if (left <= 0) break
            delay(200)
        }
    }
    val canUndo = p.status == "pending_undo" && secondsLeft > 0
    Column(Modifier.fillMaxSize().dotted().padding(bottom = 34.dp)) {
        Gap(80.dp)
        Polaroid(p.imageUrl, if (canUndo) "fetching…" else "paying…", Modifier.width(214.dp).align(Alignment.CenterHorizontally), imageHeight = 190.dp)
        Gap(28.dp)
        Text(
            if (canUndo) "Buying in $secondsLeft…" else "Paying with PayPal…",
            style = body(40.sp, FontWeight.Medium).copy(letterSpacing = (-1.5).sp), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
        Text("${p.title} · ${money(p.price, p.currency)}", style = body(15.sp, color = Ink.NavGrey), textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp))
        RulesPassed(p, Modifier.padding(horizontal = 22.dp))
        Spacer()
        ErrorNote(error, Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        if (canUndo) PillButton("Undo", loading = undoing, modifier = Modifier.padding(horizontal = 20.dp), onClick = {
            undoing = true
            scope.launch {
                runCatching { app.api.undoPurchase(p.purchaseId) }
                    .onSuccess { app.remember(it); onUpdate(it); app.refresh() }
                    .onFailure { error = it.friendly() }
                undoing = false
            }
        })
    }
}

@Composable
private fun RulesPassed(p: Purchase, modifier: Modifier = Modifier) {
    val passed = p.reasons.count { it.pass }
    PaperNote(modifier.fillMaxWidth()) {
        p.reasons.filter { it.pass }.take(4).forEach { Text("✓  ${it.message}", style = body(14.sp, color = Ink.Muted)) }
        Text("no approval needed, it passed all $passed rules", style = hand(18.sp))
    }
}

@Composable
private fun Bought(app: AppState, p: Purchase) {
    var arrives by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(p.purchaseId) {
        // This purchase is already in spend/{month} (reserved in the decision, kept on capture).
        runCatching { app.monthToDate = app.api.monthToDate() }
        // The simulated order appears in the same transaction as the capture; retry briefly in case we're early.
        repeat(3) { if (arrives == null) { arrives = runCatching { app.api.promisedDelivery(p.purchaseId) }.getOrNull()?.let(::weekday); delay(800) } }
    }
    Column(Modifier.fillMaxSize().dotted().padding(bottom = 34.dp)) {
        Text("Fetch It", style = body(22.sp, color = Ink.LightGrey), modifier = Modifier.align(Alignment.End).padding(top = 58.dp, end = 26.dp))
        Box(Modifier.fillMaxWidth().height(320.dp)) {
            Polaroid(p.imageUrl, "fetched!", Modifier.width(214.dp).align(Alignment.Center))
            DoodleIcon(Doodle.Heart, "Fetched", Modifier.align(Alignment.Center).offset(92.dp, (-118).dp).rotate(12f), size = 40.dp, color = Ink.Accent, strokeWidth = 4.4f)
            Mascot(80.dp, Modifier.align(Alignment.BottomEnd).offset((-30).dp, 0.dp))
        }
        Text(
            buildAnnotatedString {
                append("Bought. ")
                withStyle(SpanStyle(fontFamily = Fonts.serif, fontStyle = FontStyle.Italic, fontWeight = FontWeight.Normal, fontSize = 50.sp)) { append("Arrives") }
                append("\n${arrives ?: "soon"}.")
            },
            style = body(44.sp, FontWeight.Medium).copy(lineHeight = 46.sp, letterSpacing = (-1.6).sp), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "${p.title.take(40)} · ${p.retailer ?: p.merchant} · paid with PayPal",
            style = body(14.sp, color = Ink.NavGrey), textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
        )
        p.gift?.let { g ->
            Text(
                "a gift for ${g.to}" + (g.note?.let { " · \u201c$it\u201d" } ?: ""), style = hand(20.sp, Ink.Accent),
                textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            )
        }
        Text("Demo store · sandbox", style = hand(17.sp, Ink.Muted), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Text(
            "what goes with it? ›", style = hand(20.sp), textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clickable { app.go(Screen.Order(p.purchaseId)) }.padding(vertical = 6.dp),
        )
        app.rules?.let { r ->
            val left = r.monthlyBudget - app.monthToDate
            PaperNote(Modifier.padding(horizontal = 22.dp, vertical = 16.dp).fillMaxWidth()) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("Budget left", style = body(13.sp, FontWeight.SemiBold, Ink.Muted), modifier = Modifier.weight(1f))
                    Text(money(left), style = body(26.sp, FontWeight.Medium))
                    Text(" of ${money(r.monthlyBudget)}", style = serif(18.sp, Ink.Grey))
                }
                BudgetBar((left / r.monthlyBudget).toFloat())
            }
        }
        Spacer()
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("See it in activity", onClick = { app.session = null; app.reset(Screen.Activity) })
            PillButton("Fetch something else", dark = false, height = 52.dp, onClick = { app.session = null; app.reset(Screen.Fetch) })
        }
    }
}

@Composable
private fun Outcome(app: AppState, p: Purchase?, title: String, note: String, secondary: Pair<String, () -> Unit>? = null) {
    Column(Modifier.fillMaxSize().dotted().padding(top = 80.dp, bottom = 34.dp)) {
        if (p != null) Polaroid(p.imageUrl, null, Modifier.width(170.dp).align(Alignment.CenterHorizontally).alpha(0.8f), tilt = 4f, imageHeight = 140.dp)
        else Mascot(130.dp, Modifier.align(Alignment.CenterHorizontally))
        Gap(26.dp)
        Text(title, style = hand(36.sp), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp))
        Text(note, style = body(16.sp, color = Ink.Muted), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(24.dp, 8.dp))
        if (p != null && p.reasons.any { !it.pass }) ReasonsNote(p, Modifier.padding(horizontal = 22.dp, vertical = 10.dp))
        Spacer()
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (secondary != null) PillButton(secondary.first, onClick = secondary.second)
            PillButton("Fetch something else", dark = secondary == null, height = if (secondary == null) 58.dp else 52.dp, onClick = { app.session = null; app.reset(Screen.Fetch) })
        }
    }
}

/** Failing checks first (red dot), then what passed (black dot). */
@Composable
fun ReasonsNote(p: Purchase, modifier: Modifier = Modifier, extra: String? = null) {
    PaperNote(modifier.fillMaxWidth(), tilt = 0.6f) {
        (p.reasons.filter { !it.pass } + p.reasons.filter { it.pass }.take(2)).forEach { r ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (r.pass) Ink.Ink else Ink.Accent))
                Text(r.message, style = body(15.sp, if (r.pass) FontWeight.Normal else FontWeight.SemiBold))
            }
        }
        if (extra != null) Text(extra, style = hand(19.sp))
    }
}

/** Needs your OK: approve with fingerprint (then approvePurchase re-checks the live price and charges), or decline. */
@Composable
fun ApproveScreen(app: AppState, screen: Screen.Approve) {
    val scope = rememberCoroutineScope()
    val approver = rememberApprover()
    val p = app.purchase(screen.purchaseId) ?: return
    DisposableEffect(p.purchaseId) {
        PushOpens.onScreen.value = p.purchaseId
        onDispose { if (PushOpens.onScreen.value == p.purchaseId) PushOpens.onScreen.value = null }
    }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var allowCategory by remember { mutableStateOf(false) }
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(1f, 1.04f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "s")

    fun run(kind: String, call: suspend () -> Purchase) {
        busy = kind; error = null
        scope.launch {
            runCatching { call() }
                .onSuccess { app.remember(it); app.refresh(); if (it.status == "needs_approval") error = it.reasons.firstOrNull { r -> !r.pass }?.message else app.replace(Screen.Done(it.purchaseId)) }
                .onFailure { error = it.friendly() }
            busy = null
        }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFFCFCFCB))) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Polaroid(p.imageUrl, null, Modifier.width(150.dp).alpha(0.8f), tilt = 5f, imageHeight = 130.dp)
        }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp)).background(Ink.Background)
                .padding(start = 22.dp, end = 22.dp, top = 12.dp, bottom = 34.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).width(44.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFFD6D6D2)))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Just checking…", style = hand(30.sp), modifier = Modifier.weight(1f))
                Text("your call", style = serif(16.sp, Ink.Muted))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                ProductImage(p.imageUrl, Modifier.size(120.dp).rotate(-2f))
                Column {
                    Text(p.title, style = body(14.sp, FontWeight.SemiBold, Ink.Muted), maxLines = 2)
                    Text(money(p.price, p.currency), style = body(50.sp, FontWeight.Medium).copy(letterSpacing = (-2).sp))
                    Text(p.retailer ?: p.merchant, style = serif(17.sp, Ink.Muted))
                }
            }
            ReasonsNote(p, extra = "${app.dogName} found it, but it's outside the rules you set.")
            // Outside the categories: approve this one, and optionally allow the category from now on.
            p.reasons.firstOrNull { it.rule == "category" && !it.pass }?.let { r ->
                Toggle("Always allow ${r.message.substringBefore(" isn't in your allowed categories")}", allowCategory) { allowCategory = it }
            }
            Text(
                "Change rule ›", style = body(14.sp, FontWeight.SemiBold, Ink.Muted),
                modifier = Modifier.align(Alignment.End).clip(RoundedCornerShape(999.dp)).clickable { app.go(changeRuleTarget(p.reasons)) }.padding(horizontal = 10.dp, vertical = 8.dp),
            )
            ErrorNote(error)
            PillButton("Approve with fingerprint", loading = busy == "approve", modifier = Modifier.scale(if (busy == null) pulse else 1f), onClick = {
                approver.approve("Approve ${money(p.price, p.currency)}", p.title) { ok ->
                    if (ok) run("approve") { app.api.approvePurchase(p.purchaseId, allowCategory).also { if (allowCategory) app.refresh() } }
                }
            })
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Not now", dark = false, height = 50.dp, loading = busy == "decline", modifier = Modifier.weight(1f), onClick = {
                    run("decline") { app.api.undoPurchase(p.purchaseId) }
                })
                PillButton("Later", dark = false, height = 50.dp, modifier = Modifier.weight(1f), onClick = { app.session = null; app.reset(Screen.Activity) })
            }
        }
    }
}

/** "2026-10-30" → "Friday". */
fun weekday(date: String): String? = runCatching {
    LocalDate.parse(date).dayOfWeek.let { d -> d.name.lowercase().replaceFirstChar { it.uppercase() } }
}.getOrNull()
