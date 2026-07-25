package com.peskyreminders.poc.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.peskyreminders.poc.QuickPick
import com.peskyreminders.poc.Repeat
import com.peskyreminders.poc.TaskTime

private val SheetShape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
private val R12 = RoundedCornerShape(12.dp)
private val R10 = RoundedCornerShape(10.dp)
private val R9 = RoundedCornerShape(9.dp)
private val R8 = RoundedCornerShape(8.dp)

private enum class EntryMode { WHEELS, CALENDAR }

private val MINUTE_STEPS = listOf(0, 15, 30, 45)

/**
 * The "New pester" bottom sheet: a name, a time picked any of three ways
 * (shortcut chips, scroll wheels, or a calendar), and a repeat rule.
 */
@Composable
fun AddTaskSheet(
    nowMillis: Long,
    use24h: Boolean,
    onDismiss: () -> Unit,
    onSave: (name: String, dueMillis: Long, repeat: Repeat) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var dueMillis by rememberSaveable { mutableStateOf<Long?>(null) }
    var chipKey by rememberSaveable { mutableStateOf<String?>(null) }
    var mode by rememberSaveable { mutableStateOf(EntryMode.WHEELS) }
    var repeat by rememberSaveable { mutableStateOf(Repeat.ONCE) }
    var calOffset by rememberSaveable { mutableIntStateOf(0) }

    // Where the steppers start from before anything has been chosen.
    val base = dueMillis ?: TaskTime.defaultDue(nowMillis)
    val commit: (Long) -> Unit = { dueMillis = it; chipKey = null }
    val canSave = name.isNotBlank() && dueMillis != null

    // Runs the entrance the moment the sheet is composed.
    val appear = remember { MutableTransitionState(false).apply { targetState = true } }
    val slideFrom = with(LocalDensity.current) { 48.dp.roundToPx() }
    BackHandler(onBack = onDismiss)

    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visibleState = appear, enter = fadeIn(tween(200)), exit = fadeOut()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .testTag("sheet-scrim")
                    .background(PeskyColors.Scrim)
                    .tap(onDismiss)
            )
        }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val sheetMax = maxHeight * 0.9f
            AnimatedVisibility(
                visibleState = appear,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(tween(220)) { slideFrom } +
                    fadeIn(tween(220), initialAlpha = 0.4f),
                exit = fadeOut(),
            ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = sheetMax)
                    .clip(SheetShape)
                    .background(PeskyColors.Sheet)
            ) {
                // Sits *behind* the sheet's content and eats taps that land on
                // empty space, so they never reach the scrim and close the sheet.
                // A sibling rather than a modifier on the Column above: a
                // clickable ancestor would merge the whole sheet into one
                // semantics node — a single giant "button" to a screen reader.
                Box(Modifier.matchParentSize().tap {})

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
            ) {
                Grabber()
                SheetHeader(onDismiss)

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(start = 22.dp, end = 22.dp, top = 10.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    NameField(name) { name = it }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("When?", style = PeskyType.FieldLabel)
                        QuickPickGrid(
                            nowMillis = nowMillis,
                            use24h = use24h,
                            selectedKey = chipKey,
                            onPick = { pick -> dueMillis = pick.whenMillis; chipKey = pick.key },
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("…or dial it in", style = PeskyType.FieldLabel)
                        ModeTabs(mode) { mode = it }

                        when (mode) {
                            EntryMode.CALENDAR -> CalendarPicker(
                                nowMillis = nowMillis,
                                use24h = use24h,
                                base = base,
                                dueMillis = dueMillis,
                                monthOffset = calOffset,
                                onMonthShift = { calOffset += it },
                                onCommit = commit,
                            )

                            EntryMode.WHEELS -> Wheels(
                                nowMillis = nowMillis,
                                use24h = use24h,
                                base = base,
                                dueMillis = dueMillis,
                                onCommit = commit,
                            )
                        }
                    }
                }

                SheetFooter(
                    nowMillis = nowMillis,
                    use24h = use24h,
                    dueMillis = dueMillis,
                    repeat = repeat,
                    onRepeat = { repeat = it },
                    canSave = canSave,
                    onSave = { dueMillis?.let { onSave(name.trim(), it, repeat) } },
                )
            }
            }
            }
        }
    }
}

