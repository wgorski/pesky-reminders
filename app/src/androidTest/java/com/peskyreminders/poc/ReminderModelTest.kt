package com.peskyreminders.poc

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReminderModelTest {

    @get:Rule
    val permission: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val nm: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    private var taskId = 0

    private fun active() =
        nm.activeNotifications.firstOrNull { it.id == ReminderContract.notificationId(taskId) }

    private fun stored() = TaskStore.find(context, taskId)!!

    private fun deliver(action: String) {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            this.action = action
            putExtra(ReminderContract.EXTRA_TASK_ID, taskId)
        }
        ReminderReceiver().onReceive(context, intent)
        Thread.sleep(300)
    }

    @Before fun seedOneTask() {
        TaskStore.clear(context)
        // Ids restart at 1 after a clear, so this drops anything a previous test
        // left armed — without it `nextAlarmClock` reports a stale alarm.
        for (id in 1..20) {
            ReminderScheduler.cancel(context, id)
            ReminderNotifier.cancel(context, id)
        }
        taskId = TaskStore.add(
            context,
            "Buy milk",
            System.currentTimeMillis() + 60_000L,
            Repeat.ONCE,
        ).id
        Thread.sleep(200)
    }

    private fun boot() {
        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        Thread.sleep(400)
    }

    @Test fun fire_posts_ongoing_notification_with_two_actions() {
        deliver(ReminderContract.ACTION_FIRE)
        val n = active()
        assertNotNull("expected a posted notification", n)
        assertTrue(
            "ongoing flag must be set (blocks clear-all + swipe-while-locked)",
            (n!!.notification.flags and Notification.FLAG_ONGOING_EVENT) != 0
        )
        assertEquals("Snooze + Done", 2, n.notification.actions.size)
    }

    @Test fun dismissing_notification_triggers_repost() {
        deliver(ReminderContract.ACTION_FIRE)
        val posted = active()
        assertNotNull("precondition: posted", posted)
        // Fire the notification's OWN delete-intent — this is exactly what the OS
        // sends when the user swipes the notification away (on Android 14+ the
        // ongoing flag no longer blocks the swipe, so this path is what matters).
        // We do NOT call ReminderNotifier.cancel() + ACTION_REPOST by hand, because
        // that would bypass the real delete-intent wiring and prove nothing.
        posted!!.notification.deleteIntent.send()
        Thread.sleep(400)
        assertNotNull("dismissal must immediately re-post the notification", active())
    }

    @Test fun done_clears_it() {
        deliver(ReminderContract.ACTION_FIRE)
        assertNotNull("precondition: posted", active())
        deliver(ReminderContract.ACTION_DONE)
        assertNull("Done must clear the notification", active())
    }

    @Test fun done_marks_the_task_complete() {
        assertFalse("precondition: not done", stored().done)
        deliver(ReminderContract.ACTION_DONE)
        assertTrue("Done must tick the task off in the list too", stored().done)
    }

    @Test fun done_on_a_repeating_task_rolls_it_forward_instead() {
        TaskStore.clear(context)
        val due = System.currentTimeMillis() - 60_000L
        taskId = TaskStore.add(context, "Water the monstera", due, Repeat.DAILY).id

        deliver(ReminderContract.ACTION_DONE)

        val task = stored()
        assertFalse("a repeating task is never 'done'", task.done)
        assertTrue("it must move to its next occurrence", task.dueMillis > System.currentTimeMillis())
        val daysLater = (task.dueMillis - due).toDouble() / 86_400_000.0
        assertEquals("exactly one day on", 1.0, daysLater, 0.01)
    }

    @Test fun schedule_sets_an_exact_alarm_clock() {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val target = System.currentTimeMillis() + 60_000L
        ReminderScheduler.schedule(context, stored().copy(dueMillis = target))
        val next = alarmManager.nextAlarmClock
        assertNotNull("an alarm clock must be scheduled", next)
        val delta = Math.abs(next!!.triggerTime - target)
        assertTrue("alarm within 2s of target (delta=$delta ms)", delta < 2_000L)
    }

    @Test fun snooze_clears_and_reschedules_five_minutes_out() {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        deliver(ReminderContract.ACTION_FIRE)
        assertNotNull("precondition: posted", active())
        deliver(ReminderContract.ACTION_SNOOZE)
        assertNull("snooze clears the current notification", active())
        val next = alarmManager.nextAlarmClock
        assertNotNull("snooze must schedule a new alarm", next)
        val deltaMin = (next!!.triggerTime - System.currentTimeMillis()) / 60_000.0
        assertTrue("alarm ~5 min out (was $deltaMin min)", deltaMin in 4.0..5.5)
    }

    // ---- surviving a reboot -------------------------------------------------

    @Test fun boot_rearms_a_pending_alarm() {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val due = System.currentTimeMillis() + 10 * 60_000L
        TaskStore.clear(context)
        taskId = TaskStore.add(context, "Pay the water bill", due, Repeat.ONCE).id
        // A reboot drops every pending alarm but leaves the stored list intact.
        ReminderScheduler.cancel(context, taskId)

        boot()

        val next = alarmManager.nextAlarmClock
        assertNotNull("boot must re-arm the alarm", next)
        val delta = Math.abs(next!!.triggerTime - due)
        assertTrue("re-armed at the original time (delta=$delta ms)", delta < 2_000L)
    }

    @Test fun boot_reposts_a_reminder_that_came_due_while_the_device_was_off() {
        TaskStore.clear(context)
        taskId = TaskStore.add(
            context,
            "Call the vet",
            System.currentTimeMillis() - 60_000L,
            Repeat.ONCE,
        ).id
        ReminderNotifier.cancel(context, taskId)
        Thread.sleep(200)

        boot()

        assertNotNull("a reminder missed while powered off must still nag", active())
    }

    @Test fun boot_leaves_completed_tasks_alone() {
        TaskStore.clear(context)
        taskId = TaskStore.add(
            context,
            "Book dentist",
            System.currentTimeMillis() - 60_000L,
            Repeat.ONCE,
        ).id
        TaskStore.replace(context, TaskStore.find(context, taskId)!!.copy(done = true))
        ReminderNotifier.cancel(context, taskId)
        Thread.sleep(200)

        boot()

        assertNull("a task already ticked off must stay quiet", active())
    }

    @Test fun tasks_survive_a_reload_from_disk() {
        val due = System.currentTimeMillis() + 3_600_000L
        TaskStore.clear(context)
        val saved = TaskStore.add(context, "Pay the water bill", due, Repeat.WEEKLY)

        TaskStore.forgetForTest()
        val reloaded = TaskStore.find(context, saved.id)

        assertNotNull("the task must come back after a cold start", reloaded)
        assertEquals("Pay the water bill", reloaded!!.name)
        assertEquals(due, reloaded.dueMillis)
        assertEquals(Repeat.WEEKLY, reloaded.repeat)
    }
}
