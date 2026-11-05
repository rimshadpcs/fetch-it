package com.rimapps.fetchit.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rimapps.fetchit.AppState
import com.rimapps.fetchit.Screen
import com.rimapps.fetchit.data.FunctionsException
import com.rimapps.fetchit.data.Place
import com.rimapps.fetchit.data.PlaceForm
import com.rimapps.fetchit.friendly
import com.rimapps.fetchit.ui.components.Chip
import com.rimapps.fetchit.ui.components.PillButton
import com.rimapps.fetchit.ui.motion.noteSlideIn
import com.rimapps.fetchit.ui.motion.riseIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ---- Address form (onboarding "Use a different address", Saved places → add / edit) ----

private val US_STATES = setOf(
    "AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE", "FL", "GA", "HI", "ID", "IL", "IN", "IA", "KS", "KY", "LA", "ME", "MD",
    "MA", "MI", "MN", "MS", "MO", "MT", "NE", "NV", "NH", "NJ", "NM", "NY", "NC", "ND", "OH", "OK", "OR", "PA", "RI", "SC",
    "SD", "TN", "TX", "UT", "VT", "VA", "WA", "WV", "WI", "WY", "DC", "PR", "GU", "VI", "AS", "MP",
)

/** The same checks addPlace makes (functions/src/lib/address.ts), so most mistakes show before a round trip. */
fun checkPlace(f: PlaceForm): Map<String, String> = buildMap {
    if (!Regex("^[\\p{L}\\p{N}' &.-]{1,24}$").matches(f.label.trim())) put("label", "Name it in 1–24 letters")
    if (f.fullName.trim().length < 2) put("fullName", "Who it's for")
    val l1 = f.line1.trim()
    if (l1.length < 3 || (!l1.any(Char::isDigit) && !Regex("^p\\.?\\s*o\\.?\\s*box", RegexOption.IGNORE_CASE).containsMatchIn(l1))) put("line1", "Street address with a number")
    if (f.city.trim().length < 2 || !Regex("^[\\p{L} .'-]+$").matches(f.city.trim())) put("city", "City name")
    if (f.state.trim().uppercase() !in US_STATES) put("state", "2-letter state")
    if (!Regex("^\\d{5}(-\\d{4})?$").matches(f.postalCode.trim())) put("postalCode", "5-digit ZIP")
    val phone = f.phone.filter(Char::isDigit).removePrefix("1").takeIf { f.phone.isNotBlank() }
    if (phone != null && !Regex("^[2-9]\\d{2}[2-9]\\d{6}$").matches(phone)) put("phone", "10-digit US phone")
}

