package com.rimapps.fetchit.ui

import com.rimapps.fetchit.ui.components.grayPress
import com.rimapps.fetchit.ui.components.WarningNote
import com.rimapps.fetchit.ui.components.DrumPicker
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.clickable
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.rotate
import com.rimapps.fetchit.ui.motion.rememberHaptics
import com.rimapps.fetchit.ui.motion.doodleArrow
import androidx.compose.ui.platform.LocalDensity
import com.rimapps.fetchit.ui.motion.sharedShape
import com.rimapps.fetchit.ui.motion.ScoutPeek
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rimapps.fetchit.AppState
import com.rimapps.fetchit.RuleFocus
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.rimapps.fetchit.ui.motion.inkStroke
import fetchit.shared.generated.resources.Res
import fetchit.shared.generated.resources.welcome_lamp
import fetchit.shared.generated.resources.welcome_polo
import com.rimapps.fetchit.Screen
import com.rimapps.fetchit.data.HouseRules
import com.rimapps.fetchit.data.Place
import com.rimapps.fetchit.data.PlaceForm
import com.rimapps.fetchit.data.ShopCategory
import com.rimapps.fetchit.friendly
import com.rimapps.fetchit.platform.DeepLinks
import com.rimapps.fetchit.platform.openBrowser
import com.rimapps.fetchit.ui.components.AmountField
import com.rimapps.fetchit.ui.components.CheckMark
import com.rimapps.fetchit.ui.components.Chip
import com.rimapps.fetchit.ui.components.PillButton
import com.rimapps.fetchit.ui.components.Segmented
import com.rimapps.fetchit.ui.components.SizePicker
import com.rimapps.fetchit.ui.components.Toggle
import com.rimapps.fetchit.ui.motion.Dog
import com.rimapps.fetchit.ui.motion.DogMood
import com.rimapps.fetchit.ui.motion.LocalReduceMotion
import com.rimapps.fetchit.ui.motion.PathDraw
import com.rimapps.fetchit.ui.motion.RunningDog
import com.rimapps.fetchit.ui.motion.Stamp
import com.rimapps.fetchit.ui.motion.Tape
import com.rimapps.fetchit.ui.motion.noteSlideIn
import com.rimapps.fetchit.ui.motion.pinIn
import com.rimapps.fetchit.ui.motion.popIn
import com.rimapps.fetchit.ui.motion.rememberProgress
import com.rimapps.fetchit.ui.motion.riseIn
import com.rimapps.fetchit.ui.motion.uiSpring
import com.rimapps.fetchit.ui.motion.wiggleAfterIdle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** After a primary action's ✓ has drawn, move on. */
private const val DONE_PAUSE_MS = 350L

