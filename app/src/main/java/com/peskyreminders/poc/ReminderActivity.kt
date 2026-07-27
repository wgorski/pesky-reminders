package com.peskyreminders.poc

import android.os.Bundle
import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.peskyreminders.poc.ui.ReminderSheet

/**
 * The reminder's action sheet, opened straight from the notification — by a tap
 * on its body or on its Snooze action.
 *
 * It has to be an activity: since Android 12 a notification action cannot hand
 * off to a background receiver that then shows UI, so there is no way to raise
 * this from [ReminderReceiver]. Drawn translucent over whatever is on screen.
 *
 * Backing out — the close button, the scrim, or the back gesture — leaves the
 * reminder exactly as it was. Everything else in the sheet commits on the tap.
 */
class ReminderActivity : ComponentActivity() {

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
            ReminderSheet(
                taskName = task.name,
                nowMillis = System.currentTimeMillis(),
                use24h = DateFormat.is24HourFormat(this),
                onDismiss = { finish() },
                onDone = {
                    // toggle can refuse a repeater whose slot has not come, but
                    // that cannot happen from here: a notification only exists
                    // once the slot has passed, and every snooze cancels it.
                    // There is no PeskyApp to raise a toast on either — this
                    // activity is closing.
                    Reminders.toggle(this, taskId)
                    finish()
                },
                onSnooze = { minutes ->
                    Reminders.snooze(this, taskId, minutes)
                    finish()
                },
            )
        }
    }
}
