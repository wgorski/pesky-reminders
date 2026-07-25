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
import com.peskyreminders.poc.TaskStore
import kotlinx.coroutines.delay

@Composable
fun PeskyApp() {
    val context = LocalContext.current
    val use24h = remember(context) { DateFormat.is24HourFormat(context) }

    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    var doneExpanded by rememberSaveable { mutableStateOf(false) }

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
            )

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
        }
    }
}
