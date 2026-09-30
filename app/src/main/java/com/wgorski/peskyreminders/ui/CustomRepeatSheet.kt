package com.wgorski.peskyreminders.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wgorski.peskyreminders.Recurrence
import com.wgorski.peskyreminders.Repeat
import com.wgorski.peskyreminders.RepeatUnit
import com.wgorski.peskyreminders.TaskTime

private val R12 = RoundedCornerShape(12.dp)
private val R10 = RoundedCornerShape(10.dp)
private val R9 = RoundedCornerShape(9.dp)

/** The stepper's range. Nobody means "every 100 weeks"; everyone means "every 2". */
internal const val MAX_EVERY = 99

/**
 * The "Custom repeat" editor — every N days / weeks / months / years, and on
 * which days — raised over the task sheet by its Custom chip.
 *
 * It is a draft of a draft: [onDone] hands the rule back to the task sheet,
 * which still waits for Save; dismissing drops it. It is hosted inside the task
 * sheet's own composition rather than swapped in for it, which is what keeps the
 * name and time typed underneath alive while this is up.
 *
 * Every field keeps its own state, so flipping week → month → week comes back
 * to the days that were ticked, and the rule is only assembled from the fields
 * the chosen unit reads.
 */
@Composable
internal fun CustomRepeatSheet(
    seed: Repeat,
    dueMillis: Long,
    onDismiss: () -> Unit,
    onDone: (Repeat) -> Unit,
) {
    val dueWeekday = TaskTime.dayOfWeekOf(dueMillis)
    // A task that does not repeat yet opens on "every week, on its own weekday" —
    // the commonest reason to be here at all.
    val start = if (seed.repeats) Recurrence.bindTo(seed, dueMillis) else Repeat(RepeatUnit.WEEK)

    var unit by rememberSaveable { mutableStateOf(start.unit ?: RepeatUnit.WEEK) }
    var every by rememberSaveable { mutableIntStateOf(start.every) }
    var dayMask by rememberSaveable {
        mutableIntStateOf(maskOf(start.weekdays.ifEmpty { setOf(dueWeekday) }))
    }
    var byWeekday by rememberSaveable { mutableStateOf(start.ordinal != null) }
    var monthDay by rememberSaveable { mutableIntStateOf(start.monthDay ?: TaskTime.dayOf(dueMillis)) }
    var ordinal by rememberSaveable { mutableIntStateOf(start.ordinal ?: ordinalOf(dueMillis)) }
    var weekday by rememberSaveable { mutableIntStateOf(start.weekday ?: dueWeekday) }

    val weekdays = (1..7).filter { dayMask and (1 shl it) != 0 }.toSet()
    val rule = when (unit) {
        RepeatUnit.WEEK -> Repeat(unit, every, weekdays = weekdays)
        RepeatUnit.MONTH ->
            if (byWeekday) Repeat(unit, every, ordinal = ordinal, weekday = weekday)
            else Repeat(unit, every, monthDay = monthDay)
        else -> Repeat(unit, every)
    }

    PeskySheet(
        title = "Custom repeat",
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
                        imageVector = PeskyIcons.Repeat,
                        contentDescription = null,
                        tint = PeskyColors.Text.copy(alpha = 0.7f),
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        Recurrence.describe(rule, dueMillis),
                        modifier = Modifier.testTag("custom-readout"),
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
                        .testTag("custom-done")
                        .pressable(scale = 0.99f) { onDone(rule) }
                        .clip(CircleShape)
                        .background(PeskyColors.Accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Done", style = PeskyType.Action, color = PeskyColors.Text)
                }
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Repeat every", style = PeskyType.FieldLabel)
            EveryStepper(every) { every = it }
            Segmented(
                options = RepeatUnit.entries.map { it to unitLabel(it, every) },
                selected = unit,
                accent = true,
                tag = { "custom-unit-${it.name}" },
            ) { unit = it }
        }

        when (unit) {
            RepeatUnit.WEEK -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Repeat on", style = PeskyType.FieldLabel)
                WeekdayToggles(selected = weekdays) { day ->
                    // The last ticked day stays ticked: a weekly rule with no days
                    // is not a rule, and a disabled Done would only ask why.
                    if (day !in weekdays) dayMask = dayMask or (1 shl day)
                    else if (weekdays.size > 1) dayMask = dayMask and (1 shl day).inv()
                }
            }

            RepeatUnit.MONTH -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Repeat on", style = PeskyType.FieldLabel)
                Segmented(
                    options = listOf(false to "A date", true to "A weekday"),
                    selected = byWeekday,
                    accent = false,
                    tag = { if (it) "custom-month-by-weekday" else "custom-month-by-date" },
                ) { byWeekday = it }
                if (byWeekday) {
                    OrdinalChips(ordinal) { ordinal = it }
                    WeekdayToggles(selected = setOf(weekday)) { weekday = it }
                } else {
                    MonthDayGrid(monthDay) { monthDay = it }
                }
            }

            else -> Unit
        }
    }
}

/** "day" / "days" — the unit switch reads as the end of "Repeat every N …". */
internal fun unitLabel(unit: RepeatUnit, every: Int): String {
    val word = unit.name.lowercase()
    return if (every == 1) word else word + "s"
}

private fun maskOf(days: Set<Int>): Int = days.fold(0) { mask, day -> mask or (1 shl day) }

/** Which of its weekday [millis] is in its month: the 1st–4th, or the last past that. */
private fun ordinalOf(millis: Long): Int =
    ((TaskTime.dayOf(millis) - 1) / 7 + 1).let { if (it > 4) Repeat.LAST else it }

