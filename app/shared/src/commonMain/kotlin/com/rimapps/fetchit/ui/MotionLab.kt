package com.rimapps.fetchit.ui

import com.rimapps.fetchit.ui.components.Chip
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rimapps.fetchit.AppState
import androidx.compose.runtime.mutableStateOf
import com.rimapps.fetchit.ui.components.AmountField
import com.rimapps.fetchit.ui.components.PillButton
import com.rimapps.fetchit.ui.components.Segmented
import com.rimapps.fetchit.ui.components.SizePicker
import com.rimapps.fetchit.ui.components.Toggle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.rimapps.fetchit.ui.motion.CountUpMoney
import com.rimapps.fetchit.ui.motion.Dog
import com.rimapps.fetchit.ui.motion.DogMood
import com.rimapps.fetchit.ui.motion.HandwritingReveal
import com.rimapps.fetchit.ui.motion.PathDraw
import com.rimapps.fetchit.ui.motion.RunningDog
import com.rimapps.fetchit.ui.motion.ScoutRunsIn
import com.rimapps.fetchit.ui.motion.Stamp
import com.rimapps.fetchit.ui.motion.doodleArrow
import com.rimapps.fetchit.ui.motion.doodleCross
import com.rimapps.fetchit.ui.motion.outlineBox
import com.rimapps.fetchit.ui.motion.pinIn
import com.rimapps.fetchit.ui.motion.wobblyLoop

/** Every motion piece on one page, each replayable. Hidden (long-press the dog on Account). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MotionLabScreen(app: AppState) {
    var replay by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().dotted().padding(top = 56.dp)) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BackButton({ app.back() })
            Text("Motion lab", style = hand(30.sp), modifier = Modifier.weight(1f))
            Chip("Replay", false, onClick = { replay++ })
        }
        key(replay) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Label("DogMood")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    DogMood.entries.forEach { m ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Dog(84.dp, m, bone = false)
                            Text(m.name.lowercase(), style = hand(17.sp, Ink.Muted))
                        }
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        RunningDog(84.dp)
                        Text("running", style = hand(17.sp, Ink.Muted))
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Dog(84.dp, DogMood.Excited, bone = true)
                        Text("with a bone", style = hand(17.sp, Ink.Muted))
                    }
                }
                Label("ScoutFace / ScoutPeek (eyes glance + blink; right one looks up)")
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Bottom) {
                    com.rimapps.fetchit.ui.motion.ScoutFace(90.dp)
                    com.rimapps.fetchit.ui.motion.ScoutPeek(90.dp, showing = 0.55f, lookAt = androidx.compose.ui.geometry.Offset(0f, -1f))
                }
                Label("ScoutRunsIn")
                Box(Modifier.fillMaxWidth().height(110.dp)) { ScoutRunsIn(80.dp, Modifier.align(Alignment.CenterEnd), bone = true) }

                Label("PathDraw")
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.size(90.dp, 60.dp)) {
                        PathDraw(Modifier.matchParentSize(), durationMs = 600) { s -> listOf(wobblyLoop(Rect(Offset(4f, 4f), androidx.compose.ui.geometry.Size(s.width - 8f, s.height - 8f)))) }
                    }
                    Box(Modifier.size(70.dp, 60.dp)) {
                        PathDraw(Modifier.matchParentSize()) { s -> doodleArrow(Offset(s.width * 0.9f, s.height * 0.85f), Offset(s.width * 0.1f, s.height * 0.2f)) }
                    }
                    Box(Modifier.size(60.dp)) {
                        PathDraw(Modifier.matchParentSize(), color = Ink.Accent, strokeWidth = 4.dp) { s -> doodleCross(Rect(Offset.Zero, s)) }
                    }
                    Box(Modifier.size(80.dp, 60.dp)) {
                        PathDraw(Modifier.matchParentSize(), color = Ink.Accent, dashed = true) { s -> listOf(outlineBox(Rect(Offset(3f, 3f), androidx.compose.ui.geometry.Size(s.width - 6f, s.height - 6f)), 16f)) }
                    }
                }

                Label("HandwritingReveal")
                HandwritingReveal("mushroom lamp, cream", hand(28.sp))

                Label("PinIn")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    repeat(4) { i -> Box(Modifier.pinIn(i).width(70.dp).height(80.dp).background(Ink.Pill)) }
                }

                Label("Stamp")
                Row(horizontalArrangement = Arrangement.spacedBy(30.dp), verticalAlignment = Alignment.CenterVertically) {
                    Stamp("FETCHED", delayMs = 200)
                    Stamp("OK'D", color = Ink.Ink, rotation = 8f, delayMs = 900, haptic = false)
                }

                Label("Buttons: press, loading, done, arming")
                var stage by remember { mutableIntStateOf(0) }
                val scope = androidx.compose.runtime.rememberCoroutineScope()
                PillButton(if (stage == 3) "Armed again" else "Save", loading = stage == 1, done = stage == 2, enabled = stage != 4, onClick = {
                    scope.launch { stage = 1; delay(1200); stage = 2; delay(700); stage = 4; delay(900); stage = 3 }
                })
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    PillButton("Gray pill", dark = false, height = 50.dp, modifier = Modifier.width(150.dp), onClick = {})
                    BackButton({})
                }

                Label("Selections")
                var seg by remember { mutableStateOf("Best") }
                Segmented(listOf("Best", "Cheapest", "Nearest"), seg, { it }, { seg = it })
                var picked by remember { mutableStateOf(setOf("Home")) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Home", "Clothing", "Shoes", "Books").forEach { c ->
                        Chip(c, c in picked, check = true, onClick = { picked = if (c in picked) picked - c else picked + c })
                    }
                }
                var on by remember { mutableStateOf(false) }
                Toggle("Ask before a new store", on) { on = it }
                var scale by remember { mutableStateOf("US") }
                var shoe by remember { mutableStateOf<String?>("US 9") }
                Segmented(listOf("US", "UK", "EU"), scale, { it }, { scale = it }, Modifier.width(180.dp))
                val shoes = when (scale) { "UK" -> (5..11).map { "UK $it" }; "EU" -> (39..45).map { "EU $it" }; else -> (6..12).map { "US $it" } }
                SizePicker(shoes, shoe, { shoe = it }, display = { it.substringAfter(' ') }, flipKey = scale)
                var budget by remember { mutableStateOf(200.0) }
                var limit by remember { mutableStateOf(75.0) }
                AmountField(budget, { budget = it }, " a month")
                AmountField(limit, { limit = it }, " per item", warning = if (limit > budget) "That's more than your monthly budget" else null)
                Segmented(listOf(25.0, 75.0, 300.0), limit, { money(it) }, { limit = it })

                Label("CountUp")
                CountUpMoney(167.10, body(34.sp, FontWeight.Medium))
                Gap(40.dp)
            }
        }
    }
}

@Composable
private fun Label(text: String) = Text(text, style = body(13.sp, FontWeight.SemiBold, Ink.Grey))
