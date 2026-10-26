package com.rimapps.fetchit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rimapps.fetchit.AppState
import com.rimapps.fetchit.Screen
import com.rimapps.fetchit.Sizes
import com.rimapps.fetchit.data.HouseRules
import com.rimapps.fetchit.data.Place
import com.rimapps.fetchit.data.ShopCategory
import com.rimapps.fetchit.friendly
import com.rimapps.fetchit.platform.DeepLinks
import com.rimapps.fetchit.platform.openBrowser
import kotlinx.coroutines.launch

@Composable
fun WelcomeScreen(app: AppState) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().dotted().padding(bottom = 34.dp)) {
        Text("Fetch It", style = body(22.sp, color = Ink.LightGrey), modifier = Modifier.align(Alignment.End).padding(top = 58.dp, end = 26.dp))
        Box(Modifier.fillMaxWidth().height(340.dp)) {
            Polaroid(null, "mushroom lamp", Modifier.width(190.dp).offset(36.dp, 30.dp), tilt = -6f, imageHeight = 150.dp)
            Polaroid(null, "tan sneakers", Modifier.width(170.dp).align(Alignment.TopEnd).offset((-30).dp, 120.dp), tilt = 5f, imageHeight = 130.dp)
            Mascot(110.dp, Modifier.align(Alignment.BottomStart).offset(26.dp, 10.dp))
        }
        Text(
            buildAnnotatedString {
                append("You spot it.\nWe ")
                withStyle(SpanStyle(fontFamily = Fonts.serif, fontStyle = FontStyle.Italic, fontWeight = FontWeight.Normal, fontSize = 52.sp)) { append("fetch") }
                append(" it.")
            },
            style = body(46.sp, FontWeight.Medium).copy(lineHeight = 48.sp, letterSpacing = (-1.6).sp),
            modifier = Modifier.padding(horizontal = 26.dp, vertical = 8.dp),
        )
        Text(
            "Snap it, and your agent finds it, checks your rules, and pays with PayPal.",
            style = body(16.sp, color = Ink.NavGrey), modifier = Modifier.padding(horizontal = 26.dp),
        )
        Spacer()
        ErrorNote(error, Modifier.padding(horizontal = 26.dp, vertical = 8.dp))
        PillButton("Let's fetch", loading = busy, modifier = Modifier.padding(horizontal = 20.dp), onClick = {
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

/** Step 1: link PayPal (vault, nothing charged), then confirm the Home address PayPal suggests. */
@Composable
fun LinkPayPalScreen(app: AppState) {
    val scope = rememberCoroutineScope()
    var setupTokenId by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var linkedEmail by remember { mutableStateOf(app.user.paypalEmail) }
    var linked by remember { mutableStateOf(app.user.paypalLinked) }
    var suggested by remember { mutableStateOf<Place?>(null) }
    var placeConfirmed by remember { mutableStateOf(app.homePlace != null) }

    fun complete() {
        val token = setupTokenId ?: return
        busy = true; error = null
        scope.launch {
            runCatching { app.api.completePayPalLink(token) }
                .onSuccess { r -> linked = r.paypalLinked; linkedEmail = r.email; suggested = r.suggestedPlace; app.refresh() }
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
            Text("First, how should I pay?", style = hand(34.sp))
            Text("Link PayPal once. I'll only spend inside the house rules you set next.", style = body(16.sp, color = Ink.Muted))
            PayPalCard(linked, linkedEmail)
            PaperNote(tilt = 1f) {
                Text("with PayPal I can…", style = body(13.sp, FontWeight.SemiBold, Ink.Muted))
                Text("✓  buy things that fit your rules", style = hand(20.sp))
                Text("✓  ask you before anything else", style = hand(20.sp))
                Text("✗  never see your password", style = hand(20.sp))
            }
            val place = suggested ?: app.places.firstOrNull { it.suggested }
            if (linked && place != null && !placeConfirmed) {
                Text("Where should I deliver?", style = hand(28.sp))
                PlaceCard(place, "from your PayPal account")
            } else if (linked && app.homePlace != null) {
                Text("Delivering to", style = body(13.sp, FontWeight.SemiBold, Ink.Muted))
                PlaceCard(app.homePlace!!, null)
            }
            ErrorNote(error)
        }
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val place = suggested ?: app.places.firstOrNull { it.suggested }
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
                    PillButton("I approved it in PayPal", loading = busy, onClick = { complete() })
                    PillButton("Open PayPal again", dark = false, height = 52.dp, onClick = { setupTokenId = null })
                }
                place != null && !placeConfirmed -> PillButton("Deliver here", loading = busy, onClick = {
                    busy = true
                    scope.launch {
                        runCatching { app.api.confirmPlace(place.placeId) }
                            .onSuccess { placeConfirmed = true; app.refresh() }
                            .onFailure { error = it.friendly() }
                        busy = false
                    }
                })
                else -> PillButton("Next", onClick = { app.go(Screen.OnbRules) })
            }
        }
    }
}

