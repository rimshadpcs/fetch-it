package com.rimapps.fetchit.ui

import androidx.compose.ui.platform.LocalDensity
import com.rimapps.fetchit.ui.motion.wobblyLoop
import com.rimapps.fetchit.friendly
import com.rimapps.fetchit.ui.motion.blurIn
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.pointer.PointerEventPass
import com.rimapps.fetchit.ui.motion.sharedShape
import com.rimapps.fetchit.ui.motion.ScoutPeek
import com.rimapps.fetchit.ui.icons.DoodleIcon
import com.rimapps.fetchit.ui.icons.Doodle
import com.rimapps.fetchit.ui.components.PillButton
import com.rimapps.fetchit.ui.components.Chip
import com.rimapps.fetchit.ui.components.Segmented
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.rimapps.fetchit.FetchSession
import com.rimapps.fetchit.RuleFocus
import com.rimapps.fetchit.ui.motion.CountUpMoney
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.rimapps.fetchit.ui.components.Press
import com.rimapps.fetchit.ui.components.grayPress
import com.rimapps.fetchit.ui.components.pressScale
import com.rimapps.fetchit.ui.components.roundButton
import com.rimapps.fetchit.ui.motion.rememberHaptics
import com.rimapps.fetchit.ui.motion.Dog
import com.rimapps.fetchit.ui.motion.DogMood
import com.rimapps.fetchit.ui.motion.HandwritingReveal
import com.rimapps.fetchit.ui.motion.PathDraw
import com.rimapps.fetchit.ui.motion.PawTrail
import com.rimapps.fetchit.ui.motion.RunningDog
import com.rimapps.fetchit.ui.motion.outlineBox
import com.rimapps.fetchit.ui.motion.rememberProgress
import com.rimapps.fetchit.ui.motion.sharedPhoto
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import com.rimapps.fetchit.AppState
import com.rimapps.fetchit.Screen
import com.rimapps.fetchit.data.Offer
import com.rimapps.fetchit.platform.PhotoSource
import com.rimapps.fetchit.platform.rememberNotificationPermission
import com.rimapps.fetchit.platform.rememberPhotoSource

/** A tab screen: dotted paper, with room at the bottom for the floating bar (drawn once in App, see Nav.kt). */
@Composable
fun MainScaffold(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().dotted()) {
        Box(Modifier.weight(1f)) { content() }
        Gap(NAV_SPACE)
    }
}