/** Welcome: polaroids drop in and get taped down, the headline writes on word by word, Scout wags, the button rises. */
@Composable
fun WelcomeScreen(app: AppState) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Scout peeks up from the bottom edge, glances around, then looks up at the button.
    var lookUp by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(2600); lookUp = true }
    Box(Modifier.fillMaxSize().dotted()) {
    ScoutPeek(230.dp, Modifier.align(Alignment.BottomCenter), showing = 0.5f, lookAt = if (lookUp) Offset(0.15f, -1f) else null, delayMs = 500)
    Column(Modifier.fillMaxSize().padding(bottom = 92.dp)) {
        Text("Fetch It", style = body(22.sp, color = Ink.LightGrey), modifier = Modifier.align(Alignment.End).padding(top = 58.dp, end = 26.dp))
        Box(Modifier.fillMaxWidth().height(340.dp)) {
            Box(Modifier.offset(36.dp, 30.dp).pinIn(0, tilt = 0f)) {
                Polaroid(null, "mushroom lamp", Modifier.width(190.dp), tilt = -6f, imageHeight = 150.dp, image = Res.drawable.welcome_lamp)
                Tape(Modifier.align(Alignment.TopCenter).offset(y = (-6).dp), rotation = -10f, delayMs = 380)
            }
            Box(Modifier.align(Alignment.TopEnd).offset((-30).dp, 120.dp).pinIn(2, tilt = 0f)) {
                Polaroid(null, "striped polo", Modifier.width(170.dp), tilt = 5f, imageHeight = 130.dp, image = Res.drawable.welcome_polo)
                Tape(Modifier.align(Alignment.TopCenter).offset(y = (-4).dp), rotation = 8f, delayMs = 520)
            }
            // A pen arrow from the lamp polaroid down to where it was spotted.
            val d = LocalDensity.current
            PathDraw(Modifier.matchParentSize(), strokeWidth = 2.dp, durationMs = 380, delayMs = 900) {
                with(d) { doodleArrow(Offset(92.dp.toPx(), 262.dp.toPx()), Offset(58.dp.toPx(), 300.dp.toPx()), bend = -0.35f, head = 8.dp.toPx()) }
            }
            Text("spotted at a café", style = hand(21.sp, Ink.Muted), modifier = Modifier.align(Alignment.BottomStart).offset(24.dp, (-2).dp).rotate(-3f).riseIn(1150, distance = 6.dp))
        }
        // The headline on two strips of paper tape, slapped on one after the other.
        val big = body(40.sp, FontWeight.Medium).copy(letterSpacing = (-1.4).sp)
        val accent = big.copy(fontFamily = Fonts.serif, fontStyle = FontStyle.Italic, fontWeight = FontWeight.Normal, fontSize = 46.sp)
        Column(Modifier.padding(horizontal = 18.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TapeStrip(rotation = -2.5f, delayMs = 450) { Text("You spot it.", style = big) }
            TapeStrip(Modifier.padding(start = 34.dp), rotation = 1.8f, delayMs = 750) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("We ", style = big, modifier = Modifier.alignByBaseline())
                    Text("fetch", style = accent, modifier = Modifier.alignByBaseline())
                    Text(" it.", style = big, modifier = Modifier.alignByBaseline())
                }
            }
        }
        Text(
            "Snap it, and your agent finds it, checks your rules, and pays with PayPal.",
            style = body(16.sp, color = Ink.NavGrey), modifier = Modifier.padding(horizontal = 26.dp).riseIn(900),
        )
        Spacer()
        ErrorNote(error, Modifier.padding(horizontal = 26.dp, vertical = 8.dp))
        PillButton("Get started", loading = busy, modifier = Modifier.padding(horizontal = 20.dp).riseIn(1000, distance = 40.dp), onClick = {
            busy = true; error = null
            scope.launch {
                runCatching { app.ensureSignedIn() }
                    .onSuccess { app.go(Screen.LinkPayPal) }
                    .onFailure { error = it.friendly() }
                busy = false
            }
        })
    }
    }
}

/**
 * A strip of paper tape with [content] on it, slapped onto the page after [delayMs]: drops in from 1.3× with a
 * springy settle at [rotation] and a light tap (a fade with reduce motion).
 */
@Composable
private fun TapeStrip(modifier: Modifier = Modifier, rotation: Float, delayMs: Int, content: @Composable () -> Unit) {
    val reduce = LocalReduceMotion.current
    val haptics = rememberHaptics()
    val a = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(delayMs.toLong())
        if (reduce) { a.animateTo(1f, tween(200)); return@LaunchedEffect }
        launch { delay(110); haptics.tap() }
        a.animateTo(1f, uiSpring(stiffness = 700f, damping = 0.5f))
    }
    Box(
        modifier.graphicsLayer {
            val v = a.value
            alpha = (v * 3f).coerceAtMost(1f)
            rotationZ = rotation
            if (!reduce) { val sc = 1.3f - 0.3f * v; scaleX = sc; scaleY = sc; rotationZ = rotation * (2f - v) }
        }.background(Color(0xFFEDE6D3).copy(alpha = 0.92f)).padding(horizontal = 14.dp, vertical = 2.dp),
    ) { content() }
}

