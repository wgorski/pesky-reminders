package com.wgorski.peskyreminders.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.wgorski.peskyreminders.TaskTime

/**
 * The two ways the task sheet lets you name a moment: the scroll wheels and the
 * month grid.
 *
 * Split out from the sheet itself because both the add and the edit path draw
 * exactly the same controls — the sheet only decides what they start on.
 */

private val R12 = RoundedCornerShape(12.dp)
private val R10 = RoundedCornerShape(10.dp)
private val R9 = RoundedCornerShape(9.dp)
private val R8 = RoundedCornerShape(8.dp)

/** Which of the two "…or dial it in" tabs is showing. */
internal enum class EntryMode { WHEELS, CALENDAR }

/** The minute wheel's only rungs. Nothing here needs the precision of a minute. */
internal val MINUTE_STEPS = listOf(0, 15, 30, 45)

@Composable
internal fun ModeTabs(mode: EntryMode, onMode: (EntryMode) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(R12).background(PeskyColors.Field).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        listOf(
            EntryMode.WHEELS to "Quick pick",
            EntryMode.CALENDAR to "Calendar",
        ).forEach { (value, label) ->
            val selected = mode == value
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(R9)
                    .background(if (selected) PeskyColors.FieldBorder else Color.Transparent)
                    .tap { onMode(value) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    fontFamily = DmSans,
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = if (selected) PeskyColors.Text else PeskyColors.TextDim,
                )
            }
        }
    }
}

// ---- wheels -----------------------------------------------------------------

/**
 * Day / hour / minute columns, working from — and pointing at — [dueMillis].
 *
 * The DAY column only spans a fortnight from today, so a task due further out —
 * or one whose moment has already gone — has no rung to sit on and shows nothing
 * selected. The MIN column is quarter-hours only, so a snoozed task sitting at
 * :07 shows nothing selected there either. The sheet's footer always states the
 * real due time, so nothing is misreported; the wheel simply cannot point at it.
 */
@Composable
internal fun Wheels(
    nowMillis: Long,
    use24h: Boolean,
    dueMillis: Long,
    onCommit: (Long) -> Unit,
) {
    val selectedDay = TaskTime.dayDiff(dueMillis, nowMillis)
    val selectedHour = TaskTime.hourOf(dueMillis)
    val selectedMinute = MINUTE_STEPS.indexOf(TaskTime.minuteOf(dueMillis))

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PeskyWheel(
            title = "DAY",
            modifier = Modifier.weight(1.5f),
            count = 14,
            selectedIndex = if (selectedDay in 0..13) selectedDay else -1,
            label = { i ->
                TaskTime.formatDay(
                    TaskTime.plusDays(TaskTime.startOfDay(nowMillis), i),
                    nowMillis,
                )
            },
            onPick = { i -> onCommit(TaskTime.withDayOffset(dueMillis, nowMillis, i)) },
        )
        PeskyWheel(
            title = "HOUR",
            modifier = Modifier.weight(1f),
            count = 24,
            selectedIndex = selectedHour,
            label = { h ->
                if (use24h) h.toString().padStart(2, '0')
                else "${(h % 12).let { if (it == 0) 12 else it }} ${if (h < 12) "AM" else "PM"}"
            },
            onPick = { h -> onCommit(TaskTime.withHour(dueMillis, h)) },
        )
        PeskyWheel(
            title = "MIN",
            modifier = Modifier.weight(0.8f),
            count = MINUTE_STEPS.size,
            selectedIndex = selectedMinute,
            label = { i -> ":" + MINUTE_STEPS[i].toString().padStart(2, '0') },
            onPick = { i -> onCommit(TaskTime.withMinute(dueMillis, MINUTE_STEPS[i])) },
        )
    }
}

