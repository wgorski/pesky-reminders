package com.peskyreminders.poc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.peskyreminders.poc.SnoozeOptions
import com.peskyreminders.poc.TaskTime

private val R12 = RoundedCornerShape(12.dp)

/**
 * Presets for the common cases, a quarter-hour wheel for the rest.
 *
 * Raised by the notification's own Snooze action, and nowhere else — the task
 * list used to re-word it as "Reschedule", which is why it once took its title
 * and labels as parameters; tapping a task now picks an absolute time instead.
 *
 * The duration always counts from now, matching
 * [com.peskyreminders.poc.Reminders.snooze]. There is deliberately no way to
 * pass a different starting point, because a preview that can disagree with what
 * the button does is worse than no preview.
 */
@Composable
fun SnoozeSheet(
    taskName: String,
    nowMillis: Long,
    use24h: Boolean,
    onDismiss: () -> Unit,
    onSnooze: (minutes: Int) -> Unit,
) {
    var minutes by rememberSaveable { mutableIntStateOf(SnoozeOptions.DEFAULT_MINUTES) }
    val backAt = nowMillis + minutes * 60_000L

    PeskySheet(
        title = "Snooze until",
        onDismiss = onDismiss,
        footer = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(PeskyColors.Sheet)
                    .padding(start = 22.dp, end = 22.dp, top = 14.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = PeskyIcons.Clock,
                        contentDescription = null,
                        tint = PeskyColors.Text.copy(alpha = 0.7f),
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        "Back at ${TaskTime.formatFull(backAt, nowMillis, use24h)}",
                        modifier = Modifier.testTag("back-at"),
                        fontFamily = DmSans,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = PeskyColors.Accent,
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("snooze-button")
                        .pressable(scale = 0.99f) { onSnooze(minutes) }
                        .clip(CircleShape)
                        .background(PeskyColors.Accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Snooze", style = PeskyType.Action, color = PeskyColors.Text)
                }
            }
        },
    ) {
        Text(
            taskName,
            modifier = Modifier.testTag("snooze-task"),
            style = PeskyType.TaskName,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Common", style = PeskyType.FieldLabel)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SnoozeOptions.PRESETS.forEach { preset ->
                    PresetChip(
                        minutes = preset,
                        selected = minutes == preset,
                        modifier = Modifier.weight(1f),
                    ) { minutes = preset }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("…or dial it in", style = PeskyType.FieldLabel)
            PeskyWheel(
                title = "SNOOZE",
                showTitle = false,
                count = SnoozeOptions.WHEEL.size,
                selectedIndex = SnoozeOptions.WHEEL.indexOf(minutes),
                label = { SnoozeOptions.label(SnoozeOptions.WHEEL[it]) },
                // "8h" says nothing about bedtime; "8h (04:00)" does. Only worth
                // the room once the duration is long enough to be hard to picture.
                aside = { index ->
                    val option = SnoozeOptions.WHEEL[index]
                    if (!SnoozeOptions.landsAtAClockTime(option)) null
                    else "(" + TaskTime.formatCompact(
                        nowMillis + option * 60_000L, nowMillis, use24h,
                    ) + ")"
                },
                onPick = { minutes = SnoozeOptions.WHEEL[it] },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PresetChip(
    minutes: Int,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .testTag("preset-$minutes")
            .pressable(scale = 0.96f, onClick = onClick)
            .clip(R12)
            .background(if (selected) PeskyColors.AccentWash else PeskyColors.Field)
            .border(
                1.dp,
                if (selected) PeskyColors.Accent else PeskyColors.FieldBorder,
                R12,
            )
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