/** Step 1: link PayPal (vault, nothing charged). PayPal's shipping address, if any, comes back as a suggestion for step 2. */
@Composable
fun LinkPayPalScreen(app: AppState) {
    val scope = rememberCoroutineScope()
    var setupTokenId by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var linkedEmail by remember { mutableStateOf(app.user.paypalEmail) }
    var linked by remember { mutableStateOf(app.user.paypalLinked) }
    // Celebrations only for what happens on this visit: "linked" stamps in and Scout hops.
    var justLinked by remember { mutableStateOf(false) }
    var linkDone by remember { mutableStateOf(false) }
    var hop by remember { mutableStateOf(false) }

    fun complete() {
        val token = setupTokenId ?: return
        busy = true; error = null
        scope.launch {
            runCatching { app.api.completePayPalLink(token) }
                .onSuccess { r ->
                    busy = false
                    linkDone = true; justLinked = true; hop = true
                    delay(DONE_PAUSE_MS + 250)
                    linked = r.paypalLinked; linkedEmail = r.email; linkDone = false
                    app.refresh()
                    delay(900); hop = false
                }
                .onFailure { error = it.friendly() }
            busy = false
        }
    }

    LaunchedEffect(Unit) {
        DeepLinks.incoming.collect { url ->
            when {
                url.startsWith("fetchit://paypal/return") -> complete()
                url.startsWith("fetchit://paypal/cancel") -> error = "You cancelled in PayPal. Nothing was linked."
            }
        }
    }

    Column(Modifier.fillMaxSize().dotted().padding(top = 52.dp, bottom = 34.dp)) {
        StepHeader(1, onBack = { app.back() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Gap(10.dp)
            Row(verticalAlignment = Alignment.Bottom) {
                Text("First, how should I pay?", style = hand(34.sp), modifier = Modifier.weight(1f))
                Dog(58.dp, if (hop) DogMood.Excited else DogMood.Wag)
            }
            Text("Link PayPal once. I'll only spend inside the house rules you set next.", style = body(16.sp, color = Ink.Muted))
            PayPalCard(linked, linkedEmail, stampIt = justLinked)
            PaperNote(tilt = 1f) {
                Text("with PayPal I can…", style = body(13.sp, FontWeight.SemiBold, Ink.Muted))
                listOf(true to "buy things that fit your rules", true to "ask you before anything else", false to "never see your password").forEachIndexed { i, (yes, line) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TickOrCross(yes, Modifier.size(18.dp), delayMs = 300 + 220 * i)
                        Text(line, style = hand(20.sp), modifier = Modifier.riseIn(300 + 220 * i + 120, distance = 6.dp))
                    }
                }
            }
            ErrorNote(error)
        }
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when {
                !linked && setupTokenId == null -> {
                    PillButton("Connect PayPal", loading = busy, onClick = {
                        busy = true; error = null
                        scope.launch {
                            runCatching { app.api.startPayPalLink() }
                                .onSuccess { setupTokenId = it.setupTokenId; openBrowser(it.approvalUrl) }
                                .onFailure { error = it.friendly() }
                            busy = false
                        }
                    })
                    Text(
                        "You'll log in on PayPal's own page and approve saving it for future payments.",
                        style = body(13.sp, color = Ink.Grey), modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    )
                }
                !linked -> {
                    PillButton("I approved it in PayPal", loading = busy, done = linkDone, onClick = { complete() })
                    if (!linkDone) PillButton("Open PayPal again", dark = false, height = 52.dp, onClick = { setupTokenId = null })
                }
                else -> PillButton("Next", onClick = { app.go(Screen.OnbDeliver) })
            }
        }
    }
}

/**
 * Step 2: where should I deliver? PayPal's address (if it sent one) as a paper note "from PayPal": "Yes, deliver
 * here" confirms it (the pin drops), "Use a different address" opens the form. No PayPal address: the form.
 * Coming back with a saved place shows it pinned.
 */