// ---- calendar ---------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CalendarPicker(
    nowMillis: Long,
    use24h: Boolean,
    dueMillis: Long,
    monthOffset: Int,
    onMonthShift: (Int) -> Unit,
    onCommit: (Long) -> Unit,
) {
    val monthStart = TaskTime.monthStart(nowMillis, monthOffset)
    val year = TaskTime.yearOf(monthStart)
    val month = TaskTime.monthOf(monthStart)
    val todayStart = TaskTime.startOfDay(nowMillis)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MonthArrow("Previous month", flip = true) { onMonthShift(-1) }
            Text(
                TaskTime.monthTitle(monthStart),
                fontFamily = DmSans,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = PeskyColors.Text,
            )
            MonthArrow("Next month", flip = false) { onMonthShift(1) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            listOf("S", "M", "T", "W", "T", "F", "S").forEachIndexed { index, dow ->
                Text(
                    dow,
                    modifier = Modifier.weight(1f).padding(vertical = 4.dp),
                    fontFamily = DmSans,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.05.em,
                    color = PeskyColors.TextMuted,
                    textAlign = TextAlign.Center,
                )
            }
        }

        val cells: List<Int?> = List(TaskTime.leadingBlanks(monthStart)) { null } +
            (1..TaskTime.daysInMonth(monthStart)).toList()
        cells.chunked(7).forEach { week ->
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                week.forEach { day ->
                    if (day == null) {
                        Spacer(Modifier.weight(1f).height(32.dp))
                    } else {
                        val cellStart =
                            TaskTime.startOfDay(TaskTime.withDate(monthStart, year, month, day))
                        val selected = TaskTime.yearOf(dueMillis) == year &&
                            TaskTime.monthOf(dueMillis) == month &&
                            TaskTime.dayOf(dueMillis) == day
                        DayCell(
                            day = day,
                            modifier = Modifier.weight(1f),
                            past = cellStart < todayStart,
                            isToday = cellStart == todayStart,
                            selected = selected,
                            onPick = { onCommit(TaskTime.withDate(dueMillis, year, month, day)) },
                        )
                    }
                }
                repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepButton("−1 hr") { onCommit(TaskTime.shiftHours(dueMillis, -1)) }
            Text(
                TaskTime.formatTime(dueMillis, use24h),
                modifier = Modifier.weight(1f),
                fontFamily = DmSans,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = PeskyColors.Text,
                textAlign = TextAlign.Center,
            )
            StepButton("+1 hr") { onCommit(TaskTime.shiftHours(dueMillis, 1)) }
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf("Morning 9:00" to 9, "Noon" to 12, "Evening 7:00" to 19, "Night 9:00" to 21)
                .forEach { (label, hour) ->
                    RoundChip(label) { onCommit(TaskTime.withTimeOfDay(dueMillis, hour)) }
                }
            RoundChip("+15 min") { onCommit(TaskTime.shiftMinutes(dueMillis, 15)) }
        }
    }
}

@Composable
private fun MonthArrow(description: String, flip: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .pressable(scale = 0.94f, onClick = onClick)
            .clip(R10)
            .background(PeskyColors.Field)
            .border(1.dp, PeskyColors.FieldBorder, R10),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = PeskyIcons.ChevronRight,
            contentDescription = description,
            tint = PeskyColors.Text.copy(alpha = 0.7f),
            modifier = Modifier.size(14.dp).rotate(if (flip) 180f else 0f),
        )
    }
}

@Composable
private fun DayCell(
    day: Int,
    modifier: Modifier,
    past: Boolean,
    isToday: Boolean,
    selected: Boolean,
    onPick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(32.dp)
            .testTag("day-$day")
            .clip(R8)
            .background(if (selected) PeskyColors.AccentWashStrong else Color.Transparent)
            .then(if (past) Modifier else Modifier.tap(onPick)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            day.toString(),
            fontFamily = DmSans,
            fontSize = 12.sp,
            fontWeight = if (selected || isToday) FontWeight.Bold else FontWeight.Normal,
            color = when {
                past -> PeskyColors.TextDisabled
                isToday && !selected -> PeskyColors.Accent
                selected -> PeskyColors.AccentBright
                else -> PeskyColors.Text
            },
        )
    }
}

@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .pressable(scale = 0.96f, onClick = onClick)
            .clip(R10)
            .background(PeskyColors.Field)
            .border(1.dp, PeskyColors.FieldBorder, R10)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            label,
            fontFamily = DmSans,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = PeskyColors.Text,
        )
    }
}

@Composable
private fun RoundChip(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .pressable(scale = 0.96f, onClick = onClick)
            .clip(CircleShape)
            .background(PeskyColors.Field)
            .border(1.dp, PeskyColors.FieldBorder, CircleShape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            fontFamily = DmSans,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = PeskyColors.TextChip,
        )
    }
}