@Composable
fun FetchScreen(app: AppState) {
    val photos = rememberPhotoSource { app.startFetch(it) }
    var mode by remember { mutableStateOf("Snap") }
    // "Needs your OK" arrives as a notification: ask once, the first time the user lands here.
    val askNotifications = rememberNotificationPermission()
    LaunchedEffect(Unit) { if (!app.askedPush) { app.askedPush = true; askNotifications() } }
    val session = app.session
    MainScaffold {
        Column(Modifier.fillMaxSize()) {
            Text("What should I fetch?", style = hand(32.sp), modifier = Modifier.padding(start = 24.dp, end = 22.dp, top = 58.dp))
            // Budget chip: a shortcut to the house rules.
            app.budgetLeft()?.let { left ->
                Row(
                    Modifier.padding(start = 20.dp, top = 6.dp).height(40.dp).grayPress({ app.go(Screen.Rules(RuleFocus.Budget)) }).background(Ink.Pill).padding(horizontal = 14.dp)
                        .semantics(mergeDescendants = true) {},
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CountUpMoney(left, serif(19.sp, Ink.Ink), from = null)
                    Text(" left this month  ›", style = body(15.sp, FontWeight.SemiBold, Ink.Muted))
                }
            }
            Segmented(listOf("Snap", "Upload"), mode, { it }, { mode = it }, Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 12.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.Divider))

            val ready = session?.response?.takeIf { !session.loading && session.error == null && it.status in setOf("results", "choose") }
            // While Results / Which one? take over, keep showing the photo: it's the shared element they fly in from.
            if (session != null && (session.loading || session.error != null || app.screen != Screen.Fetch)) {
                SniffingView(app, Modifier.weight(1f))
            } else if (ready != null) {
                // Results came back while the user was on another tab: they open them, nothing pops up on its own.
                Column(Modifier.weight(1f).padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    PaperNote(Modifier.fillMaxWidth()) {
                        Text("I found something!", style = hand(28.sp))
                        Text(
                            if (ready.status == "choose") "${ready.items.size} things in your photo" else "${ready.offers.size} matches, prices checked",
                            style = body(15.sp, color = Ink.Muted),
                        )
                    }
                    PillButton("Show me", onClick = { app.go(if (ready.status == "choose") Screen.WhichOne else Screen.Results) })
                    PillButton("Start over", dark = false, height = 52.dp, onClick = { app.session = null })
                }
            } else if (mode == "Snap") {
                Column(Modifier.weight(1f).padding(start = 22.dp, end = 22.dp, top = 6.dp, bottom = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    // Scout peeks over the top of the viewfinder (big Scout from onboarding shrinks into this).
                    ScoutPeek(96.dp, Modifier.sharedShape(SCOUT_KEY), showing = 0.6f)
                    Box(
                        Modifier.fillMaxWidth().weight(1f).rotate(-1f).background(Color(0xFF3E3C38)).clickable { photos.takePhoto() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("point me at it", style = hand(24.sp, Ink.White))
                    }
                    Gap(16.dp)
                    Box(Modifier.size(78.dp).shadow(6.dp, CircleShape).roundButton({ photos.takePhoto() }).background(Ink.White), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(58.dp).clip(CircleShape).background(Ink.Ink))
                    }
                }
            } else {
                Column(Modifier.weight(1f).padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(
                        Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(32.dp)).background(Color.White.copy(alpha = 0.6f))
                            .dashedBorder().clickable { photos.pickPhoto() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Upload a photo", style = body(20.sp, FontWeight.SemiBold))
                            Text("screenshots work too!", style = hand(21.sp, Ink.Muted), modifier = Modifier.rotate(-2f))
                        }
                    }
                }
            }
        }
    }
}

private fun Modifier.dashedBorder() = drawBehind {
    drawRoundRect(
        Color(0xFFC9C9C5), cornerRadius = CornerRadius(32.dp.toPx()),
        style = Stroke(2.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))),
    )
}

/** The photo prints as a polaroid; Scout sniffs along a paw-print trail; "I think it's a…" writes on once identify answers. */
@Composable
private fun SniffingView(app: AppState, modifier: Modifier) {
    val s = app.session ?: return
    Column(modifier.padding(start = 22.dp, end = 22.dp, top = 24.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            PrintedPolaroid(s, Modifier.fillMaxSize())
            if (s.loading) {
                Row(
                    Modifier.align(Alignment.TopEnd).offset((-8).dp, (-14).dp).shadow(4.dp, RoundedCornerShape(999.dp)).clip(RoundedCornerShape(999.dp))
                        .background(Ink.White).padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DoodleIcon(Doodle.Search, null, size = 16.dp)
                    Text(if (s.identified.isEmpty()) "Sniffing around…" else "Checking stores…", style = body(13.sp, FontWeight.SemiBold))
                }
            }
        }
        if (s.loading) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            PawTrail(Modifier.weight(1f).height(40.dp).padding(bottom = 4.dp))
            Dog(62.dp, DogMood.Sniffing)
        }
        if (s.error != null) {
            ErrorNote(s.error)
            PillButton("Try another photo", onClick = { app.session = null })
        }
    }
}

/** The user's photo, shared between Fetch, Which one? and the Results header. */
internal const val PHOTO_KEY = "fetch-photo"

