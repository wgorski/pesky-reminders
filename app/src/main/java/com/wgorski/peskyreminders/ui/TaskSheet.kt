package com.wgorski.peskyreminders.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wgorski.peskyreminders.Recurrence
import com.wgorski.peskyreminders.Repeat
import com.wgorski.peskyreminders.Task
import com.wgorski.peskyreminders.TaskTime

private val R14 = RoundedCornerShape(14.dp)
private val R12 = RoundedCornerShape(12.dp)

/**
 * The "New pester" sheet: a name, a time picked on scroll wheels or a calendar,
 * and a repeat rule.
 *
 * It opens on a time already chosen ([TaskTime.defaultDue]), so the only thing
 * standing between opening it and saving is the name.
 */
@Composable
fun AddTaskSheet(
    nowMillis: Long,
    use24h: Boolean,
    onDismiss: () -> Unit,
    onSave: (name: String, dueMillis: Long, repeat: Repeat) -> Unit,
) = TaskSheet(
    existing = null,
    nowMillis = nowMillis,
    use24h = use24h,
    onDismiss = onDismiss,
    onSave = onSave,
    onDelete = {},
)

/**
 * The same sheet, raised by tapping a task, seeded with everything about it.
 *
 * Name, time and repeat are a *draft*: [onSave] commits all three at once and
 * dismissing throws them away. [onDelete] is not — it acts immediately, closing
 * the sheet and discarding unsaved edits.
 *
 * There is deliberately no "mark as done" here: the check circle in the list does
 * that in one tap, and offering it twice invited the question of whether it saved
 * the draft on the way.
 */
@Composable
fun EditTaskSheet(
    task: Task,
    nowMillis: Long,
    use24h: Boolean,
    onDismiss: () -> Unit,
    onSave: (name: String, dueMillis: Long, repeat: Repeat) -> Unit,
    onDelete: () -> Unit,
) = TaskSheet(
    existing = task,
    nowMillis = nowMillis,
    use24h = use24h,
    onDismiss = onDismiss,
    onSave = onSave,
    onDelete = onDelete,
)

/**
 * One sheet for both jobs. Adding is editing a task that does not exist yet, so
 * the only differences are what the fields start on, the wording, and whether
 * there is anything to act on at the bottom.
 *
 * Keeping them together is deliberate: two copies of a three-way time picker
 * would drift apart at the first fix to either one.
 */
