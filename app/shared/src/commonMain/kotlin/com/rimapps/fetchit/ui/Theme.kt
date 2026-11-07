package com.rimapps.fetchit.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import fetchit.shared.generated.resources.Res
import fetchit.shared.generated.resources.figtree_bold
import fetchit.shared.generated.resources.figtree_medium
import fetchit.shared.generated.resources.figtree_regular
import fetchit.shared.generated.resources.figtree_semibold
import fetchit.shared.generated.resources.gochi_hand
import fetchit.shared.generated.resources.instrument_serif_italic
import org.jetbrains.compose.resources.Font

/** "Monochrome scrapbook" palette (CLAUDE.md → Design system). */
object Ink {
    val Background = Color(0xFFF7F7F6)
    val Ink = Color(0xFF111111)
    val Pill = Color(0xFFEDEDEB)
    val Muted = Color(0xFF6E6E6B)
    val Note = Color(0xFFF1F1EF)
    val NoteBorder = Color(0xFFE4E4E1)
    val Accent = Color(0xFFE8402A)
    val Warning = Color(0xFFB4351E)
    /** Price-watch dot: the price has dropped since watching started (red = not yet). */
    val Drop = Color(0xFF2E9B5A)
    val Grey = Color(0xFF8A8A87)
    val LightGrey = Color(0xFFA3A3A0)
    val NavGrey = Color(0xFF7A7A77)
    val TrackOff = Color(0xFFE0E0DD)
    val Divider = Color(0xFFEBEBE8)
    val Dot = Color(0xFFE2E2DF)
    val White = Color.White
}

object Fonts {
    val hand: FontFamily @Composable get() = FontFamily(Font(Res.font.gochi_hand))
    val serif: FontFamily @Composable get() = FontFamily(Font(Res.font.instrument_serif_italic, style = FontStyle.Italic))
    val body: FontFamily
        @Composable get() = FontFamily(
            Font(Res.font.figtree_regular, FontWeight.Normal),
            Font(Res.font.figtree_medium, FontWeight.Medium),
            Font(Res.font.figtree_semibold, FontWeight.SemiBold),
            Font(Res.font.figtree_bold, FontWeight.Bold),
        )
}

/** Handwritten title / caption (Gochi Hand). */
@Composable
fun hand(size: TextUnit, color: Color = Ink.Ink) = TextStyle(fontFamily = Fonts.hand, fontSize = size, color = color, lineHeight = size * 1.1f)

/** Numbers, dates and accent words (Instrument Serif italic). */
@Composable
fun serif(size: TextUnit, color: Color = Ink.Ink) =
    TextStyle(fontFamily = Fonts.serif, fontStyle = FontStyle.Italic, fontSize = size, color = color)

/** UI / body (Figtree). */
@Composable
fun body(size: TextUnit = 15.sp, weight: FontWeight = FontWeight.Normal, color: Color = Ink.Ink) =
    TextStyle(fontFamily = Fonts.body, fontSize = size, fontWeight = weight, color = color)

@Composable
fun FetchTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Ink.Ink, onPrimary = Ink.White, background = Ink.Background, surface = Ink.Background,
            onBackground = Ink.Ink, onSurface = Ink.Ink, secondaryContainer = Ink.Pill,
        ),
        typography = MaterialTheme.typography.let { t ->
            val f = Fonts.body
            t.copy(
                bodyLarge = t.bodyLarge.copy(fontFamily = f), bodyMedium = t.bodyMedium.copy(fontFamily = f),
                labelLarge = t.labelLarge.copy(fontFamily = f), titleMedium = t.titleMedium.copy(fontFamily = f),
            )
        },
        content = content,
    )
}

fun money(amount: Double?, currency: String = "USD"): String {
    if (amount == null) return "—"
    val symbol = if (currency == "USD") "$" else "$currency "
    val cents = kotlin.math.round(amount * 100).toLong()
    return if (cents % 100 == 0L) "$symbol${cents / 100}" else "$symbol${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"
}