/**
 * Name, street, city / state / ZIP, phone, and what to call the place (Home, Work or your own). Problems show under
 * their field once [showErrors] (after the first Save), including the server's ([serverErrors]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddressForm(form: PlaceForm, onChange: (PlaceForm) -> Unit, showErrors: Boolean, serverErrors: Map<String, String> = emptyMap()) {
    val errors = if (showErrors) checkPlace(form) + serverErrors.filterKeys { it !in checkPlace(form) } else emptyMap()
    val preset = form.label in setOf("Home", "Work")
    var custom by remember { mutableStateOf(!preset) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Call it", style = body(13.sp, FontWeight.SemiBold, Ink.Muted))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Home", !custom && form.label == "Home", onClick = { custom = false; onChange(form.copy(label = "Home")) })
                Chip("Work", !custom && form.label == "Work", onClick = { custom = false; onChange(form.copy(label = "Work")) })
                Chip("Other…", custom, onClick = { custom = true; if (preset) onChange(form.copy(label = "")) })
            }
            if (custom) Field("Name for this place", form.label, { onChange(form.copy(label = it.take(24))) }, errors["label"], placeholder = "Mum's, Cabin…")
        }
        PaperNote(Modifier.fillMaxWidth(), tilt = -0.4f) {
            Field("Full name", form.fullName, { onChange(form.copy(fullName = it)) }, errors["fullName"], caps = KeyboardCapitalization.Words)
            Field("Street address", form.line1, { onChange(form.copy(line1 = it)) }, errors["line1"], placeholder = "2211 N First St", caps = KeyboardCapitalization.Words)
            Field("Apt, suite (optional)", form.line2, { onChange(form.copy(line2 = it)) }, errors["line2"])
            Field("City", form.city, { onChange(form.copy(city = it)) }, errors["city"], caps = KeyboardCapitalization.Words)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.width(96.dp)) {
                    Field("State", form.state, { onChange(form.copy(state = it.filter(Char::isLetter).take(2).uppercase())) }, errors["state"], placeholder = "CA", caps = KeyboardCapitalization.Characters)
                }
                Box(Modifier.weight(1f)) {
                    Field("ZIP", form.postalCode, { onChange(form.copy(postalCode = it.filter { c -> c.isDigit() || c == '-' }.take(10))) }, errors["postalCode"], placeholder = "95131", keyboard = KeyboardType.Number)
                }
            }
            Field("Phone (optional, for the courier)", form.phone, { onChange(form.copy(phone = it.take(20))) }, errors["phone"], keyboard = KeyboardType.Phone)
        }
    }
}

/** A labelled underline field: the line turns ink while focused, the error slides in under it. */
@Composable
private fun Field(
    label: String, value: String, onChange: (String) -> Unit, error: String?,
    placeholder: String = "", keyboard: KeyboardType = KeyboardType.Text, caps: KeyboardCapitalization = KeyboardCapitalization.Sentences,
) {
    var focused by remember { mutableStateOf(false) }
    val line by animateColorAsState(if (error != null) Ink.Accent else if (focused) Ink.Ink else Ink.NoteBorder, tween(200), label = "line")
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = body(12.sp, FontWeight.SemiBold, Ink.Muted))
        Box {
            if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, style = body(17.sp, color = Ink.LightGrey))
            BasicTextField(
                value, onChange, singleLine = true, textStyle = body(17.sp, FontWeight.Medium),
                keyboardOptions = KeyboardOptions(capitalization = caps, keyboardType = keyboard, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
            )
        }
        Box(Modifier.fillMaxWidth().height(if (focused) 2.dp else 1.dp).background(line))
        AnimatedVisibility(error != null, enter = slideInVertically(tween(200)) { -it } + fadeIn(tween(200)), exit = fadeOut(tween(120))) {
            Text(error ?: "", style = body(12.sp, FontWeight.Medium, Ink.Warning))
        }
    }
}

fun Place.toForm() = PlaceForm(label, fullName, line1, line2 ?: "", city, state, postalCode, phone?.removePrefix("+1") ?: "", placeId)

/**
 * Saves [form] with addPlace. Returns the saved place, or null with [onErrors] given every field problem
 * (client checks first, then the server's).
 */
suspend fun savePlace(app: AppState, form: PlaceForm, onErrors: (Map<String, String>, String?) -> Unit): Place? {
    val local = checkPlace(form)
    if (local.isNotEmpty()) { onErrors(local, null); return null }
    return try {
        app.api.addPlace(form.copy(state = form.state.uppercase(), label = form.label.trim())).also { app.places = app.api.places() }
    } catch (e: FunctionsException) {
        onErrors(e.fields, e.message); null
    } catch (e: Exception) {
        onErrors(emptyMap(), e.friendly()); null
    }
}

// ---- You & settings → Saved places ----

