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

    /**
     * Crimson, taken from the "uscita / exit" sign.
     *
     * The ink itself white-balances to about #914155, but that only reaches
     * 2.7:1 against [Screen] — the FAB would sink into the background and the
     * button labels would be unreadable. This keeps the sign's hue (350) and
     * depth while clearing the bars: 3.6:1 as a shape on the background, 4.5:1
     * for a cream label sitting on it.
     *
     * Because it is a deep red, text on top of it is [Text], not [Screen] —
     * near-black on crimson only manages 3.6:1 and reads muddy.
     */
    val Accent = Color(0xFFD12744)

    /** Lifted crimson, for accent text that sits on an accent wash. */
    val AccentBright = Color(0xFFE8455F)

    val AccentWash = Color(0x29D12744) // 16%
    val AccentWashStrong = Color(0x33D12744) // 20%
    val AccentGlow = Color(0x2ED12744) // 18%

    /**
     * Overdue is the accent, not a second red. Derived rather than duplicated so
     * the two can never drift apart.
     *
     * The "OVERDUE" label and the "Was due …" line are small text, where the
     * deep crimson reaches 3.6:1 on [Screen] rather than the 4.5:1 that small
     * text wants — the coral it replaced managed 6.6:1. That is the cost of one
     * red instead of two; see [OverdueText] if it turns out to be too dim.
     */
    val Overdue = Accent
    val OverdueBorder = Accent.copy(alpha = 0.28f)

    /** Same hue, lifted to 4.8:1 — swap the overdue text to this if 3.6 reads too dark. */
    val OverdueText = AccentBright
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