/** Slides out like a fresh print and develops (dark → photo), then a strip of tape lands on top. ≈ 640 ms in all. */
@Composable
private fun PrintedPolaroid(s: FetchSession, modifier: Modifier) {
    val print = rememberProgress(s, 520)
    val tape = rememberProgress(s, 240, delayMs = 400)
    Box(modifier.graphicsLayer {
        translationY = (1f - print.value) * -56.dp.toPx()
        alpha = (print.value * 2.5f).coerceAtMost(1f)
        rotationZ = -1.5f
    }) {
        Column(Modifier.fillMaxSize().shadow(5.dp).background(Ink.White).padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 8.dp)) {
            Box(Modifier.fillMaxWidth().weight(1f).sharedPhoto(PHOTO_KEY).background(Color(0xFF3E3C38))) {
                AsyncImage(s.photo, contentDescription = "Your photo", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                Box(Modifier.matchParentSize().graphicsLayer { alpha = 1f - print.value }.background(Color(0xFF3E3C38)))
            }
            Column(Modifier.fillMaxWidth().height(56.dp).padding(start = 4.dp, top = 6.dp)) {
                val item = s.identified.firstOrNull()
                if (item == null) Text("sniffing…", style = hand(24.sp, Ink.LightGrey))
                else {
                    Text("I think it's a…", style = body(12.sp, FontWeight.SemiBold, Ink.Grey))
                    HandwritingReveal(listOfNotNull(item.item_type, item.colors.firstOrNull()).joinToString(", "), hand(24.sp))
                }
            }
        }
        Box(
            Modifier.align(Alignment.TopStart).size(92.dp, 24.dp).graphicsLayer {
                translationX = -22.dp.toPx(); translationY = 4.dp.toPx(); rotationZ = -38f
                val v = tape.value
                alpha = v; scaleX = 1.35f - 0.35f * v; scaleY = 1.35f - 0.35f * v
            }.background(Color(0xFFEDE6D3).copy(alpha = 0.88f)),
        )
    }
}

