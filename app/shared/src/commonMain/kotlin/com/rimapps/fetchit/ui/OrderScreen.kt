package com.rimapps.fetchit.ui

import com.rimapps.fetchit.ui.motion.inkStroke
import com.rimapps.fetchit.ui.motion.doodleCross
import com.rimapps.fetchit.ui.motion.PathDraw
import com.rimapps.fetchit.ui.motion.LocalReduceMotion
import com.rimapps.fetchit.ui.icons.DoodleIcon
import com.rimapps.fetchit.ui.icons.Doodle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Animatable
import com.rimapps.fetchit.ui.components.PillButton
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rimapps.fetchit.AppState
import com.rimapps.fetchit.Screen
import com.rimapps.fetchit.data.Order
import com.rimapps.fetchit.friendly
import com.rimapps.fetchit.ui.motion.RunningDog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One purchase's order: its timeline (every Activity entry for it, oldest first) and, until it ships,
 * Cancel order → cancelOrder refunds the PayPal capture and rolls back the month's spend.
 */
@Composable
fun OrderScreen(app: AppState, screen: Screen.Order) {
    val scope = rememberCoroutineScope()
    var order by remember { mutableStateOf<Order?>(null) }
    var confirming by remember { mutableStateOf(false) }
    var cancelling by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Orders move on their own (confirmed → shipped → delivered): keep it fresh while open.
    LaunchedEffect(screen.purchaseId) {
        while (true) {
            runCatching { app.api.order(screen.purchaseId) }.getOrNull()?.let { order = it }
            app.refreshActivity()
            delay(5_000)
        }
    }
    // "Bought" and "Ordered" are written in one transaction (same timestamp): the purchase entry goes first.
    val timeline = app.activity.filter { it.purchaseId == screen.purchaseId }
        .sortedWith(compareBy({ it.createdAt }, { if (it.type == "purchase") 0 else 1 }))
    val first = timeline.firstOrNull()
    val o = order

    Column(Modifier.fillMaxSize().dotted().padding(top = 56.dp, bottom = 34.dp)) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BackButton({ app.back() })
            Text("Your order", style = hand(30.sp))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Polaroid(o?.items?.firstOrNull()?.imageUrl ?: first?.imageUrl, null, Modifier.width(120.dp), tilt = -3f, imageHeight = 110.dp)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(o?.items?.firstOrNull()?.title ?: first?.title ?: "", style = body(15.sp, FontWeight.SemiBold), maxLines = 3)
                    Text(money((o?.totalCents ?: 0) / 100.0, o?.currency ?: "USD"), style = body(34.sp, FontWeight.Medium))
                    Text(listOfNotNull(o?.retailer, o?.retailerOrderId).joinToString(" · "), style = serif(15.sp, Ink.Muted))
                    Text("demo store · sandbox", style = body(11.sp, color = Ink.Grey))
                }
            }
            o?.refund?.let { r ->
                PaperNote(Modifier.fillMaxWidth(), tilt = 0.6f) {
                    Text("Refunded ${money(r.amountCents / 100.0, o.currency)}", style = hand(26.sp))
                    Text("Back on your PayPal · refund ${r.id} (${r.status.lowercase()})", style = body(13.sp, color = Ink.Muted))
                }
            }
            if (o != null) OrderRoute(o, o.items.firstOrNull()?.imageUrl ?: first?.imageUrl)
            Text("timeline", style = hand(22.sp, Ink.Muted))
            PaperNote(Modifier.fillMaxWidth(), tilt = 0f) {
                if (timeline.isEmpty()) RunningDog(60.dp)
                timeline.forEach { e ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(if (e.status == "cancelled" || e.status == "refunded") Ink.Accent else Ink.Ink))
                        Column(Modifier.weight(1f)) {
                            Text(statusLabel(e.status), style = body(14.sp, FontWeight.SemiBold))
                            Text(e.summary, style = body(13.sp, color = Ink.Muted))
                        }
                        Text(timeAgo(e.createdAt), style = serif(14.sp, Ink.Muted))
                    }
                }
            }
            ErrorNote(error)
        }
        if (o?.cancellable == true) {
            PillButton(
                if (confirming) "Tap again: cancel and refund ${money(o.totalCents / 100.0, o.currency)}" else "Cancel order",
                dark = confirming, loading = cancelling, height = if (confirming) 58.dp else 52.dp,
                modifier = Modifier.padding(horizontal = 20.dp),
                onClick = {
                    if (!confirming) { confirming = true; return@PillButton }
                    cancelling = true; error = null
                    scope.launch {
                        runCatching { app.api.cancelOrder(screen.purchaseId) }
                            .onSuccess { order = app.api.order(screen.purchaseId) ?: order; app.refresh(); app.refreshActivity() }
                            .onFailure { error = it.friendly() }
                        cancelling = false; confirming = false
                    }
                },
            )
        } else if (o != null && o.refund == null) {
            Text(
                "It's ${statusLabel(o.status).lowercase()}, so it can't be cancelled any more.",
                style = body(13.sp, color = Ink.Grey), modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
    }
}

