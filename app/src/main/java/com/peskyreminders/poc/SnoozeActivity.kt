package com.peskyreminders.poc

import android.os.Bundle
import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.peskyreminders.poc.ui.SnoozeSheet

/**
 * The "Snooze until" picker, opened straight from the notification action.
 *
 * It has to be an activity: since Android 12 a notification action cannot hand
 * off to a background receiver that then shows UI, so there is no way to raise
 * this from [ReminderReceiver]. Drawn translucent over whatever is on screen.
 *
 * Backing out leaves the reminder exactly as it was — only the Snooze button
 * commits.
 */
class SnoozeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val taskId = intent.getIntExtra(ReminderContract.EXTRA_TASK_ID, 0)
        val task = TaskStore.find(this, taskId)
        if (task == null) {
            finish()
            return
        }

        setContent {
            SnoozeSheet(
                taskName = task.name,
                nowMillis = System.currentTimeMillis(),
                use24h = DateFormat.is24HourFormat(this),
                onDismiss = { finish() },
                onSnooze = { minutes ->
                    Reminders.snooze(this, taskId, minutes)
                    finish()
                },
            )
        }
    }
}