@Composable
fun OnbDeliverScreen(app: AppState) {
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { app.places = app.api.places() }; loaded = true }
    val suggestion = app.places.firstOrNull { it.suggested }
    val saved = app.defaultPlace
    var useForm by remember { mutableStateOf(false) }
    // The address the user types here is the one they chose: it becomes the default.
    var form by remember { mutableStateOf(PlaceForm(label = "Home", fullName = suggestion?.fullName ?: "", makeDefault = true)) }
    var tried by remember { mutableStateOf(false) }
    var serverErrors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var justPinned by remember { mutableStateOf(false) }
    val showForm = useForm || (loaded && saved == null && suggestion == null)

    Column(Modifier.fillMaxSize().dotted().padding(top = 52.dp, bottom = 34.dp)) {
        StepHeader(2, onBack = { if (useForm && (suggestion != null || saved != null)) useForm = false else app.back() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Gap(10.dp)
            Text("Where should I deliver?", style = hand(34.sp))
            when {
                showForm -> {
                    Text("I'll only ever ship to places you save.", style = body(16.sp, color = Ink.Muted))
                    Box(Modifier.noteSlideIn()) { AddressForm(form, { form = it; serverErrors = emptyMap() }, tried, serverErrors) }
                }
                saved != null -> {
                    Text("Got it. You can add more places in settings.", style = body(16.sp, color = Ink.Muted))
                    PlaceCard(saved, if (saved.phone != null) "call ${saved.phone}" else null, pinned = true, dropPin = justPinned)
                }
                suggestion != null -> {
                    Text("PayPal has this address for you.", style = body(16.sp, color = Ink.Muted))
                    PlaceCard(suggestion, "from PayPal", Modifier.noteSlideIn(150))
                }
                else -> RunningDog(70.dp)
            }
            ErrorNote(error)
        }
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when {
                showForm -> PillButton("Save this address", loading = busy, done = done, onClick = {
                    tried = true; busy = true; error = null
                    scope.launch {
                        val place = savePlace(app, form) { fields, message -> serverErrors = fields; error = message.takeIf { fields.isEmpty() } }
                        busy = false
                        if (place != null) { done = true; delay(DONE_PAUSE_MS); done = false; justPinned = true; useForm = false }
                    }
                })
                saved != null -> {
                    PillButton("Next", onClick = { app.go(Screen.OnbRules) })
                    PillButton("Use a different address", dark = false, height = 52.dp, onClick = { form = PlaceForm(label = if (saved.label == "Home") "Work" else "Home", fullName = saved.fullName, makeDefault = true); tried = false; useForm = true })
                }
                suggestion != null -> {
                    PillButton("Yes, deliver here", loading = busy, done = done, onClick = {
                        busy = true; error = null
                        scope.launch {
                            runCatching { app.api.confirmPlace(suggestion.placeId); app.places = app.api.places() }
                                .onSuccess { busy = false; done = true; delay(DONE_PAUSE_MS); done = false; justPinned = true }
                                .onFailure { error = it.friendly() }
                            busy = false
                        }
                    })
                    PillButton("Use a different address", dark = false, height = 52.dp, onClick = { useForm = true })
                }
            }
        }
    }
}

/** ✓ (or ✗) drawn by pen after [delayMs]. */
@Composable
private fun TickOrCross(yes: Boolean, modifier: Modifier, delayMs: Int) {
    if (yes) CheckMark(Ink.Ink, modifier, durationMs = 260, delayMs = delayMs, strokeWidth = 2.4.dp)
    else PathDraw(modifier, color = Ink.Accent, strokeWidth = 2.4.dp, durationMs = 260, delayMs = delayMs) { s ->
        listOf(
            Path().apply { moveTo(s.width * 0.18f, s.height * 0.18f); lineTo(s.width * 0.82f, s.height * 0.82f) },
            Path().apply { moveTo(s.width * 0.82f, s.height * 0.2f); lineTo(s.width * 0.2f, s.height * 0.82f) },
        )
    }
}

@Composable
private fun PayPalCard(linked: Boolean, email: String?, stampIt: Boolean) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Ink.White).border(1.dp, Ink.NoteBorder, RoundedCornerShape(24.dp)).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(Ink.Ink), contentAlignment = Alignment.Center) {
            Text("P", style = body(20.sp, FontWeight.Bold, Ink.White))
        }
        Column(Modifier.weight(1f)) {
            Text("PayPal", style = body(17.sp, FontWeight.SemiBold))
            Text(email ?: "sandbox account", style = body(13.sp, color = Ink.Muted))
        }
        when {
            linked && stampIt -> Stamp("LINKED ✓", color = Ink.Ink, rotation = -8f, fontSize = 13.sp)
            else -> Box(Modifier.clip(RoundedCornerShape(999.dp)).background(if (linked) Ink.Ink else Ink.Pill).padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text(if (linked) "linked" else "not linked", style = body(12.sp, FontWeight.SemiBold, if (linked) Ink.White else Ink.Muted))
            }
        }
    }
}

