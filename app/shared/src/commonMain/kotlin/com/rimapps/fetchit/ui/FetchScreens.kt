package com.rimapps.fetchit.ui

import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.StrokeCap
import kotlinx.datetime.toLocalDateTime
import com.rimapps.fetchit.ui.motion.riseIn
import com.rimapps.fetchit.ui.motion.PawLoader
import com.rimapps.fetchit.SharedLink
import com.rimapps.fetchit.ui.motion.popIn
import com.rimapps.fetchit.snapKey
import androidx.compose.ui.text.style.TextDecoration
import com.rimapps.fetchit.data.FetchResponse
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.key
import com.rimapps.fetchit.ui.motion.LocalReduceMotion
import com.rimapps.fetchit.ui.components.CheckMark
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.semantics.contentDescription
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
            // Header: title with the Saved (heart) and Price watches (bell) shortcuts on the right, today's date under it,
            // then a row of pills: what's left this month (black, → house rules) and the snaps album.
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 14.dp, top = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("What should I fetch?", style = hand(30.sp), modifier = Modifier.weight(1f))
                HeaderIcon(Doodle.Heart, "Saved for later", app.saved.size, Ink.Accent, filled = app.saved.isNotEmpty()) { app.go(Screen.Saved) }
                app.watches.filter { it.watching }.let { live ->
                    // Green dot: something watched is cheaper than when watching started; red: no drop yet.
                    HeaderIcon(Doodle.PriceTag, "Price watches", live.size, Ink.Ink, badge = if (live.any { it.dropped }) Ink.Drop else Ink.Accent) { app.go(Screen.Watches) }
                }
            }
            TodayLine(Modifier.padding(start = 24.dp, top = 2.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                app.budgetLeft()?.let { left ->
                    Row(
                        Modifier.height(44.dp).grayPress({ app.go(Screen.Rules(RuleFocus.Budget)) }).background(Ink.Ink).padding(horizontal = 16.dp)
                            .semantics(mergeDescendants = true) {},
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CountUpMoney(left, serif(19.sp, Ink.White), from = null)
                        Text(" left this month", style = body(15.sp, FontWeight.SemiBold, Ink.White.copy(alpha = 0.75f)), maxLines = 1)
                    }
                }
                SnapsChip(app)
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
            } else {
                // Scout peeks over the top of the card on both Snap and Upload (big Scout from onboarding shrinks into
                // this), and stays put when you switch between them.
                Column(Modifier.weight(1f).padding(start = 22.dp, end = 22.dp, top = 6.dp, bottom = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    app.sharedLink?.let { SharedLinkNote(app, it) }
                    ScoutPeek(96.dp, Modifier.sharedShape(SCOUT_KEY), showing = 0.6f)
                    if (mode == "Snap") {
                        Box(
                            Modifier.fillMaxWidth().weight(1f).rotate(-1f).background(Color(0xFF3E3C38)).viewfinderCorners().clickable { photos.takePhoto() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("point me at it", style = hand(24.sp, Ink.White))
                        }
                        Gap(16.dp)
                        Box(Modifier.size(78.dp).shadow(6.dp, CircleShape).roundButton({ photos.takePhoto() }).background(Ink.White), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(58.dp).clip(CircleShape).background(Ink.Ink))
                        }
                    } else {
                        Box(
                            Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(32.dp)).background(Color.White.copy(alpha = 0.6f))
                                .dashedBorder().clickable { photos.pickPhoto() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                DoodleIcon(Doodle.Upload, null, size = 56.dp, strokeWidth = 3.6f)
                                Text("Upload a photo", style = body(20.sp, FontWeight.SemiBold))
                                Text("screenshots work too!", style = hand(21.sp, Ink.Muted), modifier = Modifier.rotate(-2f))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A bare doodle icon button (like the reference's header icons) with a small black count badge. */
@Composable
private fun HeaderIcon(icon: Doodle, label: String, count: Int, color: Color, filled: Boolean = false, badge: Color = Ink.Ink, onClick: () -> Unit) {
    Box(
        Modifier.size(46.dp).roundButton(onClick).semantics(mergeDescendants = true) { contentDescription = if (count > 0) "$label, $count" else label },
        contentAlignment = Alignment.Center,
    ) {
        DoodleIcon(icon, null, size = 27.dp, color = color, strokeWidth = 4.2f, filled = filled)
        if (count > 0) Box(
            Modifier.align(Alignment.TopEnd).padding(top = 5.dp, end = 3.dp).size(18.dp).clip(CircleShape).background(badge),
            contentAlignment = Alignment.Center,
        ) { Text(if (count > 9) "9+" else "$count", style = body(10.sp, FontWeight.Bold, Ink.White)) }
    }
}

/** "7th November": the day in serif italic, the month in the body font (as in the scrapbook reference). */
@Composable
private fun TodayLine(modifier: Modifier = Modifier) {
    val d = kotlin.time.Clock.System.now().toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault()).date
    val n = d.day
    val suffix = if (n % 100 in 11..13) "th" else when (n % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        Text("$n$suffix", style = serif(19.sp, Ink.Muted))
        Text(" " + d.month.name.lowercase().replaceFirstChar { it.uppercase() }, style = body(17.sp, FontWeight.Medium, Ink.Muted))
    }
}

/** A camera viewfinder's four corner brackets, in white, inset from the edges. */
private fun Modifier.viewfinderCorners() = drawWithContent {
    drawContent()
    val inset = 18.dp.toPx(); val arm = 34.dp.toPx(); val w = 4.dp.toPx()
    val c = Color.White.copy(alpha = 0.9f)
    val l = inset; val t = inset; val r = size.width - inset; val b = size.height - inset
    listOf(
        listOf(Offset(l, t + arm), Offset(l, t), Offset(l + arm, t)),
        listOf(Offset(r - arm, t), Offset(r, t), Offset(r, t + arm)),
        listOf(Offset(r, b - arm), Offset(r, b), Offset(r - arm, b)),
        listOf(Offset(l + arm, b), Offset(l, b), Offset(l, b - arm)),
    ).forEach { (a, corner, z) ->
        drawPath(androidx.compose.ui.graphics.Path().apply { moveTo(a.x, a.y); lineTo(corner.x, corner.y); lineTo(z.x, z.y) }, c, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
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
                    PathDraw(Modifier.matchParentSize(), color = if (i in s.picked) Ink.Accent else Color.White, strokeWidth = 3.5.dp, durationMs = 450, delayMs = 300 + 140 * i, key = item) { size ->
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
                // Nothing is picked to start; tapping an item ticks it and fetches it right away.
                ItemPickRow(item.item_type.ifBlank { item.search_query }, item.colors.firstOrNull(), i in s.picked, enabled = !s.loading) {
                    s.picked = setOf(i)
                    app.chooseItems(listOf(i))
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

/** A detected item: tapping fills it black from the left and draws a ✓ while it's fetched. */
@Composable
private fun ItemPickRow(label: String, color: String?, on: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val reduce = LocalReduceMotion.current
    val fill by animateFloatAsState(if (on) 1f else 0f, tween(if (reduce) 120 else 260), label = "pickFill")
    val fg by animateColorAsState(if (on) Ink.White else Ink.Ink, tween(200), label = "pickFg")
    Box(
        Modifier.fillMaxWidth().height(58.dp).clip(RoundedCornerShape(999.dp)).background(Ink.Pill)
            .clickable(enabled = enabled, role = Role.Checkbox, onClick = onClick)
            .semantics { stateDescription = if (on) "picked" else "not picked" },
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fill).background(Ink.Ink))
        Row(Modifier.fillMaxSize().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (on) CheckMark(Ink.White, Modifier.size(20.dp), durationMs = 240, delayMs = 100, strokeWidth = 2.6.dp)
            Text(label, style = hand(22.sp, fg), modifier = Modifier.weight(1f))
            color?.let { Text(it, style = serif(16.sp, if (on) Ink.LightGrey else Ink.Muted)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ResultsScreen(app: AppState) {
    val s = app.session ?: return
    // One view per searched item; switching tabs starts that item's view fresh (sort, pick, more matches).
    val shown = s.shown ?: return
    key(s.current) { ResultsFor(app, s, shown) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResultsFor(app: AppState, s: FetchSession, r: FetchResponse) {
    val limit = app.rules?.perItemLimit
    var sort by remember { mutableStateOf("Best") }
    var underLimit by remember { mutableStateOf(false) }
    val cheapest = r.offers.minByOrNull { it.price }
    // Searching a Snap again: what's new or cheaper than last time.
    val before = s.before?.associate { it.key to it.price }
    fun wasPrice(o: Offer) = before?.get(o.snapKey())?.takeIf { o.price < it - 0.005 }
    fun isNew(o: Offer) = before != null && o.snapKey() !in before
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
    var watching by remember { mutableStateOf(false) }
    // Send as a gift: ships to that person's saved place, from the gift budget.
    var gift by remember { mutableStateOf<GiftChoice?>(null) }
    var gifting by remember { mutableStateOf(false) }
    // Buy only counts a press that starts after this screen has been on the display for a moment: a tap aimed
    // at the screen underneath (the Fetch "+" sits where Buy lands) must never buy. Measured from the first
    // draw, not composition, because a slow device can compose long before it shows anything. Closing the
    // place sheet re-arms it the same way (the sheet's rows sit above Buy).
    var drawn by remember { mutableStateOf(false) }
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(drawn, picking, watching, gifting) { armed = false; if (drawn && !picking && !watching && !gifting) { delay(800); armed = true } }

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
            if (before != null) {
                val newCount = r.offers.count { isNew(it) }
                val cheaper = r.offers.count { wasPrice(it) != null }
                val since = s.beforeAt?.let { " since ${shortDate(it)}" } ?: ""
                Text(
                    when {
                        newCount == 0 && cheaper == 0 -> "Nothing new$since."
                        else -> listOfNotNull(
                            if (newCount > 0) "$newCount new" else null,
                            if (cheaper > 0) "$cheaper cheaper" else null,
                        ).joinToString(" · ") + since
                    },
                    style = hand(22.sp, if (newCount + cheaper > 0) Ink.Accent else Ink.Muted),
                    modifier = Modifier.padding(start = 70.dp, top = 4.dp).riseIn(300),
                )
            }
            // Several things searched: a tab for each.
            if (s.results.size + s.resultErrors.size > 1) {
                val names = s.response?.items.orEmpty()
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (s.results.keys + s.resultErrors.keys).sorted().forEach { i ->
                        val label = names.getOrNull(i)?.let { it.item_type.ifBlank { it.search_query } } ?: "item ${i + 1}"
                        if (i in s.results) Chip(label, s.current == i, onClick = { s.current = i })
                        else Chip("$label · none found", false, onClick = {})
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
                                        isNew = isNew(o), was = wasPrice(o),
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
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                // Watch the price instead of buying now (one watch per item; tapping again changes or stops it).
                if (offer != null) WatchChip(app.watchFor(offer), onClick = { watching = true }, modifier = Modifier.padding(bottom = 4.dp))
                if (offer != null) GiftChip(gift, onClick = { gifting = true }, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
                Spacer(Modifier.weight(1f))
                ScoutPeek(
                    54.dp, Modifier.padding(end = 18.dp)
                        .onGloballyPositioned { c -> scoutCenter = c.positionInRoot() + Offset(c.size.width / 2f, c.size.height / 2f) },
                    showing = 0.6f, lookAt = scoutLook, glance = false,
                )
            }
            val sizes = offer?.sizes.orEmpty()
            // A gift is for someone else: never your saved size; their size must be picked before Buy.
            val needsGiftSize = gift != null && sizes.isNotEmpty() && size == null
            if (sizes.isNotEmpty()) {
                if (gift != null) Text(if (size == null) "Which size for ${gift!!.to}?" else "Size for ${gift!!.to}", style = hand(19.sp, if (size == null) Ink.Accent else Ink.Muted))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    sizes.forEach { s -> Chip(s.label, s.label == size, onClick = { size = s.label }) }
                }
            }
            Row(
                Modifier.fillMaxWidth().height(64.dp).shadow(8.dp, RoundedCornerShape(999.dp)).clip(RoundedCornerShape(999.dp)).background(Ink.White).padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.widthIn(max = 120.dp).clip(RoundedCornerShape(999.dp)).clickable(role = Role.Button) { if (gift != null) gifting = true else picking = true }.padding(start = 16.dp, end = 10.dp, top = 6.dp, bottom = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        DoodleIcon(Doodle.MapPin, null, size = 13.dp, color = Ink.Grey, strokeWidth = 4.6f)
                        Text(if (gift != null) "gift to ▾" else "ship to ▾", style = body(11.sp, FontWeight.SemiBold, Ink.Grey))
                    }
                    Text(gift?.to ?: place?.label ?: "add address", style = body(16.sp, FontWeight.SemiBold), maxLines = 1)
                }
                Box(
                    Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(999.dp)).background(Ink.Ink)
                        .drawWithContent { drawContent(); drawn = true }
                        .buyGesture(enabled = armed && offer != null && place != null && !needsGiftSize) {
                            val g = gift
                            app.go(if (g != null) Screen.Buying(offer!!, size, newRequestId(), g.placeId, g.note) else Screen.Buying(offer!!, size, newRequestId(), place!!.placeId))
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (needsGiftSize) Text("Pick ${gift!!.to}'s size", style = body(16.sp, FontWeight.SemiBold, Ink.White.copy(alpha = 0.6f)))
                    else Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Buy · ", style = body(16.sp, FontWeight.SemiBold, Ink.White))
                        Text(money(offer?.price, offer?.currency ?: "USD"), style = serif(20.sp, Ink.White))
                    }
                }
            }
        }
        PlacePickerSheet(app, picking, place?.placeId, onPick = { placeId = it.placeId; picking = false }, onDismiss = { picking = false })
        WatchSheet(app, watching, offer, size, place?.placeId, onDismiss = { watching = false })
        GiftSheet(app, gifting, gift, onPick = {
            // Switching to a gift drops your own size; back to yourself restores it.
            if ((it == null) != (gift == null)) size = if (it == null) offer?.let { o -> defaultSize(app, o) } else null
            gift = it; gifting = false
        }, onDismiss = { gifting = false })
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
private fun OfferCard(o: Offer, selected: Boolean, height: androidx.compose.ui.unit.Dp, tilt: Float, caption: String?, warn: Boolean, saved: Boolean, onToggleSave: () -> Unit, isNew: Boolean = false, was: Double? = null, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        Box(Modifier.rotate(tilt).then(if (selected) Modifier.border(3.dp, Ink.Ink) else Modifier)) {
            ProductImage(o.imageUrl, Modifier.fillMaxWidth().height(height))
            HeartButton(saved, onToggleSave, Modifier.align(Alignment.TopEnd).padding(6.dp))
            // Searched again: a strip of tape marks a match that wasn't there last time.
            if (isNew) Text(
                "new!", style = hand(18.sp, Ink.Accent),
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp).rotate(-6f).popIn(250)
                    .background(Color(0xFFEDE6D3).copy(alpha = 0.95f)).padding(horizontal = 8.dp, vertical = 1.dp),
            )
        }
        Column(Modifier.padding(start = 6.dp, end = 6.dp, top = 8.dp)) {
            Row {
                Text(o.merchant.removePrefix("www."), style = body(14.sp, FontWeight.SemiBold, Ink.Muted), maxLines = 1, modifier = Modifier.weight(1f))
                if (was != null) Text(money(was, o.currency), style = serif(14.sp, Ink.Grey).copy(textDecoration = TextDecoration.LineThrough), modifier = Modifier.padding(end = 4.dp))
                Text(money(o.price, o.currency), style = serif(17.sp, if (was != null) Ink.Accent else Ink.Ink))
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

/** "Nov 5" (or "today"). */
@OptIn(kotlin.time.ExperimentalTime::class)
fun shortDate(millis: Long): String {
    val tz = kotlinx.datetime.TimeZone.currentSystemDefault()
    val d = kotlin.time.Instant.fromEpochMilliseconds(millis).toLocalDateTime(tz).date
    val today = kotlin.time.Clock.System.now().toLocalDateTime(tz).date
    return if (d == today) "earlier today" else "${d.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }} ${d.day}"
}

/** A link shared from Pinterest / a store page while the backend finds its photo, or why it couldn't. */
@Composable
private fun SharedLinkNote(app: AppState, link: SharedLink) {
    PaperNote(Modifier.fillMaxWidth().padding(bottom = 10.dp).riseIn(), tilt = 0.8f) {
        if (link.error == null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Getting the photo from ${link.from}…", style = hand(22.sp), modifier = Modifier.weight(1f))
                PawLoader(Ink.Ink, Modifier.size(48.dp, 18.dp))
            }
        } else {
            Text(link.error, style = body(15.sp, FontWeight.Medium, Ink.Warning))
            Text("ok", style = body(14.sp, FontWeight.SemiBold, Ink.Muted), modifier = Modifier.clickable { app.sharedLink = null }.padding(top = 2.dp))
        }
    }
}
