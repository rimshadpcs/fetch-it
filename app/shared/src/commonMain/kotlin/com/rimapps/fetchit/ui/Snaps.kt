package com.rimapps.fetchit.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.rimapps.fetchit.AppState
import com.rimapps.fetchit.Screen
import com.rimapps.fetchit.Snap
import com.rimapps.fetchit.platform.PhotoFiles
import com.rimapps.fetchit.ui.components.CheckMark
import com.rimapps.fetchit.ui.components.Chip
import com.rimapps.fetchit.ui.components.PillButton
import com.rimapps.fetchit.ui.motion.blurIn
import com.rimapps.fetchit.ui.motion.pinTilt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Faint graph-paper lines (the Snaps album). */
fun Modifier.graphPaper(): Modifier = this.background(Ink.Background).drawBehind {
    val step = 22.dp.toPx()
    val line = Color(0xFFE8E8E4)
    var x = 0f
    while (x < size.width) { drawLine(line, Offset(x, 0f), Offset(x, size.height), 1f); x += step }
    var y = 0f
    while (y < size.height) { drawLine(line, Offset(0f, y), Offset(size.width, y), 1f); y += step }
}

/** The snap's photo, read from the phone off the main thread. */
@Composable
private fun rememberSnapPhoto(id: String): ByteArray? {
    val bytes by produceState<ByteArray?>(null, id) { value = withContext(Dispatchers.Default) { PhotoFiles.load(id) } }
    return bytes
}

/**
 * A snap as a postage stamp: the photo inset in white paper with perforated edges (bites the colour of
 * [paper] along every side), a soft shadow, a small tilt.
 */
@Composable
fun PostageStamp(id: String, modifier: Modifier = Modifier, tilt: Float = 0f, paper: Color = Ink.Background, perf: Dp = 5.dp) {
    val photo = rememberSnapPhoto(id)
    Box(
        modifier.rotate(tilt).aspectRatio(0.8f).shadow(2.dp).background(Ink.White)
            .drawBehind {
                // Perforations: half-circles bitten out of every edge.
                val r = perf.toPx() / 2
                val gap = perf.toPx() * 1.7f
                var x = gap / 2
                while (x < size.width) { drawCircle(paper, r, Offset(x, 0f)); drawCircle(paper, r, Offset(x, size.height)); x += gap }
                var y = gap / 2
                while (y < size.height) { drawCircle(paper, r, Offset(0f, y)); drawCircle(paper, r, Offset(size.width, y)); y += gap }
            }
            .padding(perf * 1.6f),
    ) {
        Box(Modifier.fillMaxSize().background(Color(0xFF3E3C38))) {
            if (photo != null) AsyncImage(photo, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

/** A strip of the newest snaps on Fetch: tap one to open it in the album; "see all" opens the album. */
@Composable
fun SnapsStrip(app: AppState, modifier: Modifier = Modifier) {
    if (app.snaps.isEmpty()) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("your snaps", style = hand(20.sp, Ink.Muted), modifier = Modifier.weight(1f))
            Text(
                "see all ›", style = body(13.sp, FontWeight.SemiBold, Ink.Muted),
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).clickable { app.go(Screen.Snaps()) }.padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 22.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(app.snaps.take(12), key = { it.id }) { snap ->
                PostageStamp(
                    snap.id,
                    Modifier.width(54.dp).clickable(role = Role.Button) { app.go(Screen.Snaps(open = snap.id)) }
                        .semantics { contentDescription = snap.label ?: "snap" },
                    tilt = pinTilt(snap.id.hashCode(), 3f), perf = 3.dp,
                )
            }
        }
    }
}

/**
 * Every photo fetched, as stamps on graph paper (kept on this phone only). Tap one: its sheet with Search
 * again. Select: tick several and delete them.
 */
@Composable
fun SnapsScreen(app: AppState, screen: Screen.Snaps) {
    var open by remember { mutableStateOf(screen.open?.let { id -> app.snaps.firstOrNull { it.id == id } }) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var confirm by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().graphPaper()) {
        Column(Modifier.fillMaxSize().padding(top = 56.dp)) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BackButton({ app.back() })
                Text("Your snaps", style = hand(30.sp))
                Text("${app.snaps.size}", style = serif(18.sp, Ink.Muted), modifier = Modifier.weight(1f))
                if (app.snaps.isNotEmpty()) Chip(if (selecting) "Done" else "Select", selecting, onClick = { selecting = !selecting; selected = emptySet(); confirm = false })
            }
            Text(
                "Everything you've fetched, kept on this phone. Search one again to see what's new.",
                style = body(14.sp, color = Ink.Muted), modifier = Modifier.padding(start = 70.dp, end = 20.dp, top = 4.dp),
            )
            if (app.snaps.isEmpty()) Text("No snaps yet. Fetch something!", style = hand(22.sp, Ink.Muted), modifier = Modifier.padding(32.dp))
            LazyVerticalGrid(
                GridCells.Fixed(3), Modifier.weight(1f),
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 120.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                itemsIndexed(app.snaps, key = { _, s -> s.id }) { i, snap ->
                    val on = snap.id in selected
                    Column(
                        Modifier.blurIn(i).clickable(remember { MutableInteractionSource() }, indication = null) {
                            if (selecting) { selected = if (on) selected - snap.id else selected + snap.id; confirm = false } else open = snap
                        }.semantics { if (selecting) stateDescription = if (on) "selected" else "not selected" },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box {
                            PostageStamp(snap.id, Modifier.fillMaxWidth(), tilt = pinTilt(snap.id.hashCode(), 2.5f))
                            if (selecting) SelectDot(on, Modifier.align(Alignment.BottomStart).padding(4.dp))
                        }
                        Text(snap.label ?: "…", style = hand(16.sp), maxLines = 1, modifier = Modifier.padding(top = 6.dp))
                        Text(shortDate(snap.createdAt), style = body(11.sp, color = Ink.Grey))
                    }
                }
            }
        }
        // Select mode: delete the ticked ones (tap twice).
        AnimatedVisibility(
            selecting && selected.isNotEmpty(), modifier = Modifier.align(Alignment.BottomCenter).padding(start = 20.dp, end = 20.dp, bottom = 34.dp),
            enter = slideInVertically(tween(250)) { it } + fadeIn(tween(200)), exit = slideOutVertically(tween(200)) { it } + fadeOut(tween(150)),
        ) {
            Box(
                Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(999.dp)).background(Ink.Accent)
                    .clickable(role = Role.Button) {
                        if (!confirm) confirm = true
                        else { app.deleteSnaps(selected); selected = emptySet(); selecting = false; confirm = false }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(if (confirm) "Tap again to delete ${selected.size}" else "Delete ${selected.size}", style = body(16.sp, FontWeight.SemiBold, Ink.White))
            }
        }
        SnapSheet(app, open, onDismiss = { open = null })
    }
}

