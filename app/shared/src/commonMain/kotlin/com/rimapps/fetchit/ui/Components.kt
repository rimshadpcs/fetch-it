package com.rimapps.fetchit.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage

/** Dotted paper background (radial dots every 18 dp). */
fun Modifier.dotted(): Modifier = this.background(Ink.Background).drawBehind {
    val step = 18.dp.toPx()
    var y = step / 2
    while (y < size.height) {
        var x = step / 2
        while (x < size.width) {
            drawCircle(Ink.Dot, 1.2.dp.toPx(), Offset(x, y))
            x += step
        }
        y += step
    }
}

/** Black pill, 58 dp. */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    dark: Boolean = true,
    height: Dp = 58.dp,
    leading: (@Composable () -> Unit)? = null,
) {
    val bg = if (dark) Ink.Ink else Ink.Pill
    val fg = if (dark) Ink.White else Ink.Muted
    Row(
        modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(999.dp)).background(bg)
            .alpha(if (enabled) 1f else 0.45f)
            .clickable(enabled = enabled && !loading, role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(22.dp), color = fg, strokeWidth = 2.5.dp)
        else {
            leading?.invoke()
            Text(text, style = body(if (dark) 17.sp else 16.sp, if (dark) FontWeight.SemiBold else FontWeight.Medium, fg))
        }
    }
}

/** Gray pill chip; black when selected. */
@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.height(40.dp).clip(RoundedCornerShape(999.dp)).background(if (selected) Ink.Ink else Ink.Pill)
            .clickable(role = Role.Checkbox, onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = body(14.sp, FontWeight.SemiBold, if (selected) Ink.White else Ink.NavGrey)) }
}

/** Segmented control: gray track, white selected segment. */
@Composable
fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.clip(RoundedCornerShape(999.dp)).background(Ink.Pill).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { o ->
            val on = o == selected
            Box(
                Modifier.weight(1f).height(40.dp).then(if (on) Modifier.shadow(2.dp, RoundedCornerShape(999.dp)) else Modifier)
                    .clip(RoundedCornerShape(999.dp)).background(if (on) Ink.White else Color.Transparent)
                    .clickable(role = Role.Tab) { onSelect(o) },
                contentAlignment = Alignment.Center,
            ) { Text(label(o), style = body(15.sp, if (on) FontWeight.SemiBold else FontWeight.Medium, if (on) Ink.Ink else Ink.NavGrey)) }
        }
    }
}

/** Paper-note card, slightly rotated. */
@Composable
fun PaperNote(modifier: Modifier = Modifier, tilt: Float = -0.8f, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.rotate(tilt).background(Ink.Note).border(1.dp, Ink.NoteBorder).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** Polaroid with a black pin; caption in handwriting. */
@Composable
fun Polaroid(imageUrl: String?, caption: String?, modifier: Modifier = Modifier, tilt: Float = -4f, imageHeight: Dp = 190.dp) {
    Box(modifier.rotate(tilt)) {
        Column(Modifier.background(Ink.Pill).padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = if (caption != null) 14.dp else 12.dp)) {
            ProductImage(imageUrl, Modifier.fillMaxWidth().height(imageHeight))
            if (caption != null) Text(caption, style = hand(if (caption.length > 9) 20.sp else 28.sp), maxLines = 1, softWrap = false, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
        }
        Box(Modifier.align(Alignment.TopCenter).padding(top = 0.dp).size(24.dp).graphicsLayer { translationY = -10.dp.toPx() }
            .clip(CircleShape).background(Color(0xFF222222)))
    }
}

@Composable
fun ProductImage(url: String?, modifier: Modifier = Modifier) {
    Box(modifier.background(Color(0xFFD9D3C9))) {
        if (url != null) AsyncImage(url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

/** 44 dp gray circle with a chevron. */
@Composable
fun BackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.size(44.dp).clip(CircleShape).background(Ink.Pill).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(16.dp)) {
            val s = Stroke(2.dp.toPx(), cap = StrokeCap.Round)
            drawLine(Ink.Ink, Offset(size.width * 0.65f, size.height * 0.1f), Offset(size.width * 0.25f, size.height * 0.5f), s.width, StrokeCap.Round)
            drawLine(Ink.Ink, Offset(size.width * 0.25f, size.height * 0.5f), Offset(size.width * 0.65f, size.height * 0.9f), s.width, StrokeCap.Round)
        }
    }
}

/** Onboarding header: back, "Step N of 4" chip, 4-segment progress. */
@Composable
fun StepHeader(step: Int, total: Int = 4, onBack: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) BackButton(onBack) else Box(Modifier.size(44.dp))
            Box(Modifier.weight(1f))
            Box(Modifier.clip(RoundedCornerShape(999.dp)).background(Ink.Pill).padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text("Step $step of $total", style = body(13.sp, FontWeight.SemiBold, Ink.Muted))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(total) { i ->
                Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(if (i < step) Ink.Ink else Ink.TrackOff))
            }
        }
    }
}

/** Budget bar that fills on first show. */
@Composable
fun BudgetBar(fraction: Float, modifier: Modifier = Modifier, color: Color = Ink.Ink) {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(fraction) { anim.animateTo(fraction.coerceIn(0f, 1f), tween(900)) }
    Box(modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Ink.TrackOff)) {
        Box(Modifier.fillMaxWidth(anim.value).height(6.dp).clip(RoundedCornerShape(3.dp)).background(color))
    }
}

/** Card that rises in with a small delay (stagger by index). */
@Composable
fun RiseIn(index: Int = 0, content: @Composable () -> Unit) {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(60L * index); a.animateTo(1f, tween(380)) }
    Box(Modifier.graphicsLayer { alpha = a.value; translationY = (1 - a.value) * 24.dp.toPx() }) { content() }
}

@Composable
fun ErrorNote(message: String?, modifier: Modifier = Modifier) {
    if (message == null) return
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(Ink.Accent))
        Text(message, style = body(14.sp, FontWeight.Medium, Ink.Warning))
    }
}

@Composable
fun RowScope.Spacer() = Box(Modifier.weight(1f))

@Composable
fun ColumnScope.Spacer() = Box(Modifier.weight(1f))

@Composable
fun Gap(h: Dp = 0.dp, w: Dp = 0.dp) = Box(Modifier.height(h).width(w))