/** Several things in the photo: tap the one to fetch. */
@Composable
fun WhichOneScreen(app: AppState) {
    val s = app.session ?: return
    val items = s.response?.items ?: s.identified
    Column(Modifier.fillMaxSize().dotted().padding(top = 56.dp, bottom = 30.dp)) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BackButton({ app.back() })
            Text("Which one?", style = hand(30.sp))
        }
        Text("I spotted a few things. Tap the one you want.", style = body(16.sp, color = Ink.Muted), modifier = Modifier.padding(start = 70.dp, top = 4.dp, end = 20.dp))
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(20.dp), contentAlignment = Alignment.Center) {
            // Photo with the item boxes drawn on top; tapping a box picks it.
            Box(Modifier.fillMaxSize().sharedPhoto(PHOTO_KEY).background(Color(0xFF3E3C38))) {
                AsyncImage(s.photo, null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
                // A pen loop circles each item in turn (like the size picks), once the photo has landed: an ellipse
                // big enough to go round the item's box, overshooting where the pen started.
                val pad = with(LocalDensity.current) { 10.dp.toPx() }
                items.forEachIndexed { i, item ->
                    val b = item.box ?: return@forEachIndexed
                    PathDraw(Modifier.matchParentSize(), color = if (i == 0) Ink.Accent else Color.White, strokeWidth = 3.5.dp, durationMs = 450, delayMs = 300 + 140 * i, key = item) { size ->
                        val box = Rect(Offset(b.x.toFloat() * size.width, b.y.toFloat() * size.height), Size(b.width.toFloat() * size.width, b.height.toFloat() * size.height))
                        // An ellipse through a box's corners is √2 × the box; a bit less still clears it with the wobble.
                        val w = box.width * 1.28f + pad; val h = box.height * 1.24f + pad
                        // Kept inside the photo (a tall item would otherwise loop off its edge).
                        val loop = Rect(box.center.x - w / 2, box.center.y - h / 2, box.center.x + w / 2, box.center.y + h / 2)
                            .intersect(Rect(pad / 2, pad / 2, size.width - pad / 2, size.height - pad / 2))
                        listOf(wobblyLoop(loop, seed = i + 2))
                    }
                }
            }
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items.forEachIndexed { i, item ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(999.dp)).background(if (i == 0) Ink.Ink else Ink.Pill)
                        .clickable(enabled = !s.loading) { app.chooseItem(i) }.padding(horizontal = 22.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(item.item_type.ifBlank { item.search_query }, style = hand(22.sp, if (i == 0) Ink.White else Ink.Ink), modifier = Modifier.weight(1f))
                    item.colors.firstOrNull()?.let { Text(it, style = serif(16.sp, if (i == 0) Ink.LightGrey else Ink.Muted)) }
                }
            }
            if (s.loading) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                RunningDog(48.dp)
                Text("Checking stores…", style = body(14.sp, FontWeight.SemiBold))
            }
            ErrorNote(s.error)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ResultsScreen(app: AppState) {
    val r = app.session?.response ?: return
    val limit = app.rules?.perItemLimit
    var sort by remember { mutableStateOf("Best") }
    var underLimit by remember { mutableStateOf(false) }
    val cheapest = r.offers.minByOrNull { it.price }
    val shown = r.offers.let { if (sort == "Cheapest") it.sortedBy { o -> o.price } else it }.filter { !underLimit || limit == null || it.price <= limit }
    var picked by remember { mutableStateOf(r.offers.firstOrNull()) }
    // "More matches" (findSimilar), loaded on request into the polaroid fan; a fan pick is what Buy buys too.
    var more by remember { mutableStateOf<List<Offer>>(emptyList()) }
    var moreLoading by remember { mutableStateOf(false) }
    var moreError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val offer = picked?.takeIf { it in shown || it in more } ?: shown.firstOrNull()
    var size by remember(offer) { mutableStateOf(offer?.let { defaultSize(app, it) }) }
    // Where it ships: the default place unless the user picks another in the sheet.
    var placeId by remember { mutableStateOf(app.defaultPlace?.placeId) }
    val place = app.savedPlaces.firstOrNull { it.placeId == placeId } ?: app.defaultPlace
    var picking by remember { mutableStateOf(false) }
    // Buy only counts a press that starts after this screen has been on the display for a moment: a tap aimed
    // at the screen underneath (the Fetch "+" sits where Buy lands) must never buy. Measured from the first
    // draw, not composition, because a slow device can compose long before it shows anything. Closing the
    // place sheet re-arms it the same way (the sheet's rows sit above Buy).
    var drawn by remember { mutableStateOf(false) }
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(drawn, picking) { armed = false; if (drawn && !picking) { delay(800); armed = true } }

    // Scout (peeking over the Buy bar) looks wherever the user last tapped.
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }
    var lastTap by remember { mutableStateOf<Offset?>(null) }
    var scoutCenter by remember { mutableStateOf(Offset.Zero) }
    val scoutLook = lastTap?.let { t ->
        val d = t - scoutCenter
        val len = kotlin.math.sqrt(d.x * d.x + d.y * d.y).coerceAtLeast(1f)
        Offset(d.x / len, d.y / len)
    }
    Box(
        Modifier.fillMaxSize().dotted()
            .onGloballyPositioned { rootOrigin = it.positionInRoot() }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial) // watch only: never consumes
                        e.changes.firstOrNull { it.pressed && !it.previousPressed }?.let { lastTap = rootOrigin + it.position }
                    }
                }
            },
    ) {
        Column(Modifier.fillMaxSize().padding(top = 56.dp)) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BackButton({ app.session = null; app.reset(Screen.Fetch) })
                Text("Look what I found", style = hand(30.sp), modifier = Modifier.weight(1f))
                app.session?.photo?.let { photo ->
                    Box(Modifier.padding(end = 6.dp).rotate(4f).shadow(3.dp).background(Ink.White).padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 10.dp)) {
                        AsyncImage(photo, "Your photo", contentScale = ContentScale.Crop, modifier = Modifier.size(46.dp).sharedPhoto(PHOTO_KEY))
                    }
                }
            }
            Row(Modifier.padding(start = 70.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(r.offers.size.toString(), style = serif(18.sp, Ink.Muted))
                Text("matches · ${r.offers.map { it.merchant }.distinct().size} stores", style = body(16.sp, FontWeight.SemiBold, Ink.Muted))
                Box(Modifier.shadow(3.dp, RoundedCornerShape(999.dp)).clip(RoundedCornerShape(999.dp)).background(Ink.White).padding(horizontal = 10.dp, vertical = 5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        DoodleIcon(Doodle.Check, null, size = 14.dp, strokeWidth = 5f)
                        Text("prices checked", style = body(12.sp, FontWeight.SemiBold))
                    }
                }
            }
            Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Segmented(listOf("Best", "Cheapest"), sort, { it }, { sort = it }, Modifier.width(200.dp))
                if (limit != null) Chip("Under ${money(limit)}", underLimit, onClick = { underLimit = !underLimit })
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.Divider))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 200.dp)) {
                if (shown.isEmpty()) Text("Nothing under your limit. Turn the filter off to see everything.", style = hand(20.sp, Ink.Muted))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    listOf(0, 1).forEach { col ->
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                            shown.filterIndexed { i, _ -> i % 2 == col }.forEachIndexed { j, o ->
                                val index = j * 2 + col
                                Box(Modifier.blurIn(index)) {
                                    OfferCard(
                                        o, selected = o == offer, height = if ((index % 3) == 0) 196.dp else 160.dp, tilt = if (index % 2 == 0) -1.2f else 1.2f,
                                        caption = when {
                                            limit != null && o.price > limit -> "over limit, I'd ask you"
                                            index == 0 && sort == "Best" -> "my pick: closest match"
                                            o == cheapest -> "cheapest${o.color?.let { ", in ${it.lowercase()}" } ?: ""}"
                                            else -> null
                                        },
                                        warn = limit != null && o.price > limit,
                                        saved = app.isSaved(o), onToggleSave = { app.toggleSaved(o) },
                                        onClick = { picked = o },
                                    )
                                }
                            }
                        }
                    }
                }
                // More matches: a swipeable fan of polaroids (look-alikes of the top pick), loaded on request.
                Gap(26.dp)
                if (more.isEmpty()) {
                    val source = r.offers.firstOrNull()
                    if (source != null) PillButton(
                        "More matches", dark = false, height = 50.dp, loading = moreLoading,
                        onClick = {
                            moreLoading = true; moreError = null
                            scope.launch {
                                runCatching { app.api.findSimilar(source.offerId) }
                                    .onSuccess { found -> more = found.filter { f -> r.offers.none { it.productId == f.productId && it.merchant == f.merchant } }; if (more.isEmpty()) moreError = "Nothing else like it right now." }
                                    .onFailure { moreError = it.friendly() }
                                moreLoading = false
                            }
                        },
                    )
                    ErrorNote(moreError, Modifier.padding(top = 8.dp))
                } else {
                    Text("more matches · swipe", style = hand(22.sp, Ink.Muted))
                    PolaroidFan(more, offer, onPick = { picked = it }, modifier = Modifier.blurIn(0))
                }
            }
        }
        // Bottom: chosen offer's size, ship-to, Buy.
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Ink.Background.copy(alpha = 0f), Ink.Background, Ink.Background)))
                .padding(start = 16.dp, end = 16.dp, top = 30.dp, bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ScoutPeek(
                54.dp, Modifier.align(Alignment.End).padding(end = 18.dp)
                    .onGloballyPositioned { c -> scoutCenter = c.positionInRoot() + Offset(c.size.width / 2f, c.size.height / 2f) },
                showing = 0.6f, lookAt = scoutLook, glance = false,
            )
            val sizes = offer?.sizes.orEmpty()
            if (sizes.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    sizes.forEach { s -> Chip(s.label, s.label == size, onClick = { size = s.label }) }
                }
            }
            Row(
                Modifier.fillMaxWidth().height(64.dp).shadow(8.dp, RoundedCornerShape(999.dp)).clip(RoundedCornerShape(999.dp)).background(Ink.White).padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.widthIn(max = 120.dp).clip(RoundedCornerShape(999.dp)).clickable(role = Role.Button) { picking = true }.padding(start = 16.dp, end = 10.dp, top = 6.dp, bottom = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        DoodleIcon(Doodle.MapPin, null, size = 13.dp, color = Ink.Grey, strokeWidth = 4.6f)
                        Text("ship to ▾", style = body(11.sp, FontWeight.SemiBold, Ink.Grey))
                    }
                    Text(place?.label ?: "add address", style = body(16.sp, FontWeight.SemiBold), maxLines = 1)
                }
                Box(
                    Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(999.dp)).background(Ink.Ink)
                        .drawWithContent { drawContent(); drawn = true }
                        .buyGesture(enabled = armed && offer != null && place != null) {
                            app.go(Screen.Buying(offer!!, size, newRequestId(), place!!.placeId))
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Buy · ", style = body(16.sp, FontWeight.SemiBold, Ink.White))
                        Text(money(offer?.price, offer?.currency ?: "USD"), style = serif(20.sp, Ink.White))
                    }
                }
            }
        }
        PlacePickerSheet(app, picking, place?.placeId, onPick = { placeId = it.placeId; picking = false }, onDismiss = { picking = false })
    }
}

