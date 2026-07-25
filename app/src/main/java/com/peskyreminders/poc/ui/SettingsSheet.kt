package com.peskyreminders.poc.ui

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
import com.peskyreminders.poc.Settings

private val R12 = RoundedCornerShape(12.dp)

/**
 * Settings. Currently one thing to tune: whether an ignored reminder keeps
 * buzzing, and how often.
 */
@Composable
fun SettingsSheet(
    nagEnabled: Boolean,
    nagMinutes: Int,
    onNagEnabled: (Boolean) -> Unit,
    onNagMinutes: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    // Held here, not in the row, so dismissing the sheet can still commit a
    // half-typed value ("999" -> clamped to 180) on the way out.
    var minutesText by remember(nagMinutes) { mutableStateOf(nagMinutes.toString()) }
    val commitMinutes = {
        val safe = Settings.coerceMinutes(minutesText.toIntOrNull() ?: nagMinutes)
        minutesText = safe.toString()
        onNagMinutes(safe)
    }

    PeskySheet(
        title = "Settings",
        onDismiss = { commitMinutes(); onDismiss() },
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
            enabled = nagEnabled,
            minutes = nagMinutes,
            text = minutesText,
            onText = { typed ->
                // Digits only, short enough that it cannot overflow an Int.
                minutesText = typed.filter(Char::isDigit).take(3)
                // Commit as soon as what is typed is usable, so the value is
                // never lost just because focus was never given up. Anything
                // out of range waits for the clamp on Done or dismiss.
                minutesText.toIntOrNull()
                    ?.takeIf { it in Settings.MIN_NAG_MINUTES..Settings.MAX_NAG_MINUTES }
                    ?.let(onNagMinutes)
            },
            onCommit = commitMinutes,
        )
    }
}

@Composable
private fun MinutesRow(
    enabled: Boolean,
    minutes: Int,
    text: String,
    onText: (String) -> Unit,
    onCommit: () -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Every",
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
                    .testTag("nag-minutes")
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
            )
        }
        Text(
            "Anything from ${Settings.MIN_NAG_MINUTES} to ${Settings.MAX_NAG_MINUTES} minutes.",
            style = PeskyType.Body,
            fontSize = 12.sp,
            color = if (enabled) PeskyColors.TextMuted else PeskyColors.TextDisabled,
            modifier = Modifier.testTag("nag-minutes-hint"),
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