@Composable
private fun TaskSheet(
    existing: Task?,
    nowMillis: Long,
    use24h: Boolean,
    onDismiss: () -> Unit,
    onSave: (name: String, dueMillis: Long, repeat: Repeat) -> Unit,
    onDelete: () -> Unit,
) {
    // Keyed on the task, so the draft resets rather than leaks if this sheet is
    // ever reused for a different one.
    val key = existing?.id
    var name by rememberSaveable(key) { mutableStateOf(existing?.name ?: "") }
    // Never null: an edit starts on the task's time, a new pester on a sensible
    // default. That is what lets the pickers always show a selection, and leaves
    // the name as the only thing Save waits for.
    var dueMillis by rememberSaveable(key) {
        mutableLongStateOf(existing?.dueMillis ?: TaskTime.defaultDue(nowMillis))
    }
    var mode by rememberSaveable(key) { mutableStateOf(EntryMode.WHEELS) }
    // A preset is held loose, so it follows the date; anything else is a custom
    // rule, which the date has to obey — see [commit].
    var repeat by rememberSaveable(key, stateSaver = RepeatSaver) {
        mutableStateOf(existing?.let { Recurrence.normalise(it.repeat, it.dueMillis) } ?: Repeat.ONCE)
    }
    var customOpen by rememberSaveable(key) { mutableStateOf(false) }
    // Open the calendar on the month the task is due in, not on this one.
    var calOffset by rememberSaveable(key) {
        mutableIntStateOf(existing?.let { TaskTime.monthOffsetOf(it.dueMillis, nowMillis) } ?: 0)
    }

    // Under a custom rule a picked date that the rule does not name moves on to
    // the first one it does, keeping the time; the readout says where it went.
    // The calendar follows it there, or the selection would vanish off the page.
    val commit: (Long) -> Unit = { picked ->
        val landed = if (Recurrence.isCustom(repeat)) Recurrence.firstMatchOnOrAfter(picked, repeat) else picked
        if (landed != picked) calOffset = TaskTime.monthOffsetOf(landed, nowMillis)
        dueMillis = landed
    }
    val canSave = name.isNotBlank()

    Box(Modifier.fillMaxSize()) {
        PeskySheet(
            title = if (existing == null) "New pester" else "Edit pester",
            onDismiss = onDismiss,
            footer = {
                SheetFooter(
                    nowMillis = nowMillis,
                    use24h = use24h,
                    dueMillis = dueMillis,
                    repeat = repeat,
                    onRepeat = { repeat = it },
                    onCustom = { customOpen = true },
                    editing = existing != null,
                    canSave = canSave,
                    onSave = { onSave(name.trim(), dueMillis, repeat) },
                )
            },
        ) {
            // A new pester opens with the keyboard already up; an edit does not.
            NameField(name, autoFocus = existing == null) { name = it }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("When?", style = PeskyType.FieldLabel)
                ModeTabs(mode) { mode = it }

                when (mode) {
                    EntryMode.CALENDAR -> CalendarPicker(
                        nowMillis = nowMillis,
                        use24h = use24h,
                        dueMillis = dueMillis,
                        monthOffset = calOffset,
                        onMonthShift = { calOffset += it },
                        onCommit = commit,
                    )

                    EntryMode.WHEELS -> Wheels(
                        nowMillis = nowMillis,
                        use24h = use24h,
                        dueMillis = dueMillis,
                        onCommit = commit,
                    )
                }
            }

            // Only repeaters get one, because only they need one — see [TaskActions].
            if (existing != null && existing.repeats) {
                TaskActions(onDelete = onDelete)
            }
        }

        // Composed after the task sheet, so it draws — and takes Back — on top of it.
        if (customOpen) {
            CustomRepeatSheet(
                seed = repeat,
                dueMillis = dueMillis,
                onDismiss = { customOpen = false },
                onDone = { rule ->
                    customOpen = false
                    val landed = Recurrence.firstMatchOnOrAfter(dueMillis, rule)
                    repeat = Recurrence.normalise(rule, landed)
                    if (landed != dueMillis) calOffset = TaskTime.monthOffsetOf(landed, nowMillis)
                    dueMillis = landed
                },
            )
        }
    }
}

/** The draft rule through process death — [Repeat.toInts] is the whole state. */
private val RepeatSaver = listSaver<Repeat, Int>(
    save = { it.toInts() },
    restore = { Repeat.fromInts(it) },
)

/**
 * Takes focus on open when [autoFocus] is set, which is the add path only.
 *
 * That is a reversal: it used to refuse focus everywhere, because throwing the
 * keyboard up covers the time pickers before the user has decided whether they
 * even want to type. Opening a *new* pester, though, they always do — the name
 * is the one thing the sheet cannot supply a default for, and it is the only
 * thing Save waits on. Editing keeps the old behaviour, because that is usually
 * a trip to change the time and the pickers should be the thing on screen.
 *
 * The keyboard covering the pickers is not hypothetical, it just costs less than
 * the tap it saves: the sheet is `ime`-inset, so its body scrolls from the first
 * frame and the footer stays pinned. Verified at font scale 1.3, where the
 * margin is thinnest.
 *
 * Capitalization is a hint to the IME, not a transform — it opens in shift state
 * and does not fight someone who means to type lowercase.
 */