/**
 * A tap that only counts when the press *starts* while [enabled]: a finger already down when Buy appears or
 * arms can't complete a purchase on release. Keeps button semantics for accessibility.
 */
@Composable
private fun Modifier.buyGesture(enabled: Boolean, onBuy: () -> Unit): Modifier {
    // Press feel (sink 0.96, spring back, light haptic) is visual only; the armed check below is what counts.
    val source = remember { MutableInteractionSource() }
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    return this
        .pressScale(source, Press.PILL)
        .semantics { role = Role.Button; if (enabled) onClick { onBuy(); true } else disabled() }
        .pointerInput(enabled) {
            detectTapGestures(onPress = { offset ->
                val armedAtPress = enabled
                val press = PressInteraction.Press(offset)
                scope.launch { source.emit(press) }
                val released = tryAwaitRelease()
                scope.launch { source.emit(if (released) PressInteraction.Release(press) else PressInteraction.Cancel(press)) }
                if (released && armedAtPress) { haptics.tap(); onBuy() }
            })
        }
}

@OptIn(ExperimentalUuidApi::class)
private fun newRequestId() = Uuid.random().toHexString()

/** The user's saved size, if this offer sells it in stock (otherwise none: the rules then ask). */
private fun defaultSize(app: AppState, o: Offer): String? {
    val sizes = o.sizes ?: return null
    val mine = listOfNotNull(app.sizes.top, app.sizes.waist, app.sizes.shoe?.substringAfter(' '))
    return sizes.firstOrNull { s -> mine.any { it.equals(s.size, true) || it.equals(s.label, true) } }?.label
}