/** An address on a paper note. [pinned]: a black pin holds it; [dropPin] drops the pin in (on confirm). */
@Composable
fun PlaceCard(place: Place, note: String?, modifier: Modifier = Modifier, pinned: Boolean = false, dropPin: Boolean = false) {
    Box(modifier.fillMaxWidth()) {
        PaperNote(tilt = -0.6f, modifier = Modifier.fillMaxWidth().padding(top = if (pinned) 8.dp else 0.dp)) {
            Text(place.label.ifBlank { "Home" }, style = body(16.sp, FontWeight.SemiBold))
            Text(place.fullName, style = body(14.sp, color = Ink.Muted))
            Text(place.oneLine, style = body(14.sp, color = Ink.Muted))
            if (note != null) Text(note, style = hand(18.sp))
        }
        if (pinned) {
            val reduce = LocalReduceMotion.current
            val drop = remember { androidx.compose.animation.core.Animatable(if (dropPin && !reduce) 0f else 1f) }
            LaunchedEffect(Unit) { drop.animateTo(1f, uiSpring(stiffness = 420f, damping = 0.6f)) }
            Box(
                Modifier.align(Alignment.TopCenter).size(20.dp).graphicsLayer {
                    translationY = (1f - drop.value) * -46.dp.toPx(); alpha = (drop.value * 3f).coerceAtMost(1f)
                }.shadow(3.dp, CircleShape).clip(CircleShape).background(Ink.Ink),
            )
        }
    }
}

