package com.peskyreminders.poc.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.peskyreminders.poc.R

/**
 * Colour tokens lifted verbatim from the "Pesky Reminders v2" design.
 * Warm near-black surfaces, a single orange accent, coral for overdue,
 * mint for completed.
 */
object PeskyColors {
    val Screen = Color(0xFF161513)
    val Card = Color(0xFF211F1C)
    val CardBorder = Color(0xFF2D2B27)
    val DoneCard = Color(0xFF1B1A18)
    val DoneCardBorder = Color(0xFF262421)
    val Sheet = Color(0xFF1E1C1A)
    val Field = Color(0xFF282623)
    val FieldBorder = Color(0xFF3A3631)

    val Text = Color(0xFFF3F0EB)
    val TextChip = Color(0xFFD8D3CC)
    val TextDim = Color(0xFF9E9890)
    val TextMuted = Color(0xFF6E6962)
    val TextDisabled = Color(0xFF4B4742)

    val Accent = Color(0xFFFF7A4F)
    val AccentBright = Color(0xFFFF8F6A)
    val AccentWash = Color(0x29FF7A4F) // rgba(255,122,79,0.16)
    val AccentWashStrong = Color(0x33FF7A4F) // rgba(255,122,79,0.20)
    val AccentGlow = Color(0x2EFF7A4F) // rgba(255,122,79,0.18)

    val Overdue = Color(0xFFFF6B6B)
    val OverdueBorder = Color(0x47FF6B6B) // rgba(255,107,107,0.28)
    val Check = Color(0xFF5EE6B4)
    val CheckRing = Color(0xFF5A5650)

    val Grabber = Color(0xFF3A3631)
    val Scrim = Color(0x990A0908) // rgba(10,9,8,0.6)
}

/** Body typeface for everything except the logo, sheet title and save button. */
val DmSans = FontFamily(
    Font(R.font.dm_sans_regular, FontWeight.Normal),
    Font(R.font.dm_sans_medium, FontWeight.Medium),
    Font(R.font.dm_sans_semibold, FontWeight.SemiBold),
    Font(R.font.dm_sans_bold, FontWeight.Bold),
)

/** Display typeface — used sparingly, for the moments the design wants to shout. */
val Bricolage = FontFamily(
    Font(R.font.bricolage_grotesque_bold, FontWeight.Bold),
    Font(R.font.bricolage_grotesque_extrabold, FontWeight.ExtraBold),
)