@Composable
private fun PayPalCard(linked: Boolean, email: String?) {
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
        Box(Modifier.clip(RoundedCornerShape(999.dp)).background(if (linked) Ink.Ink else Ink.Pill).padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(if (linked) "linked" else "not linked", style = body(12.sp, FontWeight.SemiBold, if (linked) Ink.White else Ink.Muted))
        }
    }
}

@Composable
fun PlaceCard(place: Place, note: String?) {
    PaperNote(tilt = -0.6f, modifier = Modifier.fillMaxWidth()) {
        Text(place.label.ifBlank { "Home" }, style = body(16.sp, FontWeight.SemiBold))
        Text(place.fullName, style = body(14.sp, color = Ink.Muted))
        Text(place.oneLine, style = body(14.sp, color = Ink.Muted))
        if (note != null) Text(note, style = hand(18.sp))
    }
}

/** Step 2 (and the Rules tab): budget, auto-buy limit, categories, asks. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RulesEditor(initial: HouseRules, onChange: (HouseRules) -> Unit) {
    var r by remember(initial) { mutableStateOf(initial) }
    fun update(n: HouseRules) { r = n; onChange(n) }
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Monthly budget", style = body(14.sp, FontWeight.SemiBold, Ink.Muted))
            Text(buildAnnotatedString { append(money(r.monthlyBudget)); withStyle(SpanStyle(fontFamily = Fonts.serif, fontStyle = FontStyle.Italic, fontSize = 20.sp, color = Ink.Grey)) { append(" a month") } }, style = body(34.sp, FontWeight.Medium))
            Segmented(listOf(100.0, 200.0, 500.0), r.monthlyBudget, { money(it) }, { update(r.copy(monthlyBudget = it)) })
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Buy without asking, up to", style = body(14.sp, FontWeight.SemiBold, Ink.Muted))
            Text(buildAnnotatedString { append(money(r.perItemLimit)); withStyle(SpanStyle(fontFamily = Fonts.serif, fontStyle = FontStyle.Italic, fontSize = 20.sp, color = Ink.Grey)) { append(" per item") } }, style = body(34.sp, FontWeight.Medium))
            Segmented(listOf(25.0, 50.0, 75.0, 100.0), r.perItemLimit, { money(it) }, { update(r.copy(perItemLimit = it)) })
            if (r.perItemLimit > r.monthlyBudget) ErrorNote("That's more than your monthly budget")
            Text("Anything over this asks for your fingerprint.", style = hand(18.sp, Ink.Muted))
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("I can shop for…", style = hand(24.sp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ShopCategory.entries.forEach { c ->
                    val on = c.slug in r.allowedCategories
                    Chip(c.label, on, onClick = {
                        update(r.copy(allowedCategories = if (on) r.allowedCategories - c.slug else r.allowedCategories + c.slug))
                    })
                }
            }
        }
        PaperNote(tilt = 0.5f, modifier = Modifier.fillMaxWidth()) {
            Toggle("Ask before a new store", r.askBeforeNewStore) { update(r.copy(askBeforeNewStore = it)) }
            Toggle("Ask if I'm unsure about size", r.askIfUnsureAboutSize) { update(r.copy(askIfUnsureAboutSize = it)) }
            Toggle("Only ship to saved places", r.onlyShipToSavedPlaces) { update(r.copy(onlyShipToSavedPlaces = it)) }
        }
    }
}

@Composable
fun Toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!on) }, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = body(15.sp, FontWeight.Medium), modifier = Modifier.weight(1f))
        Switch(on, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Ink.Ink, uncheckedTrackColor = Color(0xFFD6D6D2), uncheckedBorderColor = Color.Transparent, uncheckedThumbColor = Ink.White))
    }
}

@Composable
fun OnbRulesScreen(app: AppState) {
    val scope = rememberCoroutineScope()
    var rules by remember { mutableStateOf(app.rules ?: HouseRules()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().dotted().padding(top = 52.dp, bottom = 34.dp)) {
        StepHeader(2, onBack = { app.back() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Set the house rules", style = hand(34.sp))
            Text("I'll never spend outside these. Anything else, I ask you first.", style = body(16.sp, color = Ink.Muted))
            Gap(4.dp)
            RulesEditor(rules) { rules = it }
            ErrorNote(error)
        }
        PillButton("Looks good", loading = busy, enabled = rules.allowedCategories.isNotEmpty(), modifier = Modifier.padding(horizontal = 20.dp), onClick = {
            busy = true; error = null
            scope.launch {
                runCatching { app.api.setRules(rules) }
                    .onSuccess { app.rules = it; app.go(Screen.OnbSizes) }
                    .onFailure { error = it.friendly() }
                busy = false
            }
        })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnbSizesScreen(app: AppState) {
    var s by remember { mutableStateOf(app.sizes.let { if (it.top == null) it.copy(top = "M", waist = "32", fit = "Regular") else it }) }
    var shoeScale by remember { mutableStateOf("US") }
    Column(Modifier.fillMaxSize().dotted().padding(top = 52.dp, bottom = 34.dp)) {
        StepHeader(3, onBack = { app.back() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("What size are you?", style = hand(34.sp))
            Text("So I never fetch the wrong fit.", style = body(16.sp, color = Ink.Muted))
            SizeRow("Tops", listOf("XS", "S", "M", "L", "XL", "XXL"), s.top) { s = s.copy(top = it) }
            SizeRow("Waist", listOf("28", "30", "32", "34", "36", "38"), s.waist) { s = s.copy(waist = it) }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Shoes", style = body(14.sp, FontWeight.SemiBold, Ink.Muted), modifier = Modifier.weight(1f))
                    Segmented(listOf("US", "UK", "EU"), shoeScale, { it }, { shoeScale = it }, Modifier.width(180.dp))
                }
                val shoes = when (shoeScale) { "UK" -> (5..11).map { "UK $it" }; "EU" -> (38..45).map { "EU $it" }; else -> (6..12).map { "US $it" } }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    shoes.forEach { v -> Chip(v.substringAfter(' '), s.shoe == v, onClick = { s = s.copy(shoe = v) }) }
                }
            }
            SizeRow("Fit", listOf("Slim", "Regular", "Relaxed"), s.fit) { s = s.copy(fit = it) }
            Toggle("Ask me if I'm unsure", s.askIfUnsure) { s = s.copy(askIfUnsure = it) }
        }
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("Save my sizes", onClick = { app.saveSizes(s); app.go(Screen.Ready) })
            PillButton("Skip, ask me later", dark = false, height = 52.dp, onClick = { app.go(Screen.Ready) })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SizeRow(label: String, options: List<String>, selected: String?, onPick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = body(14.sp, FontWeight.SemiBold, Ink.Muted))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { o -> Chip(o, o == selected, onClick = { onPick(o) }) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReadyScreen(app: AppState) {
    var name by remember { mutableStateOf(if (app.dogName == "Scout") "" else app.dogName) }
    Column(Modifier.fillMaxSize().dotted().padding(top = 52.dp, bottom = 34.dp)) {
        StepHeader(4, onBack = { app.back() })
        Column(Modifier.weight(1f).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Meet your fetcher.", style = hand(36.sp))
            Mascot(150.dp, Modifier.align(Alignment.CenterHorizontally).rotate(-3f))
            Text("What should I call them?", style = body(14.sp, FontWeight.SemiBold, Ink.Muted))
            Box(Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(999.dp)).background(Ink.Pill).padding(horizontal = 22.dp), contentAlignment = Alignment.CenterStart) {
                if (name.isEmpty()) Text("Scout", style = hand(26.sp, Ink.LightGrey))
                BasicTextField(name, { name = it.take(20) }, singleLine = true, textStyle = hand(26.sp), modifier = Modifier.fillMaxWidth())
            }
            Text("all set:", style = hand(20.sp, Ink.Muted))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOfNotNull(
                    if (app.user.paypalLinked) "PayPal linked" else null,
                    app.rules?.let { "${money(it.monthlyBudget)} a month" },
                    app.rules?.let { "auto-buy up to ${money(it.perItemLimit)}" },
                    app.homePlace?.let { "ships to ${it.label}" },
                    app.sizes.top?.let { "size $it" },
                ).forEach { Chip(it, false, onClick = {}) }
            }
        }
        PillButton("Start fetching", modifier = Modifier.padding(horizontal = 20.dp), onClick = {
            app.saveDogName(name)
            app.finishOnboarding()
        })
    }
}
