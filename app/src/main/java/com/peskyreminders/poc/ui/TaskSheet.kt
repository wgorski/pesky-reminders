package com.peskyreminders.poc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.peskyreminders.poc.Repeat
import com.peskyreminders.poc.Task
import com.peskyreminders.poc.TaskTime

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
    var repeat by rememberSaveable(key) { mutableStateOf(existing?.repeat ?: Repeat.ONCE) }
    // Open the calendar on the month the task is due in, not on this one.
    var calOffset by rememberSaveable(key) {
        mutableIntStateOf(existing?.let { TaskTime.monthOffsetOf(it.dueMillis, nowMillis) } ?: 0)
    }

    val commit: (Long) -> Unit = { dueMillis = it }
    val canSave = name.isNotBlank()

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
                editing = existing != null,
                canSave = canSave,
                onSave = { onSave(name.trim(), dueMillis, repeat) },
            )
        },
    ) {
        NameField(name) { name = it }

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
}

/**
 * Deliberately does NOT take focus on open, unlike the design's `autoFocus`:
 * on a phone that would throw the keyboard up over the time pickers before the
 * user has decided whether they even want to type. They tap the field first.
 */
@Composable
private fun NameField(value: String, onValue: (String) -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current

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

        RepeatRow(repeat, onRepeat)

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
 * The four repeat rules, always on one line.
 *
 * The label sits above rather than beside them: inline, it stole just enough
 * width that "Monthly" dropped onto a second row and the footer grew a step. The
 * row scrolls sideways so a large font scale pushes the last chip off the edge
 * instead of wrapping — one row, whatever the text size.
 */
@Composable
private fun RepeatRow(repeat: Repeat, onRepeat: (Repeat) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Repeat", style = PeskyType.FieldLabel)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Repeat.entries.forEach { option ->
                val selected = repeat == option
                Box(
                    modifier = Modifier
                        .testTag("repeat-${option.label}")
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
    }
}
