package com.wgorski.peskyreminders.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wgorski.peskyreminders.SnoozeOptions
import com.wgorski.peskyreminders.TaskTime

private val R12 = RoundedCornerShape(12.dp)

/**
 * Everything you can do about a reminder that has gone off, on one screen:
 * finish it, push it by a duration, or push it to a time of day.
 *
 * Raised by the notification, and nowhere else — both by a tap on its body and
 * by its Snooze action. The task list picks absolute times instead, which is why
 * this no longer takes its title and labels as parameters.
 *
 * **Every control commits the moment it is touched.** There is no confirm
 * button, and therefore no selection to hold and nothing to highlight. It is
 * also why there is no "back at …" footer: with no held choice there is nothing
 * to preview. Each wheel row states the time it lands on instead, so you can see
 * where a tap goes before you take it.
 *
 * The duration always counts from now, matching
 * [com.wgorski.peskyreminders.Reminders.snooze]. There is deliberately no way to
 * pass a different starting point, because a preview that can disagree with what
 * the tap does is worse than no preview.
 */
@Composable
fun ReminderSheet(
    taskName: String,
    nowMillis: Long,
    use24h: Boolean,
    onDismiss: () -> Unit,
    onDone: () -> Unit,
    onSnooze: (minutes: Int) -> Unit,
    onSnoozeUntil: (atMillis: Long) -> Unit,
) {
    PeskySheet(
        title = taskName,
        onDismiss = onDismiss,
        // A little more room at the foot than the default, since there is no
        // footer left to sit between the wheel and the navigation bar.
        bodyPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 10.dp, bottom = 22.dp),
    ) {
        DoneButton(onDone)

        // Both rows are one choice offered two ways — how long from now, or what
        // time to land on — so they share a heading. "Snooze for" could not cover
        // the second row anyway: *snooze for 20:00* is wrong, which is what makes
        // the label the neutral "Snooze" rather than a matched pair.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Snooze", style = PeskyType.FieldLabel)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SnoozeOptions.PRESETS.forEach { preset ->
                    PresetChip(minutes = preset, modifier = Modifier.weight(1f)) {
                        onSnooze(preset)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SnoozeOptions.untilPresets(nowMillis).forEachIndexed { index, target ->
                    UntilChip(
                        target = target,
                        nowMillis = nowMillis,
                        use24h = use24h,
                        index = index,
                        modifier = Modifier.weight(1f),
                    ) { onSnoozeUntil(target) }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("…or dial it in", style = PeskyType.FieldLabel)
            PeskyWheel(
                title = "SNOOZE",
                showTitle = false,
                count = SnoozeOptions.WHEEL.size,
                // Nothing is ever held, so there is nothing to mark as chosen.
                // PeskyWheel's scroll-into-view effect returns early on a
                // negative index, so this also leaves the list where it opened.
                selectedIndex = -1,
                label = { SnoozeOptions.label(SnoozeOptions.WHEEL[it]) },
                // With the footer readout gone this is the only place a landing
                // time appears, so every row carries one — not just the long
                // durations that are hard to picture.
                aside = { index ->
                    "(" + TaskTime.formatCompact(
                        nowMillis + SnoozeOptions.WHEEL[index] * 60_000L, nowMillis, use24h,
                    ) + ")"
                },
                onPick = { onSnooze(SnoozeOptions.WHEEL[it]) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * The one filled control in the sheet, and the only thing giving it a
 * hierarchy — everything below is a flat chip or a wheel row.
 *
 * [TaskSheet] puts its immediate action at the foot behind a hairline, because
 * everything above it there is a draft waiting for Save and the hairline marks
 * that change of register. Nothing here waits for anything, so there are no two
 * registers to separate and this goes on top as the primary outcome.
 */
@Composable
private fun DoneButton(onDone: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .testTag("done-button")
            .pressable(scale = 0.99f, onClick = onDone)
            .clip(CircleShape)
            .background(PeskyColors.Accent),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = PeskyIcons.Check,
            contentDescription = null,
            // Cream, not the screen colour: near-black on this crimson is muddy.
            tint = PeskyColors.Text,
            modifier = Modifier.size(18.dp),
        )
        Text("Done", style = PeskyType.Action, color = PeskyColors.Text)
    }
}

@Composable
private fun PresetChip(
    minutes: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .testTag("preset-$minutes")
            .pressable(scale = 0.96f, onClick = onClick)
            .clip(R12)
            .background(PeskyColors.Field)
            .border(1.dp, PeskyColors.FieldBorder, R12)
            .padding(vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            SnoozeOptions.chipLabel(minutes),
            fontFamily = DmSans,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = PeskyColors.Text,
        )
        Text(
            SnoozeOptions.chipUnit(minutes),
            fontFamily = DmSans,
            fontSize = 10.sp,
            color = PeskyColors.TextDim,
        )
    }
}

/**
 * A chip that lands on a time of day rather than after a duration.
 *
 * Same geometry as [PresetChip] and the same hierarchy — the big line is the
 * value, the small line qualifies it. The part-of-day names the ladder is built
 * from are deliberately absent: once the chip reads "13:00" the word "afternoon"
 * adds nothing, and four chips only get ~81dp each, which "Afternoon" at 15sp
 * very nearly fills on its own.
 *
 * Both labels come from [TaskTime], so a chip cannot disagree with the wheel rows
 * below it about how a time is written — including whether it is 24-hour.
 */
@Composable
private fun UntilChip(
    target: Long,
    nowMillis: Long,
    use24h: Boolean,
    index: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .testTag("until-$index")
            .pressable(scale = 0.96f, onClick = onClick)
            .clip(R12)
            .background(PeskyColors.Field)
            .border(1.dp, PeskyColors.FieldBorder, R12)
            .padding(vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            TaskTime.formatTime(target, use24h),
            fontFamily = DmSans,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = PeskyColors.Text,
            maxLines = 1,
        )
        Text(
            TaskTime.formatDay(target, nowMillis),
            fontFamily = DmSans,
            fontSize = 10.sp,
            color = PeskyColors.TextDim,
            maxLines = 1,
        )
    }
}