@Composable
private fun Grabber() {
    Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.width(38.dp).height(4.dp).clip(CircleShape).background(PeskyColors.Grabber))
    }
}

@Composable
private fun SheetHeader(onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 22.dp, end = 22.dp, top = 8.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("New pester", style = PeskyType.SheetTitle)
        Box(
            modifier = Modifier
                .size(32.dp)
                .pressable(scale = 0.9f, onClick = onDismiss)
                .clip(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = PeskyIcons.Close,
                contentDescription = "Close",
                tint = PeskyColors.TextDim,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun NameField(value: String, onValue: (String) -> Unit) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("What should I nag you about?", style = PeskyType.FieldLabel)
        BasicTextField(
            value = value,
            onValueChange = onValue,
            singleLine = true,
            textStyle = PeskyType.Input,
            cursorBrush = SolidColor(PeskyColors.Accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("name-field")
                .focusRequester(focusRequester)
                .clip(R12)
                .background(PeskyColors.Field)
                .border(1.dp, PeskyColors.FieldBorder, R12)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            decorationBox = { field ->
                Box {
                    if (value.isEmpty()) {
                        Text(
                            "e.g. Feed the sourdough",
                            style = PeskyType.Input,
                            color = PeskyColors.TextMuted,
                        )
                    }
                    field()
                }
            },
        )
    }
}

@Composable
private fun QuickPickGrid(
    nowMillis: Long,
    use24h: Boolean,
    selectedKey: String?,
    onPick: (QuickPick) -> Unit,
) {
    val picks = remember(nowMillis) { TaskTime.quickPicks(nowMillis) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        picks.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { pick ->
                    val selected = selectedKey == pick.key
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .pressable(scale = 0.98f) { onPick(pick) }
                            .clip(R12)
                            .background(if (selected) PeskyColors.AccentWash else PeskyColors.Field)
                            .border(
                                1.dp,
                                if (selected) PeskyColors.Accent else PeskyColors.FieldBorder,
                                R12,
                            )
                            .padding(horizontal = 13.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(1.dp),
                    ) {
                        Text(
                            pick.label,
                            fontFamily = DmSans,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = PeskyColors.Text,
                        )
                        Text(
                            TaskTime.formatFull(pick.whenMillis, nowMillis, use24h),
                            fontFamily = DmSans,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            color = PeskyColors.TextDim,
                        )
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ModeTabs(mode: EntryMode, onMode: (EntryMode) -> Unit) {
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

@Composable
private fun Wheels(
    nowMillis: Long,
    use24h: Boolean,
    base: Long,
    dueMillis: Long?,
    onCommit: (Long) -> Unit,
) {
    val selectedDay = dueMillis?.let { TaskTime.dayDiff(it, nowMillis) } ?: -1
    val selectedHour = dueMillis?.let { TaskTime.hourOf(it) } ?: -1
    val selectedMinute = dueMillis?.let { MINUTE_STEPS.indexOf(TaskTime.minuteOf(it)) } ?: -1

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Wheel(
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
            onPick = { i -> onCommit(TaskTime.withDayOffset(base, nowMillis, i)) },
        )
        Wheel(
            title = "HOUR",
            modifier = Modifier.weight(1f),
            count = 24,
            selectedIndex = selectedHour,
            label = { h ->
                if (use24h) h.toString().padStart(2, '0')
                else "${(h % 12).let { if (it == 0) 12 else it }} ${if (h < 12) "AM" else "PM"}"
            },
            onPick = { h -> onCommit(TaskTime.withHour(base, h)) },
        )
        Wheel(
            title = "MIN",
            modifier = Modifier.weight(0.8f),
            count = MINUTE_STEPS.size,
            selectedIndex = selectedMinute,
            label = { i -> ":" + MINUTE_STEPS[i].toString().padStart(2, '0') },
            onPick = { i -> onCommit(TaskTime.withMinute(base, MINUTE_STEPS[i])) },
        )
    }
}

@Composable
private fun Wheel(
    title: String,
    modifier: Modifier,
    count: Int,
    selectedIndex: Int,
    label: (Int) -> String,
    onPick: (Int) -> Unit,
) {
    val state = rememberLazyListState()
    // Bring the selection into view when it moves off-screen (e.g. a chip was tapped).
    LaunchedEffect(selectedIndex) {
        if (selectedIndex < 0) return@LaunchedEffect
        if (state.layoutInfo.visibleItemsInfo.none { it.index == selectedIndex }) {
            state.animateScrollToItem(maxOf(0, selectedIndex - 1))
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            title,
            style = PeskyType.ColumnLabel,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        LazyColumn(
            state = state,
            modifier = Modifier
                .height(168.dp)
                .testTag("wheel-$title")
                .clip(R12)
                .background(PeskyColors.DoneCard)
                .border(1.dp, PeskyColors.CardBorder, R12)
                .padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(count) { index ->
                val selected = index == selectedIndex
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("$title-$index")
                        .clip(R8)
                        .background(
                            if (selected) PeskyColors.AccentWashStrong else Color.Transparent
                        )
                        .tap { onPick(index) }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label(index),
                        fontFamily = DmSans,
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = PeskyColors.Text,
                    )
                }
            }
        }
    }
}

// ---- calendar ---------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CalendarPicker(
    nowMillis: Long,
    use24h: Boolean,
    base: Long,
    dueMillis: Long?,
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
                        val selected = dueMillis != null &&
                            TaskTime.yearOf(dueMillis) == year &&
                            TaskTime.monthOf(dueMillis) == month &&
                            TaskTime.dayOf(dueMillis) == day
                        DayCell(
                            day = day,
                            modifier = Modifier.weight(1f),
                            past = cellStart < todayStart,
                            isToday = cellStart == todayStart,
                            selected = selected,
                            onPick = { onCommit(TaskTime.withDate(base, year, month, day)) },
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
            StepButton("−1 hr") { onCommit(TaskTime.shiftHours(base, -1)) }
            Text(
                TaskTime.formatTime(base, use24h),
                modifier = Modifier.weight(1f),
                fontFamily = DmSans,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = PeskyColors.Text,
                textAlign = TextAlign.Center,
            )
            StepButton("+1 hr") { onCommit(TaskTime.shiftHours(base, 1)) }
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf("Morning 9:00" to 9, "Noon" to 12, "Evening 7:00" to 19, "Night 9:00" to 21)
                .forEach { (label, hour) ->
                    RoundChip(label) { onCommit(TaskTime.withTimeOfDay(base, hour)) }
                }
            RoundChip("+15 min") { onCommit(TaskTime.shiftMinutes(base, 15)) }
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

// ---- footer -----------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SheetFooter(
    nowMillis: Long,
    use24h: Boolean,
    dueMillis: Long?,
    repeat: Repeat,
    onRepeat: (Repeat) -> Unit,
    canSave: Boolean,
    onSave: () -> Unit,
) {
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
            if (dueMillis != null) {
                Text(
                    TaskTime.formatFull(dueMillis, nowMillis, use24h),
                    modifier = Modifier.testTag("due-label"),
                    fontFamily = DmSans,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = PeskyColors.Accent,
                )
            } else {
                Text(
                    "Pick a time above",
                    modifier = Modifier.testTag("due-label"),
                    style = PeskyType.Body,
                )
            }
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "Repeat",
                style = PeskyType.FieldLabel,
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .padding(end = 2.dp),
            )
            Repeat.entries.forEach { option ->
                val selected = repeat == option
                Box(
                    modifier = Modifier
                        .pressable(scale = 0.96f) { onRepeat(option) }
                        .clip(CircleShape)
                        .background(if (selected) PeskyColors.AccentWash else PeskyColors.Field)
                        .border(
                            1.dp,
                            if (selected) PeskyColors.Accent else PeskyColors.FieldBorder,
                            CircleShape,
                        )
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                ) {
                    Text(
                        option.label,
                        fontFamily = DmSans,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = PeskyColors.Text,
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("save-button")
                .then(
                    if (canSave) Modifier.pressable(scale = 0.99f, onClick = onSave)
                    else Modifier
                )
                .clip(CircleShape)
                .background(if (canSave) PeskyColors.Accent else PeskyColors.Field),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Pester me",
                style = PeskyType.Action,
                color = if (canSave) PeskyColors.Screen else PeskyColors.TextMuted,
            )
        }
    }
}