@Composable
private fun SelectDot(on: Boolean, modifier: Modifier) {
    Box(
        modifier.size(24.dp).clip(CircleShape).background(if (on) Ink.Ink else Ink.White.copy(alpha = 0.9f)).border(1.5.dp, Ink.Ink, CircleShape),
        contentAlignment = Alignment.Center,
    ) { if (on) CheckMark(Ink.White, Modifier.size(14.dp), durationMs = 200, strokeWidth = 2.dp) }
}

/** One snap: the stamp, what it was, when it was last searched, and Search again / Delete. */
@Composable
private fun SnapSheet(app: AppState, snap: Snap?, onDismiss: () -> Unit) {
    var confirmDelete by remember(snap?.id) { mutableStateOf(false) }
    AnimatedVisibility(snap != null, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
        Box(Modifier.fillMaxSize().background(Ink.Ink.copy(alpha = 0.35f)).clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss))
    }
    AnimatedVisibility(
        snap != null, enter = slideInVertically(tween(300)) { it } + fadeIn(tween(150)), exit = slideOutVertically(tween(250)) { it } + fadeOut(tween(150)),
        modifier = Modifier.fillMaxSize(),
    ) {
        val s = snap ?: return@AnimatedVisibility
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().clip(RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)).background(Ink.Background)
                    .clickable(remember { MutableInteractionSource() }, indication = null) {}
                    .padding(start = 22.dp, end = 22.dp, top = 12.dp, bottom = 34.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.align(Alignment.CenterHorizontally).width(44.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(Ink.TrackOff))
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    PostageStamp(s.id, Modifier.width(120.dp), tilt = -3f)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(s.label ?: "a snap", style = hand(28.sp))
                        Text("snapped ${shortDate(s.createdAt)}", style = body(13.sp, color = Ink.Muted))
                        Text(
                            if (s.offers.isEmpty()) "no matches saved yet" else "last searched ${shortDate(s.checkedAt)} · ${s.offers.size} matches",
                            style = body(13.sp, color = Ink.Muted),
                        )
                    }
                }
                PillButton("Search again", onClick = { onDismiss(); app.searchAgain(s) })
                PillButton(if (confirmDelete) "Tap again to delete" else "Delete", dark = false, height = 50.dp, onClick = {
                    if (confirmDelete) { app.deleteSnaps(setOf(s.id)); onDismiss() } else confirmDelete = true
                })
            }
        }
    }
}
