package com.peskyreminders.poc

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { MaterialTheme { ReminderScreen(::scheduleReminder) } }
    }

    /** Schedules the reminder [offsetSeconds] from now via ReminderScheduler. */
    private fun scheduleReminder(text: String, offsetSeconds: Long) {
        val triggerAt = ReminderContract.triggerAtMillis(
            System.currentTimeMillis(), offsetSeconds * 1000L
        )
        ReminderScheduler.schedule(this, text, triggerAt)
    }
}

@Composable
private fun ReminderScreen(onSchedule: (String, Long) -> Unit) {
    var text by remember { mutableStateOf("Buy milk") }
    var offset by remember { mutableStateOf("15") }
    var status by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Pesky Reminders", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Reminder text") },
        )
        OutlinedTextField(
            value = offset,
            onValueChange = { offset = it.filter(Char::isDigit) },
            label = { Text("Remind me in (seconds)") },
        )
        Button(onClick = {
            val secs = offset.toLongOrNull() ?: 0L
            onSchedule(text, secs)
            status = "Scheduled to fire in ${secs}s"
        }) { Text("Schedule") }
        if (status.isNotEmpty()) Text(status)
    }
}