/** How far along the route each status is (store 0 → Home 1). */
private fun routeFraction(status: String) = when (status) {
    "confirmed" -> 0.22f
    "shipped" -> 0.62f
    "delivered" -> 1f
    else -> 0f
}

/**
 * A dotted route from the store to Home, with a small polaroid of the item travelling along it as the order
 * advances (placed → confirmed → shipped → delivered). It rolls from the store to where the order is when the
 * screen opens, then moves on as the status changes. Cancelled: it stays put with a red ✗.
 */
@Composable
private fun OrderRoute(o: Order, imageUrl: String?) {
    val reduce = LocalReduceMotion.current
    val cancelled = o.status == "cancelled" || o.status == "cancelling"
    val target = routeFraction(o.status)
    val frac = remember { Animatable(0f) }
    LaunchedEffect(target) { if (reduce) frac.snapTo(target) else frac.animateTo(target, tween(600, easing = FastOutSlowInEasing)) }
    PaperNote(Modifier.fillMaxWidth(), tilt = -0.4f) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DoodleIcon(Doodle.Cart, null, size = 22.dp, color = Ink.Muted)
            Text(" ${o.retailer.removePrefix("www.")}", style = body(13.sp, FontWeight.SemiBold, Ink.Muted), maxLines = 1, modifier = Modifier.weight(1f))
            Text("${o.shipTo?.label ?: "Home"} ", style = body(13.sp, FontWeight.SemiBold, Ink.Muted))
            DoodleIcon(Doodle.Home, null, size = 22.dp, color = if (o.status == "delivered") Ink.Ink else Ink.Muted)
        }
        BoxWithConstraints(Modifier.fillMaxWidth().height(96.dp)) {
            val density = LocalDensity.current
            val route = remember(maxWidth) {
                with(density) {
                    val w = maxWidth.toPx(); val h = 96.dp.toPx()
                    Path().apply {
                        moveTo(12.dp.toPx(), h * 0.62f)
                        cubicTo(w * 0.32f, h * 0.05f, w * 0.55f, h * 1.05f, w - 12.dp.toPx(), h * 0.42f)
                    }
                }
            }
            val measure = remember(route) { PathMeasure().apply { setPath(route, false) } }
            Canvas(Modifier.matchParentSize()) {
                drawPath(route, Ink.LightGrey, style = inkStroke(2.2.dp.toPx(), dashed = true, dash = 4.dp.toPx()))
                // Checkpoints: store, confirmed, shipped, home.
                listOf(0f, 0.22f, 0.62f, 1f).forEach { f ->
                    val pos = measure.getPosition(measure.length * f)
                    val reached = f <= target && !cancelled
                    drawCircle(if (reached) Ink.Ink else Ink.TrackOff, 4.dp.toPx(), pos)
                }
            }
            // The item, travelling.
            val pos = measure.getPosition(measure.length * frac.value)
            with(density) {
                Box(
                    Modifier.offset { IntOffset((pos.x - 21.dp.toPx()).toInt(), (pos.y - 50.dp.toPx()).toInt()) }
                        .graphicsLayer { rotationZ = -5f }.shadow(3.dp).background(Ink.White).padding(start = 3.dp, end = 3.dp, top = 3.dp, bottom = 8.dp),
                ) {
                    ProductImage(imageUrl, Modifier.size(36.dp))
                    if (cancelled) PathDraw(Modifier.matchParentSize(), color = Ink.Accent, strokeWidth = 3.dp) { sz -> doodleCross(Rect(Offset.Zero, sz)) }
                }
            }
        }
        Text(
            when (o.status) {
                "placed" -> "Waiting at the store"
                "confirmed" -> "The store has it"
                "shipped" -> "On its way" + (o.trackingNumber?.let { " · $it" } ?: "")
                "delivered" -> "Delivered to ${o.shipTo?.label ?: "Home"}"
                else -> statusLabel(o.status)
            },
            style = hand(20.sp),
        )
    }
}
