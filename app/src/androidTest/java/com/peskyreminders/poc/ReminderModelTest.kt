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
        Settings.clear(context)
        // Ids restart at 1 after a clear, so this drops anything a previous test
        // left armed — without it `nextAlarmClock` reports a stale alarm.
        for (id in 1..20) {
            ReminderScheduler.cancel(context, id)
            ReminderScheduler.cancelNag(context, id)
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

    @Test fun the_notification_uses_our_own_small_icon() {
        deliver(ReminderContract.ACTION_FIRE)
        val n = active()
        assertNotNull("precondition: posted", n)
        assertEquals(
            "the status-bar icon must be ours, not Android's stock reminder drawable",
            R.drawable.ic_notification,
            n!!.notification.smallIcon.resId,
        )
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
        Reminders.snooze(context, taskId)
        Thread.sleep(300)
        assertNull("snooze clears the current notification", active())
        val next = alarmManager.nextAlarmClock
        assertNotNull("snooze must schedule a new alarm", next)
        val deltaMin = (next!!.triggerTime - System.currentTimeMillis()) / 60_000.0
        assertTrue("alarm ~5 min out (was $deltaMin min)", deltaMin in 4.0..5.5)
    }

    /**
     * The Snooze action must open the picker activity itself. Routing it through
     * a receiver that then starts an activity is a notification trampoline, which
     * Android 12+ blocks outright — the tap would do nothing at all.
     */
    @Test fun the_snooze_action_opens_an_activity_not_a_broadcast() {
        deliver(ReminderContract.ACTION_FIRE)
        val n = active()
        assertNotNull("precondition: posted", n)
        val snooze = n!!.notification.actions.first { it.title == "Snooze" }
        assertTrue("Snooze must be an activity PendingIntent", snooze.actionIntent.isActivity)

        val done = n.notification.actions.first { it.title == "Done" }
        assertTrue("Done stays a broadcast — it shows no UI", done.actionIntent.isBroadcast)
    }

    @Test fun a_chosen_duration_is_what_the_reminder_comes_back_at() {
        deliver(ReminderContract.ACTION_FIRE)
        assertNotNull("precondition: posted", active())

        Reminders.snooze(context, taskId, 45)
        Thread.sleep(300)

        assertNull("snoozing clears the notification", active())
        val minutes = minutesUntilNextAlarm()
        assertTrue("back in ~45 min (was $minutes min)", minutes in 44.0..45.5)
        val stored = stored()
        val outBy = Math.abs(stored.dueMillis - (System.currentTimeMillis() + 45 * 60_000L))
        assertTrue("the task's own due time moves too (out by $outBy ms)", outBy < 2_000L)
    }

    // ---- nagging every five minutes -----------------------------------------

    private fun minutesUntilNextAlarm(): Double {
        val next = context.getSystemService(AlarmManager::class.java).nextAlarmClock
        assertNotNull("expected an alarm to be scheduled", next)
        return (next!!.triggerTime - System.currentTimeMillis()) / 60_000.0
    }

    @Test fun firing_arms_a_nag_five_minutes_out() {
        deliver(ReminderContract.ACTION_FIRE)
        assertNotNull("precondition: posted", active())
        // The task's own alarm already fired, so the only one left is the nag.
        val minutes = minutesUntilNextAlarm()
        assertTrue("nag ~5 min out (was $minutes min)", minutes in 4.0..5.5)
    }

    @Test fun the_nag_keeps_the_notification_up_and_queues_the_next_buzz() {
        deliver(ReminderContract.ACTION_FIRE)
        assertNotNull("precondition: posted", active())

        deliver(ReminderContract.ACTION_NAG)

        assertNotNull("the notification must survive a nag", active())
        val minutes = minutesUntilNextAlarm()
        assertTrue("another nag ~5 min out (was $minutes min)", minutes in 4.0..5.5)
    }

    @Test fun the_nag_chain_stops_once_the_task_is_done() {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        deliver(ReminderContract.ACTION_FIRE)
        deliver(ReminderContract.ACTION_DONE)
        assertNull("precondition: Done cleared it", active())

        // A nag already in flight when Done landed must not resurrect anything.
        deliver(ReminderContract.ACTION_NAG)

        assertNull("a done task must not be nagged back to life", active())
        assertNull("and nothing further should be scheduled", alarmManager.nextAlarmClock)
    }

    @Test fun snooze_cancels_the_nag_and_leaves_only_the_snoozed_alarm() {
        deliver(ReminderContract.ACTION_FIRE)
        Reminders.snooze(context, taskId)
        Thread.sleep(300)
        assertNull("precondition: snooze cleared it", active())

        // The only alarm left is the 5-minute snooze, not a nag on top of it.
        val minutes = minutesUntilNextAlarm()
        assertTrue("snooze alarm ~5 min out (was $minutes min)", minutes in 4.0..5.5)

        // A stale nag must not re-post the notification the user just snoozed.
        deliver(ReminderContract.ACTION_NAG)
        assertNull("a snoozed reminder must stay gone", active())
    }

    /**
     * Regression: the buzz used to go out unattributed, which the platform logs
     * as USAGE_UNKNOWN. Real devices treat that as incidental haptic feedback
     * and drop it behind touch-feedback / ring-mode / DND settings, so the nag
     * was silent on a phone while looking fine on an emulator.
     */
    @Test fun the_buzz_declares_itself_as_an_alarm_not_unknown() {
        // dumpsys keeps a rolling history, so only look at entries logged from
        // here on — otherwise a stale line from an earlier build passes or fails
        // the assertion for us.
        val since = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US)
            .format(java.util.Date())

        ReminderNotifier.vibrate(context)
        Thread.sleep(600)

        val dump = shell("dumpsys vibrator_manager")
        val fresh = dump.lines()
            .filter { it.contains("com.peskyreminders.poc") }
            .filter { it.trimStart().take(18) >= since } // same day, so string order works

        assertTrue("expected a vibration logged after $since", fresh.isNotEmpty())
        // dumpsys prints the usage two ways: "usage: ALARM" in the history rows
        // and "mUsage=ALARM" inside CallerInfo. Accept either, reject UNKNOWN.
        val declaredAlarm = fresh.count {
            it.contains("usage: ALARM") || it.contains("mUsage=ALARM")
        }
        val declaredUnknown = fresh.filter {
            it.contains("usage: UNKNOWN") || it.contains("mUsage=UNKNOWN")
        }
        assertTrue("expected an ALARM-usage buzz, saw: $fresh", declaredAlarm > 0)
        assertTrue(
            "no buzz may go out as USAGE_UNKNOWN — real devices drop those: $declaredUnknown",
            declaredUnknown.isEmpty(),
        )
    }

    private fun shell(command: String): String =
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .executeShellCommand(command)
            .let { java.io.FileInputStream(it.fileDescriptor).bufferedReader().readText() }

    // ---- the nag is configurable --------------------------------------------

    @Test fun turning_nagging_off_means_no_nag_is_armed_at_all() {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        Settings.setNagEnabled(context, false)

        deliver(ReminderContract.ACTION_FIRE)

        assertNotNull("the reminder itself must still fire", active())
        assertNull("but nothing further should be scheduled", alarmManager.nextAlarmClock)
    }

    @Test fun a_custom_interval_is_used_instead_of_five_minutes() {
        Settings.setNagMinutes(context, 20)

        deliver(ReminderContract.ACTION_FIRE)

        val minutes = minutesUntilNextAlarm()
        assertTrue("nag ~20 min out (was $minutes min)", minutes in 19.0..20.5)
    }

    @Test fun turning_nagging_off_stops_a_chain_that_is_already_running() {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        deliver(ReminderContract.ACTION_FIRE)
        assertNotNull("precondition: nag armed", alarmManager.nextAlarmClock)

        Settings.setNagEnabled(context, false)
        Reminders.applyNagSettings(context)

        assertNotNull("the notification stays up", active())
        assertNull("the nag chain must be dropped", alarmManager.nextAlarmClock)
    }

    @Test fun changing_the_interval_re_arms_a_live_nag() {
        deliver(ReminderContract.ACTION_FIRE)
        assertTrue("precondition: ~5 min", minutesUntilNextAlarm() in 4.0..5.5)

        Settings.setNagMinutes(context, 30)
        Reminders.applyNagSettings(context)

        val minutes = minutesUntilNextAlarm()
        assertTrue("re-armed at ~30 min (was $minutes min)", minutes in 29.0..30.5)
    }

    @Test fun a_disabled_nag_that_still_fires_does_not_re_arm_itself() {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        deliver(ReminderContract.ACTION_FIRE)
        Settings.setNagEnabled(context, false)

        // An alarm already in flight when the user flipped the switch.
        deliver(ReminderContract.ACTION_NAG)

        assertNotNull("the notification is untouched", active())
        assertNull("but the chain ends here", alarmManager.nextAlarmClock)
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