/** Step 2 (and the Rules tab): budget, auto-buy limit, categories, asks. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RulesEditor(initial: HouseRules, focus: RuleFocus? = null, onChange: (HouseRules) -> Unit) {
    var r by remember(initial) { mutableStateOf(initial) }
    fun update(n: HouseRules) { r = n; onChange(n) }
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        // Budget and auto-buy limit on two drums side by side; either can be typed instead.
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            MoneyDrum(
                "Monthly budget", r.monthlyBudget, BUDGET_STEPS, " a month", ::budgetMood,
                Modifier.weight(1f).rulesSection(focus == RuleFocus.Budget),
            ) { update(r.copy(monthlyBudget = it)) }
            MoneyDrum(
                "Auto-buy up to", r.perItemLimit, PER_ITEM_STEPS, " per item", ::perItemMood,
                Modifier.weight(1f).rulesSection(focus == RuleFocus.PerItem),
            ) { update(r.copy(perItemLimit = it)) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AnimatedVisibility(r.perItemLimit > r.monthlyBudget, enter = slideInVertically(tween(250)) { -it } + fadeIn(tween(250)), exit = fadeOut(tween(150))) {
                WarningNote("That's more than your monthly budget")
            }
            // How much of the month one auto-buy can take.
            BudgetBar((r.perItemLimit / r.monthlyBudget).toFloat(), color = if (r.perItemLimit > r.monthlyBudget) Ink.Accent else Ink.Ink)
            Text("Anything over the limit asks for your fingerprint.", style = hand(18.sp, Ink.Muted))
        }
        Column(Modifier.rulesSection(focus == RuleFocus.Categories), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("I can shop for…", style = hand(24.sp), modifier = Modifier.weight(1f))
                LiveCount(r.allowedCategories.size)
            }
            // Every Channel3 root, plus any older sub-category slug still in the rules (so it can be turned off).
            val slugs = ShopCategory.entries.map { it.slug } + r.allowedCategories.filter { s -> ShopCategory.entries.none { it.slug == s } }
            slugs.forEachIndexed { i, slug ->
                val on = slug in r.allowedCategories
                CategoryRow(ShopCategory.labelFor(slug), on, Modifier.riseIn(150 + 35 * i.coerceAtMost(10), distance = 10.dp)) {
                    update(r.copy(allowedCategories = if (on) r.allowedCategories - slug else r.allowedCategories + slug))
                }
            }
        }
        PaperNote(tilt = 0.5f, modifier = Modifier.fillMaxWidth().rulesSection(focus == RuleFocus.Asks)) {
            Toggle("Ask before a new store", r.askBeforeNewStore) { update(r.copy(askBeforeNewStore = it)) }
            Toggle("Ask if I'm unsure about size", r.askIfUnsureAboutSize) { update(r.copy(askIfUnsureAboutSize = it)) }
            Toggle("Only ship to saved places", r.onlyShipToSavedPlaces) { update(r.copy(onlyShipToSavedPlaces = it)) }
        }
    }
}

/**
 * The section a "Change rule" link points at: scrolled into view, then outlined with a dashed pen line that fades
 * after ~1.5 s (just the fade with reduce motion).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.rulesSection(highlight: Boolean): Modifier {
    if (!highlight) return this
    val requester = remember { BringIntoViewRequester() }
    val glow = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(Unit) { delay(350); requester.bringIntoView(); delay(900); glow.animateTo(0f, tween(700)) }
    return this.bringIntoViewRequester(requester).drawBehind {
        val pad = 8.dp.toPx()
        drawRoundRect(
            Ink.Ink.copy(alpha = glow.value), topLeft = Offset(-pad, -pad), size = Size(size.width + 2 * pad, size.height + 2 * pad),
            cornerRadius = CornerRadius(14.dp.toPx()), style = inkStroke(1.6.dp.toPx(), dashed = true, dash = 6.dp.toPx()),
        )
    }
}

private val BUDGET_STEPS = listOf(25.0, 50.0, 75.0, 100.0, 150.0, 200.0, 250.0, 300.0, 400.0, 500.0, 750.0, 1000.0, 1500.0, 2000.0)
private val PER_ITEM_STEPS = listOf(10.0, 15.0, 20.0, 25.0, 30.0, 40.0, 50.0, 60.0, 75.0, 100.0, 125.0, 150.0, 200.0, 250.0, 300.0, 400.0, 500.0)

private fun budgetMood(v: Double) = when {
    v <= 75 -> "Careful nibbler"
    v <= 150 -> "Thrifty pup"
    v <= 300 -> "Sensible shopper"
    v <= 750 -> "Treat yourself"
    else -> "Big spender"
}

private fun perItemMood(v: Double) = when {
    v <= 25 -> "Little things"
    v <= 75 -> "Everyday buys"
    v <= 150 -> "Nice things"
    v <= 300 -> "Splurges"
    else -> "Go big"
}

/**
 * A money drum with its caption ("$200 a month · Sensible shopper", cross-fading as it changes). "type it" swaps
 * the drum for a typed amount (AmountField); the typed value then joins the drum.
 */
@Composable
private fun MoneyDrum(title: String, value: Double, steps: List<Double>, suffix: String, mood: (Double) -> String, modifier: Modifier, onChange: (Double) -> Unit) {
    var typing by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = body(14.sp, FontWeight.SemiBold, Ink.Muted))
        if (typing) AmountField(value, { onChange(it); typing = false }, suffix, style = body(30.sp, FontWeight.Medium))
        else DrumPicker(steps, value, onChange, { money(it) }, contentDescription = title)
        AnimatedContent(value, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) }, label = "mood") { v ->
            Column {
                Text("${money(v)}$suffix", style = serif(16.sp, Ink.Ink))
                Text(mood(v), style = hand(19.sp, Ink.Muted))
            }
        }
        Text(
            if (typing) "use the wheel" else "type it", style = body(13.sp, FontWeight.SemiBold, Ink.Muted),
            modifier = Modifier.clip(RoundedCornerShape(999.dp)).clickable { typing = !typing }.padding(vertical = 6.dp, horizontal = 2.dp),
        )
    }
}