@Composable
private fun OfferCard(o: Offer, selected: Boolean, height: androidx.compose.ui.unit.Dp, tilt: Float, caption: String?, warn: Boolean, saved: Boolean, onToggleSave: () -> Unit, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        Box(Modifier.rotate(tilt).then(if (selected) Modifier.border(3.dp, Ink.Ink) else Modifier)) {
            ProductImage(o.imageUrl, Modifier.fillMaxWidth().height(height))
            HeartButton(saved, onToggleSave, Modifier.align(Alignment.TopEnd).padding(6.dp))
        }
        Column(Modifier.padding(start = 6.dp, end = 6.dp, top = 8.dp)) {
            Row {
                Text(o.merchant.removePrefix("www."), style = body(14.sp, FontWeight.SemiBold, Ink.Muted), maxLines = 1, modifier = Modifier.weight(1f))
                Text(money(o.price, o.currency), style = serif(17.sp))
            }
            Text(o.title, style = body(12.sp, color = Ink.Grey), maxLines = 2)
            // Scout's pick gets a star and a doodle arrow pointing back up at the photo.
            if (caption != null && caption.startsWith("my pick")) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                DoodleIcon(Doodle.Star, null, size = 18.dp)
                Text(caption, style = hand(19.sp), modifier = Modifier.weight(1f, fill = false))
                DoodleIcon(Doodle.ArrowUpRight, null, size = 18.dp, color = Ink.Muted)
            } else if (caption != null) Text(caption, style = hand(19.sp, if (warn) Ink.Warning else Ink.Ink))
        }
    }
}