@Composable
private fun NameField(value: String, autoFocus: Boolean, onValue: (String) -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    if (autoFocus) {
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("What should I nag you about?", style = PeskyType.FieldLabel)
        BasicTextField(
            value = value,
            onValueChange = onValue,
            singleLine = true,
            textStyle = PeskyType.Input,
            cursorBrush = SolidColor(PeskyColors.Accent),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Done,
            ),
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

// ---- actions on an existing task --------------------------------------------

/**
 * Delete, at the foot of the sheet, and only for a repeating task.
 *
 * A one-off has another way out — tick it off and CLEAR the done list — but a
 * repeater never lands in that list: ticking it rolls it forward to its next
 * occurrence, so without this it would pester forever.
 *
 * A hairline above it marks the change of register: everything higher up is a
 * draft waiting for Save, this happens the moment you touch it.
 */
@Composable
private fun TaskActions(onDelete: () -> Unit) {
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(PeskyColors.FieldBorder))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("action-delete")
                .pressable(scale = 0.985f, onClick = onDelete)
                .clip(R14)
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = PeskyIcons.Trash,
                    contentDescription = null,
                    // The accent means "overdue" out in the list, so a destructive
                    // row is marked by the icon alone rather than a full red row.
                    tint = PeskyColors.Accent,
                    modifier = Modifier.size(19.dp),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "Delete",
                    fontFamily = DmSans,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = PeskyColors.Text,
                )
                Text(
                    "Stops it repeating",
                    style = PeskyType.Body,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                )
            }
        }
    }
}

// ---- footer -----------------------------------------------------------------

@Composable
private fun SheetFooter(
    nowMillis: Long,
    use24h: Boolean,
    dueMillis: Long,
    repeat: Repeat,
    onRepeat: (Repeat) -> Unit,
    onCustom: () -> Unit,
    editing: Boolean,
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
            Text(
                // An existing task whose moment has gone says so, in the same words
                // the list row uses. A *new* one does not: a time in the past there
                // means "pester me now", not "you missed it".
                text = TaskTime.formatFull(dueMillis, nowMillis, use24h)
                    .let { if (editing && dueMillis < nowMillis) "Was due $it" else it },
                modifier = Modifier.testTag("due-label"),
                fontFamily = DmSans,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = PeskyColors.Accent,
            )
        }

        RepeatRow(repeat, dueMillis, onRepeat, onCustom)

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
                if (editing) "Save changes" else "Pester me",
                style = PeskyType.Action,
                color = if (canSave) PeskyColors.Text else PeskyColors.TextMuted,
            )
        }
    }
}

/**
 * The four presets and Custom, always on one line.
 *
 * The label sits above rather than beside them: inline, it stole just enough
 * width that "Monthly" dropped onto a second row and the footer grew a step. The
 * row scrolls sideways so a large font scale pushes the last chip off the edge
 * instead of wrapping — one row, whatever the text size.
 *
 * A custom rule is spelt out on the label line, not in its chip: a sentence like
 * "Every 2 weeks on Mon, Wed" cannot fit after Monthly, and the row would have to
 * scroll the presets out of sight every time such a task was opened. The label
 * line was already there, so this costs the sheet no height.
 */
@Composable
private fun RepeatRow(
    repeat: Repeat,
    dueMillis: Long,
    onRepeat: (Repeat) -> Unit,
    onCustom: () -> Unit,
) {
    val custom = Recurrence.isCustom(repeat)
    // Custom is the last chip, so at a large font scale it is the one pushed off
    // the edge. When it is the chosen one, bring it into view.
    val chipScroll = rememberScrollState()
    LaunchedEffect(custom) {
        if (custom) chipScroll.animateScrollTo(chipScroll.maxValue)
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Repeat", style = PeskyType.FieldLabel)
            if (custom) {
                Text(
                    Recurrence.describe(repeat, dueMillis),
                    modifier = Modifier.weight(1f).padding(start = 12.dp).testTag("repeat-summary"),
                    style = PeskyType.FieldLabel,
                    fontWeight = FontWeight.Bold,
                    color = PeskyColors.AccentBright,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(chipScroll),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Repeat.PRESETS.forEach { option ->
                RepeatChip(option.label, selected = repeat == option) { onRepeat(option) }
            }
            // Opens the editor rather than choosing anything itself; on an active
            // custom rule that is how you change it.
            RepeatChip(Repeat.CUSTOM_LABEL, selected = custom, onClick = onCustom)
        }
    }
}

@Composable
private fun RepeatChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .testTag("repeat-$label")
            .pressable(scale = 0.96f, onClick = onClick)
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
            label,
            fontFamily = DmSans,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = PeskyColors.Text,
        )
    }
}