/** "3 categories" / "1 category" / "none selected", popping when it changes. */
@Composable
private fun LiveCount(n: Int) {
    val reduce = LocalReduceMotion.current
    val pop = remember { androidx.compose.animation.core.Animatable(1f) }
    var last by remember { mutableStateOf(n) }
    LaunchedEffect(n) { if (n != last && !reduce) { pop.snapTo(1.15f); pop.animateTo(1f, uiSpring(stiffness = 600f)) }; last = n }
    Text(
        when (n) { 0 -> "none selected"; 1 -> "1 category"; else -> "$n categories" },
        style = serif(17.sp, if (n == 0) Ink.Warning else Ink.Muted),
        modifier = Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value },
    )
}

/** A full-width category row: picking it fills it black from the left and draws a ✓. */
@Composable
private fun CategoryRow(label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val reduce = LocalReduceMotion.current
    val fill by animateFloatAsState(if (on) 1f else 0f, tween(if (reduce) 120 else 260), label = "fill")
    val fg by animateColorAsState(if (on) Ink.White else Ink.Ink, tween(200), label = "fg")
    Box(
        modifier.fillMaxWidth().height(50.dp).grayPress(onClick, Role.Checkbox, RoundedCornerShape(16.dp)).background(Ink.Pill)
            .semantics { stateDescription = if (on) "allowed" else "not allowed" },
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fill).background(Ink.Ink))
        Row(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = body(16.sp, FontWeight.SemiBold, fg), modifier = Modifier.weight(1f))
            if (on) CheckMark(Ink.White, Modifier.size(20.dp), durationMs = 260, delayMs = 120, strokeWidth = 2.6.dp)
        }
    }
}

@Composable
fun OnbRulesScreen(app: AppState) {
    val scope = rememberCoroutineScope()
    var rules by remember { mutableStateOf(app.rules ?: HouseRules()) }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().dotted().padding(top = 52.dp, bottom = 34.dp)) {
        StepHeader(3, onBack = { app.back() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Set the house rules", style = hand(34.sp))
            Text("I'll never spend outside these. Anything else, I ask you first.", style = body(16.sp, color = Ink.Muted))
            Gap(4.dp)
            RulesEditor(rules) { rules = it }
            ErrorNote(error)
        }
        PillButton("Looks good", loading = busy, done = done, enabled = rules.allowedCategories.isNotEmpty(), modifier = Modifier.padding(horizontal = 20.dp), onClick = {
            busy = true; error = null
            scope.launch {
                runCatching { app.api.setRules(rules) }
                    .onSuccess { app.rules = it; busy = false; done = true; delay(DONE_PAUSE_MS); app.go(Screen.OnbSizes) }
                    .onFailure { error = it.friendly() }
                busy = false
            }
        })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnbSizesScreen(app: AppState) {
    val scope = rememberCoroutineScope()
    var s by remember { mutableStateOf(app.sizes.let { if (it.top == null) it.copy(top = "M", waist = "32", fit = "Regular") else it }) }
    var shoeScale by remember { mutableStateOf(app.sizes.shoe?.substringBefore(' ')?.takeIf { it in setOf("US", "UK", "EU") } ?: "US") }
    var done by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().dotted().padding(top = 52.dp, bottom = 34.dp)) {
        StepHeader(4, onBack = { app.back() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("What size are you?", style = hand(34.sp))
            Text("So I never fetch the wrong fit.", style = body(16.sp, color = Ink.Muted))
            SizeRow("Tops", listOf("XS", "S", "M", "L", "XL", "XXL"), s.top, 0) { s = s.copy(top = it) }
            SizeRow("Waist", listOf("28", "30", "32", "34", "36", "38"), s.waist, 1) { s = s.copy(waist = it) }
            Column(Modifier.riseIn(2 * 70 + 150), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Shoes", style = body(14.sp, FontWeight.SemiBold, Ink.Muted), modifier = Modifier.weight(1f))
                    Segmented(listOf("US", "UK", "EU"), shoeScale, { it }, { shoeScale = it }, Modifier.width(180.dp))
                }
                // Same seven slots in every scale, so switching flips the numbers in place.
                val shoes = when (shoeScale) { "UK" -> (5..11).map { "UK $it" }; "EU" -> (39..45).map { "EU $it" }; else -> (6..12).map { "US $it" } }
                SizePicker(shoes, s.shoe, { s = s.copy(shoe = it) }, display = { it.substringAfter(' ') }, flipKey = shoeScale)
            }
            SizeRow("Fit", listOf("Slim", "Regular", "Relaxed"), s.fit, 3) { s = s.copy(fit = it) }
            Box(Modifier.riseIn(4 * 70 + 150)) { Toggle("Ask me if I'm unsure", s.askIfUnsure) { s = s.copy(askIfUnsure = it) } }
        }
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // From Account (already onboarded) this is an edit: go back instead of on to "Meet your fetcher".
            PillButton(if (app.onboarded) "Save" else "Save my sizes", done = done, onClick = {
                app.saveSizes(s)
                scope.launch { done = true; delay(DONE_PAUSE_MS); if (app.onboarded) app.back() else app.go(Screen.Ready) }
            })
            if (!app.onboarded) PillButton("Skip, ask me later", dark = false, height = 52.dp, modifier = Modifier.wiggleAfterIdle(s), onClick = { app.go(Screen.Ready) })
        }
    }
}

