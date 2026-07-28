package com.wgorski.peskyreminders.ui

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.wgorski.peskyreminders.ActionToast
import com.wgorski.peskyreminders.Reminders
import com.wgorski.peskyreminders.Settings
import com.wgorski.peskyreminders.TaskStore
import kotlinx.coroutines.delay

@Composable
fun PeskyApp() {
    val context = LocalContext.current
    val use24h = remember(context) { DateFormat.is24HourFormat(context) }

    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    // The tapped task, then the same task once Delete is chosen from its sheet.
    var editTaskId by rememberSaveable { mutableStateOf<Int?>(null) }
    var deleteTaskId by rememberSaveable { mutableStateOf<Int?>(null) }
    // An overdue task tapped in the list, shown the same panel the notification
    // raises. Separate from [editTaskId] so the two sheets can never both be up.
    var remindTaskId by rememberSaveable { mutableStateOf<Int?>(null) }
    var doneExpanded by rememberSaveable { mutableStateOf(false) }
    var clearDoneOpen by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) { Settings.hydrate(context) }

    // Nothing left to clear means nothing left to confirm. Unreachable today —
    // the CLEAR label only exists while the section does — but it keeps a stale
    // flag from raising the sheet over an empty list later on.
    val doneCount = TaskStore.tasks.count { it.done }
    LaunchedEffect(doneCount) { if (doneCount == 0) clearDoneOpen = false }

    // Overdue vs. up-next is a function of the clock, so keep it moving.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = PeskyColors.Accent,
            background = PeskyColors.Screen,
            surface = PeskyColors.Sheet,
            onBackground = PeskyColors.Text,
            onSurface = PeskyColors.Text,
        )
    ) {
        Box(Modifier.fillMaxSize().background(PeskyColors.Screen)) {
            TaskListScreen(
                tasks = TaskStore.tasks,
                nowMillis = now,
                use24h = use24h,
                doneExpanded = doneExpanded,
                onToggleDoneSection = { doneExpanded = !doneExpanded },
                onToggleTask = { id ->
                    // Every outcome has something to say, the refusal included: a
                    // repeater that is not due yet declines the tick on purpose,
                    // and unreported that makes the circle a control which visibly
                    // does nothing. Which sentence is [ActionToast]'s call.
                    val outcome = Reminders.toggle(context, id)
                    now = System.currentTimeMillis()
                    ActionToast.toggled(context, outcome, id, now, use24h)
                },
                onAdd = { sheetOpen = true },
                onOpenSettings = { settingsOpen = true },
                onOpenTask = { editTaskId = it },
                onRemindTask = { remindTaskId = it },
                onClearDone = { clearDoneOpen = true },
            )

            if (clearDoneOpen && doneCount > 0) {
                ClearDoneSheet(
                    count = doneCount,
                    onDismiss = { clearDoneOpen = false },
                    onConfirm = {
                        Reminders.clearDone(context)
                        clearDoneOpen = false
                    },
                )
            }

            // The same panel the notification raises, with the same composable —
            // two variants would drift at the first fix to either one.
            //
            // Only ever reached from an overdue row, which is what makes the
            // snooze safe: the durations count from the clock, so on a task due
            // *tomorrow* a 30-minute snooze would drag it earlier. On something
            // already late, every duration moves it later.
            remindTaskId?.let { id ->
                TaskStore.tasks.firstOrNull { it.id == id }?.let { task ->
                    ReminderSheet(
                        taskName = task.name,
                        nowMillis = now,
                        use24h = use24h,
                        onDismiss = { remindTaskId = null },
                        onDone = {
                            // toggle cannot refuse here: the row is overdue, so
                            // its slot has passed. Same argument as the
                            // notification's own Done.
                            val outcome = Reminders.toggle(context, id)
                            now = System.currentTimeMillis()
                            ActionToast.toggled(context, outcome, id, now, use24h)
                            remindTaskId = null
                        },
                        onSnooze = { minutes ->
                            val outcome = Reminders.snooze(context, id, minutes)
                            // Re-band the row straight away — it has just left
                            // OVERDUE for somewhere in the future. The toast reads
                            // the same refreshed clock, so it cannot name a time
                            // the row's own label disagrees with.
                            now = System.currentTimeMillis()
                            ActionToast.snoozed(context, outcome, id, now, use24h)
                            remindTaskId = null
                        },
                        onSnoozeUntil = { atMillis ->
                            val outcome = Reminders.snoozeUntil(context, id, atMillis)
                            // Same re-band as above. A target already past is the
                            // one case the row stays in OVERDUE, and re-reading
                            // the clock is what keeps it there correctly — and
                            // what has the toast say so rather than claim a move.
                            now = System.currentTimeMillis()
                            ActionToast.snoozed(context, outcome, id, now, use24h)
                            remindTaskId = null
                        },
                    )
                } ?: run { remindTaskId = null }
            }

            editTaskId?.let { id ->
                TaskStore.tasks.firstOrNull { it.id == id }?.let { task ->
                    EditTaskSheet(
                        task = task,
                        nowMillis = now,
                        use24h = use24h,
                        onDismiss = { editTaskId = null },
                        onSave = { name, dueMillis, repeat ->
                            Reminders.update(context, id, name, dueMillis, repeat)
                            now = System.currentTimeMillis()
                            editTaskId = null
                        },
                        onDelete = { editTaskId = null; deleteTaskId = id },
                    )
                } ?: run { editTaskId = null }
            }

            deleteTaskId?.let { id ->
                TaskStore.tasks.firstOrNull { it.id == id }?.let { task ->
                    DeleteTaskSheet(
                        task = task,
                        onDismiss = { deleteTaskId = null },
                        onConfirm = {
                            Reminders.delete(context, id)
                            now = System.currentTimeMillis()
                            deleteTaskId = null
                        },
                    )
                } ?: run { deleteTaskId = null }
            }

            if (sheetOpen) {
                AddTaskSheet(
                    nowMillis = now,
                    use24h = use24h,
                    onDismiss = { sheetOpen = false },
                    onSave = { name, dueMillis, repeat ->
                        Reminders.create(context, name, dueMillis, repeat)
                        now = System.currentTimeMillis()
                        sheetOpen = false
                    },
                )
            }

            if (settingsOpen) {
                SettingsSheet(
                    nagEnabled = Settings.nagEnabled,
                    nagMinutes = Settings.nagMinutes,
                    onNagEnabled = {
                        Settings.setNagEnabled(context, it)
                        Reminders.applyNagSettings(context)
                    },
                    onNagMinutes = {
                        Settings.setNagMinutes(context, it)
                        Reminders.applyNagSettings(context)
                    },
                    onDismiss = { settingsOpen = false },
                )
            }
        }
    }
}
