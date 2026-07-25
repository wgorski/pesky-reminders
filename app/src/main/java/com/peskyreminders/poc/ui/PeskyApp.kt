package com.peskyreminders.poc.ui

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
import com.peskyreminders.poc.Reminders
import com.peskyreminders.poc.Settings
import com.peskyreminders.poc.TaskStore
import kotlinx.coroutines.delay

@Composable
fun PeskyApp() {
    val context = LocalContext.current
    val use24h = remember(context) { DateFormat.is24HourFormat(context) }

    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    // Long-pressed task, then the same task once Snooze is chosen from its menu.
    var actionsTaskId by rememberSaveable { mutableStateOf<Int?>(null) }
    var snoozeTaskId by rememberSaveable { mutableStateOf<Int?>(null) }
    var deleteTaskId by rememberSaveable { mutableStateOf<Int?>(null) }
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
                    Reminders.toggle(context, id)
                    now = System.currentTimeMillis()
                },
                onAdd = { sheetOpen = true },
                onOpenSettings = { settingsOpen = true },
                onTaskActions = { actionsTaskId = it },
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

            actionsTaskId?.let { id ->
                TaskStore.tasks.firstOrNull { it.id == id }?.let { task ->
                    TaskActionsSheet(
                        task = task,
                        nowMillis = now,
                        use24h = use24h,
                        onReschedule = { actionsTaskId = null; snoozeTaskId = id },
                        onToggle = {
                            Reminders.toggle(context, id)
                            now = System.currentTimeMillis()
                            actionsTaskId = null
                        },
                        onDelete = { actionsTaskId = null; deleteTaskId = id },
                        onDismiss = { actionsTaskId = null },
                    )
                } ?: run { actionsTaskId = null }
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

            snoozeTaskId?.let { id ->
                TaskStore.tasks.firstOrNull { it.id == id }?.let { task ->
                    SnoozeSheet(
                        taskName = task.name,
                        nowMillis = now,
                        use24h = use24h,
                        title = "Reschedule",
                        readoutPrefix = "Moves to",
                        confirmLabel = "Reschedule",
                        onDismiss = { snoozeTaskId = null },
                        onSnooze = { minutes ->
                            Reminders.snooze(context, id, minutes)
                            now = System.currentTimeMillis()
                            snoozeTaskId = null
                        },
                    )
                } ?: run { snoozeTaskId = null }
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
