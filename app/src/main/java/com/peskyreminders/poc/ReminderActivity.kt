package com.peskyreminders.poc

import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
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
 *
 * `launchMode="singleTop"` plus [ReminderNotifier]'s `FLAG_ACTIVITY_CLEAR_TOP`
 * mean a second tap while the sheet is still open **reuses this instance**
 * instead of creating a new one — that's what keeps two overdue reminders from
 * stacking translucent activities. But it also means `getIntent()` still
 * returns the *first* task once that happens, so the task id is held in
 * Compose state and refreshed from [onNewIntent], not read once in
 * [onCreate]. Without this, tapping notification A then notification B (without
 * dismissing the sheet in between) would leave the sheet showing A while Done
 * or Snooze acted on it — the wrong task, silently.
 */
class ReminderActivity : ComponentActivity() {

    private val taskId = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        taskId.intValue = intent.getIntExtra(ReminderContract.EXTRA_TASK_ID, 0)

        setContent {
            val id = taskId.intValue
            val task = remember(id) { TaskStore.find(this, id) }
            if (task == null) {
                // Nothing to show for this id — e.g. it was deleted out from
                // under us. Close rather than render a blank sheet.
                LaunchedEffect(id) { close() }
                return@setContent
            }
            ReminderSheet(
                taskName = task.name,
                nowMillis = System.currentTimeMillis(),
                use24h = DateFormat.is24HourFormat(this),
                onDismiss = { close() },
                onDone = {
                    // toggle can refuse a repeater whose slot has not come, but
                    // that cannot happen from here: a notification only exists
                    // once the slot has passed, and every snooze cancels it.
                    // There is no PeskyApp to raise a toast on either — this
                    // activity is closing.
                    Reminders.toggle(this, id)
                    close()
                },
                onSnooze = { minutes ->
                    Reminders.snooze(this, id, minutes)
                    close()
                },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        taskId.intValue = intent.getIntExtra(ReminderContract.EXTRA_TASK_ID, 0)
    }

    /**
     * Every exit from the sheet, and the only one — plain `finish()` would leave
     * this activity's own task behind.
     *
     * The panel lives in a task of its own (`taskAffinity=""` in the manifest),
     * so tearing that task down is what returns the user to whatever was on
     * screen when the notification arrived, rather than to the app.
     */
    private fun close() = finishAndRemoveTask()
}
