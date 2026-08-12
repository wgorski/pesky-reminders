package com.wgorski.peskyreminders.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wgorski.peskyreminders.Settings

private val R12 = RoundedCornerShape(12.dp)

/**
 * Settings. Two things to tune, one per block: whether an ignored reminder keeps
 * buzzing and how often, and how long swiping one away hides it for.
 */
@Composable
fun SettingsSheet(
    nagEnabled: Boolean,
    nagMinutes: Int,
    swipeSnoozeMinutes: Int,
    onNagEnabled: (Boolean) -> Unit,
    onNagMinutes: (Int) -> Unit,
    onSwipeSnoozeMinutes: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    // Both drafts are held here, not in the rows, so dismissing the sheet can
    // still commit a half-typed value ("999" -> clamped to 180) on the way out —
    // and so the two fields cannot end up sharing one.
    var minutesText by remember(nagMinutes) { mutableStateOf(nagMinutes.toString()) }
    val commitMinutes = {
        val safe = Settings.coerceMinutes(minutesText.toIntOrNull() ?: nagMinutes)
        minutesText = safe.toString()
        onNagMinutes(safe)
    }

    var swipeText by remember(swipeSnoozeMinutes) {
        mutableStateOf(swipeSnoozeMinutes.toString())
    }
    val commitSwipe = {
        val safe = Settings.coerceSwipeSnoozeMinutes(
            swipeText.toIntOrNull() ?: swipeSnoozeMinutes
        )
        swipeText = safe.toString()
        onSwipeSnoozeMinutes(safe)
    }

    PeskySheet(
        title = "Settings",
        // Both, or closing the sheet would clamp one field and drop the other.
        onDismiss = { commitMinutes(); commitSwipe(); onDismiss() },
        bodySpacing = 20.dp,
    ) {
        Text("NAGGING", style = PeskyType.SectionLabel, color = PeskyColors.TextMuted)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    "Keep buzzing",
                    fontFamily = DmSans,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = PeskyColors.Text,
                )
                Text(
                    "Buzz again while a reminder is still sitting there, until you " +
                        "snooze it or tick it off.",
                    style = PeskyType.Body,
                    lineHeight = 18.sp,
                )
            }
            PeskySwitch(checked = nagEnabled, onCheckedChange = onNagEnabled)
        }

        MinutesRow(
            lead = "Every",
            tag = "nag-minutes",
            min = Settings.MIN_NAG_MINUTES,
            max = Settings.MAX_NAG_MINUTES,
            enabled = nagEnabled,
            minutes = nagMinutes,
            text = minutesText,
            onText = { typed -> onMinutesTyped(typed, Settings.MIN_NAG_MINUTES,
                Settings.MAX_NAG_MINUTES, { minutesText = it }, onNagMinutes) },
            onCommit = commitMinutes,
        )

        Text("SWIPING", style = PeskyType.SectionLabel, color = PeskyColors.TextMuted)

        Text(
            "Swiping a reminder away pushes it back by this long instead of " +
                "bringing it straight back.",
            style = PeskyType.Body,
            lineHeight = 18.sp,
        )

        // No switch beside it: the behaviour is unconditional, so there is
        // nothing to turn off — and therefore nothing that could grey this out.
        MinutesRow(
            lead = "Snooze for",
            tag = "swipe-minutes",
            min = Settings.MIN_SWIPE_SNOOZE_MINUTES,
            max = Settings.MAX_SWIPE_SNOOZE_MINUTES,
            minutes = swipeSnoozeMinutes,
            text = swipeText,
            onText = { typed -> onMinutesTyped(typed, Settings.MIN_SWIPE_SNOOZE_MINUTES,
                Settings.MAX_SWIPE_SNOOZE_MINUTES, { swipeText = it }, onSwipeSnoozeMinutes) },
            onCommit = commitSwipe,
        )
    }
}

/**
 * What both fields do on a keystroke.
 *
 * Digits only, short enough that it cannot overflow an Int, and **committed as
 * soon as what is typed is usable** — hiding the keyboard does not clear Compose
 * focus, so waiting for focus loss silently drops the value. Anything out of
 * range waits for the clamp on Done or dismiss.
 */
private fun onMinutesTyped(
    typed: String,
    min: Int,
    max: Int,
    onDraft: (String) -> Unit,
    onCommit: (Int) -> Unit,
) {
    val digits = typed.filter(Char::isDigit).take(3)
    onDraft(digits)
    digits.toIntOrNull()?.takeIf { it in min..max }?.let(onCommit)
}

/**
 * A clamping numeric field with a word in front of it and its range spelled out
 * underneath.
 *
 * Shared by both blocks rather than copied. It carries the *never lose a typed
 * value on focus alone* fix — see [onMinutesTyped] — and a second copy would
 * drift from it at the first change to either.
 */
@Composable
private fun MinutesRow(
    lead: String,
    tag: String,
    min: Int,
    max: Int,
    minutes: Int,
    text: String,
    onText: (String) -> Unit,
    onCommit: () -> Unit,
    enabled: Boolean = true,
) {
    val keyboard = LocalSoftwareKeyboardController.current

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                lead,
                fontFamily = DmSans,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = if (enabled) PeskyColors.Text else PeskyColors.TextDisabled,
            )
            BasicTextField(
                value = text,
                onValueChange = onText,
                enabled = enabled,
                singleLine = true,
                textStyle = PeskyType.Input.copy(
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold,
                    color = if (enabled) PeskyColors.Text else PeskyColors.TextDisabled,
                ),
                cursorBrush = SolidColor(PeskyColors.Accent),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { onCommit(); keyboard?.hide() }),
                modifier = Modifier
                    .width(72.dp)
                    .testTag(tag)
                    .onFocusChanged { if (!it.isFocused) onCommit() }
                    .clip(R12)
                    .background(PeskyColors.Field)
                    .border(
                        1.dp,
                        if (enabled) PeskyColors.FieldBorder else PeskyColors.CardBorder,
                        R12,
                    )
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            )
            Text(
                if (minutes == 1) "minute" else "minutes",
                fontFamily = DmSans,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = if (enabled) PeskyColors.Text else PeskyColors.TextDisabled,
                // Tagged because both blocks say the same word: an
                // onNodeWithText("minutes") matches two nodes now.
                modifier = Modifier.testTag("$tag-unit"),
            )
        }
        Text(
            "Anything from $min to $max minutes.",
            style = PeskyType.Body,
            fontSize = 12.sp,
            color = if (enabled) PeskyColors.TextMuted else PeskyColors.TextDisabled,
            modifier = Modifier.testTag("$tag-hint"),
        )
    }
}

/** A switch in the app's own language — no Material chrome. */
@Composable
private fun PeskySwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val track by animateColorAsState(
        targetValue = if (checked) PeskyColors.Accent else PeskyColors.FieldBorder,
        animationSpec = tween(140),
        label = "track",
    )
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 22.dp else 2.dp,
        animationSpec = tween(140),
        label = "thumb",
    )
    Box(
        modifier = Modifier
            .testTag("nag-switch")
            .toggleable(
                value = checked,
                role = Role.Switch,
                interactionSource = remember {
                    androidx.compose.foundation.interaction.MutableInteractionSource()
                },
                indication = null,
                onValueChange = onCheckedChange,
            )
            .width(48.dp)
            .height(28.dp)
            .clip(CircleShape)
            .background(track),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .offset(x = thumbOffset)
                .size(24.dp)
                .clip(CircleShape)
                .background(if (checked) PeskyColors.Text else PeskyColors.TextDim)
        )
    }
}