@Composable
private fun SizeRow(label: String, options: List<String>, selected: String?, index: Int, onPick: (String) -> Unit) {
    Column(Modifier.riseIn(index * 70 + 150), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = body(14.sp, FontWeight.SemiBold, Ink.Muted))
        SizePicker(options, selected, onPick)
    }
}

/**
 * Meet your fetcher: Scout pops in big and tilts his head while you type a name. "Start fetching": he runs off
 * the right edge, then the camera screen slides up (App.screenTransition).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReadyScreen(app: AppState) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(if (app.dogName == "Scout") "" else app.dogName) }
    var typing by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf(false) }
    LaunchedEffect(name) { if (typed) { typing = true; delay(700); typing = false } }
    val tilt by animateFloatAsState(if (typing) 12f else 0f, uiSpring(stiffness = 300f), label = "tilt")
    Column(Modifier.fillMaxSize().dotted().padding(top = 52.dp, bottom = 34.dp)) {
        StepHeader(5, onBack = { app.back() })
        Column(Modifier.weight(1f).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Meet your fetcher.", style = hand(36.sp))
            Box(Modifier.fillMaxWidth().height(190.dp), contentAlignment = Alignment.Center) {
                // On "Start fetching" this big Scout shrinks into the small one peeking over the viewfinder.
                Dog(150.dp, DogMood.Wag, Modifier.sharedShape(SCOUT_KEY).popIn(150).graphicsLayer { rotationZ = -3f }, headTilt = tilt)
            }
            Text("What should I call them?", style = body(14.sp, FontWeight.SemiBold, Ink.Muted))
            Box(Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(999.dp)).background(Ink.Pill).padding(horizontal = 22.dp), contentAlignment = Alignment.CenterStart) {
                if (name.isEmpty()) Text("Scout", style = hand(26.sp, Ink.LightGrey))
                BasicTextField(name, { name = it.take(20); typed = true }, singleLine = true, textStyle = hand(26.sp), modifier = Modifier.fillMaxWidth())
            }
            Text("all set:", style = hand(20.sp, Ink.Muted))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOfNotNull(
                    if (app.user.paypalLinked) "PayPal linked" else null,
                    app.rules?.let { "${money(it.monthlyBudget)} a month" },
                    app.rules?.let { "auto-buy up to ${money(it.perItemLimit)}" },
                    app.defaultPlace?.let { "ships to ${it.label}" },
                    app.sizes.top?.let { "size $it" },
                ).forEachIndexed { i, label -> Chip(label, false, onClick = {}, modifier = Modifier.popIn(400 + 60 * i)) }
            }
        }
        // From Account (already onboarded) this is a rename.
        PillButton(if (app.onboarded) "Save" else "Start fetching", modifier = Modifier.padding(horizontal = 20.dp), onClick = {
            app.saveDogName(name)
            if (app.onboarded) app.back() else app.finishOnboarding()
        })
    }
}