/**
 * Every saved place as a paper note (the default one pinned). Make default, edit, delete; deleting the default
 * first asks which place takes over.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlacesScreen(app: AppState) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) } // placeId waiting for confirm / a new default
    LaunchedEffect(Unit) { app.refreshPlaces() }
    val saved = app.savedPlaces

    fun run(key: String, block: suspend () -> Unit) {
        busy = key; error = null
        scope.launch {
            runCatching { block(); app.places = app.api.places() }.onFailure { error = it.friendly() }
            busy = null; deleting = null
        }
    }

    Column(Modifier.fillMaxSize().dotted().padding(top = 56.dp, bottom = 34.dp)) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BackButton({ app.back() })
            Text("Saved places", style = hand(30.sp))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("I only ship to these. The pinned one is where Buy starts.", style = body(15.sp, color = Ink.Muted))
            if (saved.isEmpty()) Text("No places yet.", style = hand(22.sp, Ink.Muted))
            saved.forEachIndexed { i, p ->
                Column(Modifier.riseIn(80 * i), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlaceCard(p, if (p.isDefault) "default · Buy ships here" else null, pinned = p.isDefault)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!p.isDefault) Chip("Make default", false, onClick = { run("default-${p.placeId}") { app.api.setDefaultPlace(p.placeId) } })
                        Chip("Edit", false, onClick = { app.go(Screen.PlaceEdit(p.placeId)) })
                        if (saved.size > 1) Chip(if (deleting == p.placeId && !p.isDefault) "Tap again to delete" else "Delete", deleting == p.placeId, onClick = {
                            when {
                                deleting != p.placeId -> deleting = p.placeId
                                !p.isDefault -> run("delete") { app.api.deletePlace(p.placeId) }
                            }
                        })
                    }
                    // Deleting the default: pick who takes over, which also confirms the delete.
                    AnimatedVisibility(deleting == p.placeId && p.isDefault, enter = slideInVertically(tween(250)) { -it / 2 } + fadeIn(tween(250)), exit = fadeOut(tween(150))) {
                        PaperNote(Modifier.fillMaxWidth(), tilt = 0.6f) {
                            Text("That's your default. Which place takes over?", style = hand(20.sp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                saved.filter { it.placeId != p.placeId }.forEach { o ->
                                    Chip("${o.label}, then delete", false, onClick = { run("delete") { app.api.deletePlace(p.placeId, newDefaultId = o.placeId) } })
                                }
                                Chip("Keep it", false, onClick = { deleting = null })
                            }
                        }
                    }
                }
            }
            if (saved.size == 1) Text("Add another place before you can delete this one.", style = body(13.sp, color = Ink.Grey))
            ErrorNote(error)
        }
        PillButton("Add a place", loading = busy != null, modifier = Modifier.padding(horizontal = 20.dp), onClick = { app.go(Screen.PlaceEdit(null)) })
    }
}

/** Add or edit one place. */
@Composable
fun PlaceEditScreen(app: AppState, screen: Screen.PlaceEdit) {
    val scope = rememberCoroutineScope()
    val existing = app.savedPlaces.firstOrNull { it.placeId == screen.placeId }
    var form by remember { mutableStateOf(existing?.toForm() ?: PlaceForm(label = if (app.savedPlaces.none { it.label == "Home" }) "Home" else "Work")) }
    var tried by remember { mutableStateOf(false) }
    var serverErrors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().dotted().padding(top = 56.dp, bottom = 34.dp)) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BackButton({ app.back() })
            Text(if (existing != null) "Edit ${existing.label}" else "New place", style = hand(30.sp))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(22.dp).noteSlideIn(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            AddressForm(form, { form = it; serverErrors = emptyMap() }, tried, serverErrors)
            if (existing?.isDefault == false || existing == null && app.savedPlaces.isNotEmpty()) {
                com.rimapps.fetchit.ui.components.Toggle("Make this my default", form.makeDefault) { form = form.copy(makeDefault = it) }
            }
            ErrorNote(error)
        }
        PillButton("Save place", loading = busy, done = done, modifier = Modifier.padding(horizontal = 20.dp), onClick = {
            tried = true; busy = true; error = null
            scope.launch {
                val saved = savePlace(app, form) { fields, message -> serverErrors = fields; error = message.takeIf { fields.isEmpty() } }
                busy = false
                if (saved != null) { done = true; delay(350); app.back() }
            }
        })
    }
}

// ---- Before buying: where should this one go? ----

/** A sheet over Results: every saved place, the chosen one ticked; "Add a place" opens the form. */
@Composable
fun BoxScope.PlacePickerSheet(app: AppState, open: Boolean, selectedId: String?, onPick: (Place) -> Unit, onDismiss: () -> Unit) {
    AnimatedVisibility(open, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
        Box(Modifier.fillMaxSize().background(Ink.Ink.copy(alpha = 0.35f)).clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss))
    }
    AnimatedVisibility(
        open, modifier = Modifier.align(Alignment.BottomCenter),
        enter = slideInVertically(tween(300)) { it } + fadeIn(tween(150)), exit = slideOutVertically(tween(250)) { it } + fadeOut(tween(150)),
    ) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)).background(Ink.Background)
                .clickable(remember { MutableInteractionSource() }, indication = null) {}
                .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 34.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).width(44.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(Ink.TrackOff))
            Text("Where should this one go?", style = hand(28.sp))
            app.savedPlaces.forEach { p ->
                val on = p.placeId == selectedId
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(if (on) Ink.White else Ink.Note)
                        .clickable { onPick(p) }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(if (on) Ink.Ink else Ink.TrackOff))
                    Column(Modifier.weight(1f)) {
                        Text(p.label + if (p.isDefault) " · default" else "", style = body(16.sp, FontWeight.SemiBold))
                        Text(p.oneLine, style = body(13.sp, color = Ink.Muted), maxLines = 1)
                    }
                }
            }
            PillButton("Add a place", dark = false, height = 50.dp, onClick = { onDismiss(); app.go(Screen.PlaceEdit(null)) })
        }
    }
}