@Composable
private fun EveryStepper(every: Int, onChange: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .clip(R12)
            .background(PeskyColors.Field)
            .border(1.dp, PeskyColors.FieldBorder, R12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Clamped here as well as disabled: the limit is the stepper's to keep, not
        // something to trust the button state for.
        StepperEnd("−", "custom-every-minus", enabled = every > 1) { onChange((every - 1).coerceAtLeast(1)) }
        Text(
            every.toString(),
            modifier = Modifier.width(40.dp).testTag("custom-every"),
            fontFamily = DmSans,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = PeskyColors.Text,
            textAlign = TextAlign.Center,
        )
        StepperEnd("+", "custom-every-plus", enabled = every < MAX_EVERY) {
            onChange((every + 1).coerceAtMost(MAX_EVERY))
        }
    }
}

@Composable
private fun StepperEnd(symbol: String, tag: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .testTag(tag)
            .pressable(scale = 0.9f, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            symbol,
            fontFamily = DmSans,
            fontSize = 20.sp,
            color = if (enabled) PeskyColors.TextDim else PeskyColors.TextDisabled,
        )
    }
}

/**
 * The task sheet's tab strip, generalised. [accent] marks the choice in the
 * accent, for the unit — the one that decides what the rest of the sheet shows.
 */
@Composable
private fun <T> Segmented(
    options: List<Pair<T, String>>,
    selected: T,
    accent: Boolean,
    tag: (T) -> String,
    onPick: (T) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(R12).background(PeskyColors.Field).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { (value, label) ->
            val on = value == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .testTag(tag(value))
                    .semantics { this.selected = on }
                    .clip(R9)
                    .background(
                        when {
                            !on -> Color.Transparent
                            accent -> PeskyColors.AccentWash
                            else -> PeskyColors.FieldBorder
                        }
                    )
                    .border(1.dp, if (on && accent) PeskyColors.Accent else Color.Transparent, R9)
                    .tap { onPick(value) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    fontFamily = DmSans,
                    fontSize = 12.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                    color = if (on) PeskyColors.Text else PeskyColors.TextDim,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Seven round toggles, in the locale's week order — the same order the grid uses. */
@Composable
private fun WeekdayToggles(selected: Set<Int>, onToggle: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        TaskTime.weekdayOrder().forEach { day ->
            val on = day in selected
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .testTag("custom-weekday-$day")
                    .semantics { this.selected = on }
                    .pressable(scale = 0.92f) { onToggle(day) }
                    .clip(CircleShape)
                    .background(if (on) PeskyColors.AccentWash else PeskyColors.Field)
                    .border(1.dp, if (on) PeskyColors.Accent else PeskyColors.FieldBorder, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    TaskTime.weekdayShort(day).take(1),
                    fontFamily = DmSans,
                    fontSize = 13.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold,
                    color = if (on) PeskyColors.Text else PeskyColors.TextDim,
                )
            }
        }
    }
}

/** 1st · 2nd · 3rd · 4th · Last — which of the chosen weekday in the month. */
@Composable
private fun OrdinalChips(ordinal: Int, onPick: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        (listOf(1, 2, 3, 4, Repeat.LAST)).forEach { k ->
            val on = k == ordinal
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(34.dp)
                    .testTag(if (k == Repeat.LAST) "custom-ordinal-last" else "custom-ordinal-$k")
                    .semantics { this.selected = on }
                    .pressable(scale = 0.95f) { onPick(k) }
                    .clip(CircleShape)
                    .background(if (on) PeskyColors.AccentWash else PeskyColors.Field)
                    .border(1.dp, if (on) PeskyColors.Accent else PeskyColors.FieldBorder, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (k == Repeat.LAST) "Last" else Recurrence.ordinalSuffix(k),
                    fontFamily = DmSans,
                    fontSize = 12.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold,
                    color = if (on) PeskyColors.Text else PeskyColors.TextDim,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Days 1–31 and "Last day". Not a calendar — no month, no blanks, no weekday
 * columns — because the rule names a day *number*, whatever month it lands in.
 */
@Composable
private fun MonthDayGrid(monthDay: Int, onPick: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        (1..31).chunked(7).forEach { week ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                week.forEach { day ->
                    GridCell(
                        label = day.toString(),
                        tag = "custom-monthday-$day",
                        selected = day == monthDay,
                        modifier = Modifier.weight(1f),
                    ) { onPick(day) }
                }
                // The short last row: 29–31, then "Last day" across the rest.
                if (week.size < 7) {
                    GridCell(
                        label = "Last day",
                        tag = "custom-monthday-last",
                        selected = monthDay == Repeat.LAST,
                        filled = true,
                        modifier = Modifier.weight((7 - week.size).toFloat()),
                    ) { onPick(Repeat.LAST) }
                }
            }
        }
    }
}

@Composable
private fun GridCell(
    label: String,
    tag: String,
    selected: Boolean,
    modifier: Modifier,
    // A number reads as a choice on its own; a phrase among them needs a field
    // behind it, or "Last day" looks like a caption rather than a cell.
    filled: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(32.dp)
            .testTag(tag)
            .semantics { this.selected = selected }
            .clip(R10)
            .background(
                when {
                    selected -> PeskyColors.AccentWash
                    filled -> PeskyColors.Field
                    else -> Color.Transparent
                }
            )
            .border(
                1.dp,
                when {
                    selected -> PeskyColors.Accent
                    filled -> PeskyColors.FieldBorder
                    else -> Color.Transparent
                },
                R10,
            )
            .tap(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontFamily = DmSans,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = PeskyColors.Text,
        )
    }
}
