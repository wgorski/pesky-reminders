package com.wgorski.peskyreminders

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
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
        assertEquals("Snooze 15 min + Done", 2, n.notification.actions.size)
        // The button names its duration because it commits to it without asking.
        assertEquals(
            "the snooze action must say how long it snoozes for",
            "Snooze 15 min",
            n.notification.actions[0].title.toString(),
        )
    }

    private fun postedText(): String? =
        active()!!.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    @Test fun the_notification_says_when_the_task_is_due() {
        deliver(ReminderContract.ACTION_FIRE)
        assertNotNull("precondition: posted", active())
        val text = postedText()
        assertTrue("expected an 'Is due …' line, got: $text", text!!.startsWith("Is due "))
    }

    /**
     * The notification must follow the device clock, not the opposite of it.
     *
     * It used to pass the flag negated, so a 24-hour device read "Is due Today,
     * 5:17 PM" directly above a list row saying "Was due Today, 17:17" — and now a
     * toast reporting the same task's snooze would have disagreed with it too.
     */
    @Test fun the_notification_follows_the_devices_own_clock_format() {
        val due = TaskStore.find(context, taskId)!!.dueMillis
        deliver(ReminderContract.ACTION_FIRE)
        assertNotNull("precondition: posted", active())

        val expected = TaskTime.formatTime(due, DateFormat.is24HourFormat(context))
        assertTrue(
            "expected the due time written as '$expected', got: ${postedText()}",
            postedText()!!.contains(expected),
        )
    }

    /** Even late, it stays in the present tense — it is still asking to be done. */
    @Test fun a_late_notification_does_not_say_was_due() {
        TaskStore.clear(context)
        taskId = TaskStore.add(
            context, "Buy milk", System.currentTimeMillis() - 60_000L, Repeat.ONCE,
        ).id
        deliver(ReminderContract.ACTION_FIRE)
        assertNotNull("precondition: posted", active())

        val text = postedText()
        assertTrue("expected an 'Is due …' line, got: $text", text!!.startsWith("Is due "))
        assertFalse("got: $text", text.contains("Was due"))
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

    /**
     * The swipe, driven through the notification's OWN delete-intent — exactly
     * what the OS sends when the user swipes it away, and on Android 14+ the
     * ongoing flag no longer blocks that. Calling `ReminderNotifier.cancel()` plus
     * the broadcast by hand would bypass the real wiring and prove nothing.
     */
    @Test fun dismissing_the_notification_snoozes_it() {
        deliver(ReminderContract.ACTION_FIRE)
        val posted = active()
        assertNotNull("precondition: posted", posted)
        val before = stored().dueMillis

        posted!!.notification.deleteIntent.send()
        Thread.sleep(600)

        assertNull("a swipe must no longer put the notification straight back", active())
        assertTrue(
            "the swipe must push the task forward, not leave it where it was",
            stored().dueMillis > before,
        )
        val minutes = minutesUntilNextAlarm()
        assertTrue("re-armed ~5 min out (was $minutes min)", minutes in 4.0..5.5)
    }

    @Test fun a_swipe_uses_the_interval_from_settings() {
        Settings.setSwipeSnoozeMinutes(context, 30)
        deliver(ReminderContract.ACTION_FIRE)
        val posted = active()
        assertNotNull("precondition: posted", posted)

        posted!!.notification.deleteIntent.send()
        Thread.sleep(600)

        val minutes = minutesUntilNextAlarm()
        assertTrue("re-armed ~30 min out (was $minutes min)", minutes in 29.0..30.5)
    }

    /**
     * A swipe moves one firing, not the cycle. Without the anchor a daily 09:00
     * would slide five minutes later every day it was swiped away.
     */
    @Test fun swiping_a_repeater_keeps_the_slot_it_came_from() {
        TaskStore.clear(context)
        val slot = System.currentTimeMillis() - 1_000L
        taskId = TaskStore.add(context, "Bins", slot, Repeat.DAILY).id
        deliver(ReminderContract.ACTION_FIRE)
        val posted = active()
        assertNotNull("precondition: posted", posted)

        posted!!.notification.deleteIntent.send()
        Thread.sleep(600)

        assertEquals(
            "the recurring slot must survive the swipe",
            slot,
            stored().anchorMillis,
        )
        assertEquals("and slotMillis must still report it", slot, stored().slotMillis)
    }

    // ---- how loudly each post announces itself -------------------------------

    /**
     * The reminder arriving is the one event allowed to make a noise, and the
     * sound lives on the channel — it cannot be overridden per notification on
     * API 26+, so the channel a post lands on *is* whether it sounds.
     */
    @Test fun the_reminder_arriving_lands_on_the_channel_with_a_sound() {
        deliver(ReminderContract.ACTION_FIRE)
        assertEquals(
            ReminderContract.CHANNEL_ID,
            active()!!.notification.channelId,
        )
    }

    /**
     * The nag keeps its buzz — driven by us, not the channel — but must never
     * chime, however many times it repeats.
     */
    @Test fun the_nag_repeats_without_a_sound() {
        deliver(ReminderContract.ACTION_FIRE)
        assertNotNull("precondition: posted", active())

        deliver(ReminderContract.ACTION_NAG)

        val nagged = active()
        assertNotNull("the nag must leave the notification up", nagged)
        assertEquals(
            "a nag must not carry a sound",
            ReminderContract.QUIET_CHANNEL_ID,
            nagged!!.notification.channelId,
        )
    }

    /** Both channels have to exist, or a post to the quiet one is dropped. */
    @Test fun both_channels_are_created() {
        ReminderNotifier.ensureChannel(context)
        assertNotNull(nm.getNotificationChannel(ReminderContract.CHANNEL_ID))
        assertNotNull(nm.getNotificationChannel(ReminderContract.QUIET_CHANNEL_ID))
        assertNull(
            "the quiet channel must have no sound — that is its whole purpose",
            nm.getNotificationChannel(ReminderContract.QUIET_CHANNEL_ID).sound,
        )
        assertNotNull(
            "the reminder channel keeps its sound",
            nm.getNotificationChannel(ReminderContract.CHANNEL_ID).sound,
        )
    }

    /**
     * The arrival buzz belongs to the channel now, so that the system plays it
     * with the notification rather than us racing our own process being frozen.
     * A channel's alerting is frozen at creation, so if this ever fails the id
     * was not bumped and no existing install would pick the change up.
     */
    @Test fun the_reminder_channel_vibrates_and_the_quiet_one_does_not() {
        ReminderNotifier.ensureChannel(context)
        val reminder = nm.getNotificationChannel(ReminderContract.CHANNEL_ID)
        val quiet = nm.getNotificationChannel(ReminderContract.QUIET_CHANNEL_ID)

        assertTrue("an arriving reminder is buzzed by its channel", reminder.shouldVibrate())
        assertNotNull("…to the app's own pattern", reminder.vibrationPattern)
        assertFalse(
            "the quiet channel must not buzz — the nag buzzes itself, and a " +
                "re-post must not buzz at all",
            quiet.shouldVibrate(),
        )
    }

    /** The superseded channels must be gone, or the user sees stale duplicates. */
    @Test fun older_channel_generations_are_removed() {
        ReminderNotifier.ensureChannel(context)
        ReminderContract.LEGACY_CHANNEL_IDS.forEach { old ->
            assertNull("$old should have been deleted", nm.getNotificationChannel(old))
        }
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
        // A reminder that has actually gone off, which is when snooze is used.
        TaskStore.clear(context)
        taskId = TaskStore.add(
            context, "Buy milk", System.currentTimeMillis() - 1_000L, Repeat.ONCE,
        ).id
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

    // ---- snoozing to an absolute time ---------------------------------------

    @Test fun snoozing_until_a_time_arms_that_exact_alarm() {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        TaskStore.clear(context)
        taskId = TaskStore.add(
            context, "Buy milk", System.currentTimeMillis() - 1_000L, Repeat.ONCE,
        ).id
        deliver(ReminderContract.ACTION_FIRE)
        assertNotNull("precondition: posted", active())

        val target = System.currentTimeMillis() + 3 * 60 * 60 * 1000L
        Reminders.snoozeUntil(context, taskId, target)
        Thread.sleep(300)

        assertNull("a future target clears the notification", active())
        val next = alarmManager.nextAlarmClock
        assertNotNull("a future target must arm an alarm", next)
        val delta = Math.abs(next!!.triggerTime - target)
        assertTrue("alarm on the target, not an offset (delta=$delta ms)", delta < 2_000L)
        assertEquals("the stored due time is the target exactly", target, stored().dueMillis)
    }

    /**
     * The sheet can sit open across a rung: opened at 12:59, "13:00" tapped at
     * 13:00:30. Arming setAlarmClock in the past fires it immediately, so this
     * takes the same line as a task created with a past time — pester me now.
     */
    @Test fun snoozing_until_a_time_already_past_keeps_nagging_and_arms_nothing() {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        TaskStore.clear(context)
        taskId = TaskStore.add(
            context, "Buy milk", System.currentTimeMillis() - 1_000L, Repeat.ONCE,
        ).id
        deliver(ReminderContract.ACTION_FIRE)
        assertNotNull("precondition: posted", active())

        val past = System.currentTimeMillis() - 60_000L
        Reminders.snoozeUntil(context, taskId, past)
        Thread.sleep(300)

        assertNotNull("a past target must leave the reminder on screen", active())
        val next = alarmManager.nextAlarmClock
        if (next != null) {
            assertTrue(
                "must not arm an alarm in the past (was ${next.triggerTime})",
                next.triggerTime > System.currentTimeMillis(),
            )
        }
    }

    /** A snooze moves one firing, not the whole cycle. */
    @Test fun snoozing_a_repeater_until_a_time_keeps_its_slot_as_the_anchor() {
        TaskStore.clear(context)
        val slot = System.currentTimeMillis() - 1_000L
        taskId = TaskStore.add(context, "Water the plants", slot, Repeat.DAILY).id
        deliver(ReminderContract.ACTION_FIRE)

        val target = System.currentTimeMillis() + 30 * 60 * 1000L
        Reminders.snoozeUntil(context, taskId, target)
        Thread.sleep(300)

        val task = stored()
        assertEquals("fires at the target", target, task.dueMillis)
        assertEquals("but the recurring slot is preserved", slot, task.anchorMillis)
        assertEquals("and slotMillis reads the anchor", slot, task.slotMillis)
    }

    private fun snoozeAction(n: Notification) =
        n.actions.first { it.title.startsWith("Snooze") }

    /**
     * The Snooze action is a broadcast *because it shows nothing* — it moves the
     * task and posts a toast, exactly as Done does.
     *
     * It used to be an activity, when the action opened the sheet: routing
     * something that shows UI through a receiver is a notification trampoline,
     * which Android 12+ blocks outright. That rule has not changed — it now
     * governs the body tap below, which is the route that still shows the sheet.
     */
    @Test fun the_snooze_action_is_a_broadcast_because_it_shows_nothing() {
        deliver(ReminderContract.ACTION_FIRE)
        val n = active()
        assertNotNull("precondition: posted", n)
        val snooze = snoozeAction(n!!.notification)
        assertTrue("Snooze must be a broadcast, not an activity", snooze.actionIntent.isBroadcast)

        val done = n.notification.actions.first { it.title == "Done" }
        assertTrue("Done stays a broadcast — it shows no UI either", done.actionIntent.isBroadcast)

        // isBroadcast only says it is *a* broadcast, not which one. FLAG_NO_CREATE
        // makes getBroadcast a pure lookup: a non-null result means a
        // PendingIntent with this request code, targeting ReminderReceiver and
        // carrying ACTION_SNOOZE, already exists. (Matching ignores extras, so
        // the task id is not part of what this proves.)
        val resolved = PendingIntent.getBroadcast(
            context,
            ReminderContract.requestCode(taskId, ReminderContract.SLOT_SNOOZE),
            Intent(context, ReminderReceiver::class.java).apply {
                action = ReminderContract.ACTION_SNOOZE
            },
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        assertNotNull("the Snooze action must carry ACTION_SNOOZE", resolved)
    }

    /**
     * The whole point of the label: the action commits on the tap, so the task has
     * to move, and by the amount the button names rather than the API's own
     * five-minute default.
     */
    @Test fun the_snooze_action_moves_the_task_fifteen_minutes_out() {
        deliver(ReminderContract.ACTION_FIRE)
        assertNotNull("precondition: posted", active())

        deliver(ReminderContract.ACTION_SNOOZE)

        assertNull("snoozing clears the notification", active())
        // The nag is cancelled with it, so the task's own alarm is the only one left.
        val minutes = minutesUntilNextAlarm()
        assertTrue("back in ~15 min (was $minutes min)", minutes in 14.0..15.5)
        val target = System.currentTimeMillis() + SnoozeOptions.QUICK_MINUTES * 60_000L
        val outBy = Math.abs(stored().dueMillis - target)
        assertTrue("the task's own due time moves too (out by $outBy ms)", outBy < 2_000L)
    }

    /**
     * The body used to do nothing at all — there was no contentIntent — so the
     * only way to act on a reminder was the two small action buttons.
     *
     * It must not auto-cancel: tapping is not one of the two sanctioned ways to
     * clear a notification you are not allowed to dismiss.
     */
    @Test fun tapping_the_notification_body_opens_the_snooze_sheet() {
        deliver(ReminderContract.ACTION_FIRE)
        val n = active()
        assertNotNull("precondition: posted", n)

        val open = n!!.notification.contentIntent
        assertNotNull("the body must be tappable", open)
        assertTrue("must be an activity; a trampoline is blocked on 12+", open.isActivity)

        // The body is now the *only* route to the sheet — the action beside it
        // snoozes 15 minutes instead of asking — so the two must not be the same
        // PendingIntent, and their request codes have to differ or they collapse.
        assertFalse(
            "the body and the Snooze action are different intents now",
            open == snoozeAction(n.notification).actionIntent,
        )

        // isActivity says nothing about which activity. FLAG_NO_CREATE makes
        // getActivity() a pure lookup: a non-null result means a PendingIntent
        // with this exact request code, targeting ReminderActivity, already
        // exists. Re-point the body at the wrong component and the assertions
        // above would all still be green.
        val expectedTarget = Intent(context, ReminderActivity::class.java)
        val resolved = PendingIntent.getActivity(
            context,
            ReminderContract.requestCode(taskId, ReminderContract.SLOT_OPEN),
            expectedTarget,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        assertNotNull(
            "the body's PendingIntent must target ReminderActivity on SLOT_OPEN",
            resolved,
        )

        assertEquals(
            "tapping must not clear a reminder you cannot dismiss",
            0,
            n.notification.flags and Notification.FLAG_AUTO_CANCEL,
        )
    }

    @Test fun a_chosen_duration_is_what_the_reminder_comes_back_at() {
        TaskStore.clear(context)
        taskId = TaskStore.add(
            context, "Buy milk", System.currentTimeMillis() - 1_000L, Repeat.ONCE,
        ).id
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
        TaskStore.clear(context)
        taskId = TaskStore.add(
            context, "Buy milk", System.currentTimeMillis() - 1_000L, Repeat.ONCE,
        ).id
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
            .filter { it.contains("com.wgorski.peskyreminders") }
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

    @Test fun snoozing_something_not_yet_due_still_counts_from_now() {
        // The fixture's task is 60s out. Five minutes means five from now, not
        // six — the old rule counted from the due time.
        val due = stored().dueMillis
        Reminders.snooze(context, taskId, 5)
        Thread.sleep(200)

        val moved = stored().dueMillis
        val outBy = Math.abs(moved - (System.currentTimeMillis() + 5 * 60_000L))
        assertTrue("5 min from now (out by $outBy ms)", outBy < 2_000L)
        assertTrue("must not count from the due time", moved < due + 5 * 60_000L - 30_000L)
    }

    /**
     * The consequence of counting from the clock: a reminder that was not due for
     * hours comes back sooner than it would have. Deliberate — one predictable
     * rule beats a rule that depends on when the task happened to be due.
     */
    @Test fun rescheduling_can_pull_a_future_task_earlier() {
        TaskStore.clear(context)
        val tomorrow = System.currentTimeMillis() + 24 * 60 * 60_000L
        taskId = TaskStore.add(context, "Pay the water bill", tomorrow, Repeat.ONCE).id

        Reminders.snooze(context, taskId, 30)
        Thread.sleep(200)

        val moved = stored().dueMillis
        assertTrue("it moves earlier, not later", moved < tomorrow)
        val outBy = Math.abs(moved - (System.currentTimeMillis() + 30 * 60_000L))
        assertTrue("and lands 30 min from now (out by $outBy ms)", outBy < 2_000L)
    }

    @Test fun snoozing_an_overdue_task_counts_from_now_too() {
        TaskStore.clear(context)
        taskId = TaskStore.add(
            context, "Water the monstera", System.currentTimeMillis() - 3_600_000L, Repeat.ONCE,
        ).id

        Reminders.snooze(context, taskId, 15)
        Thread.sleep(200)

        val outBy = Math.abs(stored().dueMillis - (System.currentTimeMillis() + 15 * 60_000L))
        assertTrue("15 min from now, not from an hour ago (out by $outBy ms)", outBy < 2_000L)
    }

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

    // ---- deleting one task --------------------------------------------------

    /**
     * The gap this closes: a repeating task rolls forward instead of completing,
     * so it never reaches the done list and `clearDone` can never take it. Before
     * `delete` there was no way to stop one at all.
     */
    @Test fun a_repeating_task_can_finally_be_got_rid_of() {
        TaskStore.clear(context)
        taskId = TaskStore.add(
            context, "Feed the sourdough", System.currentTimeMillis() + 60_000L, Repeat.WEEKLY,
        ).id
        // Ticking it off just moves it on — proof it can never become "done".
        Reminders.toggle(context, taskId)
        assertNotNull("still here, rolled forward", TaskStore.find(context, taskId))
        assertFalse("a repeating task never completes", stored().done)

        Reminders.delete(context, taskId)

        assertNull("delete is its only exit", TaskStore.find(context, taskId))
        assertTrue(TaskStore.tasks.isEmpty())
    }

    @Test fun deleting_takes_the_notification_and_alarms_with_it() {
        ReminderScheduler.schedule(context, stored())
        ReminderScheduler.scheduleNag(context, taskId, System.currentTimeMillis() + 300_000L)
        deliver(ReminderContract.ACTION_FIRE)
        assertNotNull("precondition: the notification is up", active())

        Reminders.delete(context, taskId)
        Thread.sleep(300)

        assertNull("the notification must go with the task", active())
        val am = context.getSystemService(AlarmManager::class.java)
        assertNull("no alarm may outlive the task it belongs to", am.nextAlarmClock)
    }

    @Test fun deleting_one_task_leaves_the_others_alone() {
        val keep = taskId
        val doomed = TaskStore.add(
            context, "Call the vet", System.currentTimeMillis() + 120_000L, Repeat.DAILY,
        ).id

        Reminders.delete(context, doomed)

        assertNull(TaskStore.find(context, doomed))
        assertNotNull("the other task must survive", TaskStore.find(context, keep))
    }

    @Test fun deleting_something_already_gone_is_harmless() {
        Reminders.delete(context, taskId)
        Reminders.delete(context, taskId)
        assertNull(TaskStore.find(context, taskId))
    }

    // ---- clearing the done list ---------------------------------------------

    @Test fun clearing_done_takes_the_finished_and_leaves_the_rest() {
        val keep = taskId
        val finished = TaskStore.add(
            context, "Book dentist", System.currentTimeMillis() + 60_000L, Repeat.ONCE,
        ).id
        TaskStore.replace(context, TaskStore.find(context, finished)!!.copy(done = true))

        val cleared = Reminders.clearDone(context)

        assertEquals(1, cleared)
        assertNull("the completed task must be gone", TaskStore.find(context, finished))
        assertNotNull("an unfinished task must survive", TaskStore.find(context, keep))
    }

    @Test fun clearing_an_all_done_list_empties_it() {
        TaskStore.replace(context, stored().copy(done = true))

        assertEquals(1, Reminders.clearDone(context))
        assertTrue(TaskStore.tasks.isEmpty())
    }

    @Test fun clearing_nothing_is_harmless() {
        assertEquals(0, Reminders.clearDone(context))
        assertNotNull("an unfinished task must not be touched", TaskStore.find(context, taskId))
    }

    /**
     * The reason [Reminders.clearDone] cancels rather than trusting `toggle`: once
     * the task is deleted its id is unreachable, so anything still armed would sit
     * in the alarm table firing on a task that no longer exists.
     */
    @Test fun clearing_takes_the_alarms_with_it() {
        // Arm the lot behind toggle's back, the way a crash mid-tick would leave it.
        ReminderScheduler.schedule(context, stored())
        ReminderScheduler.scheduleNag(context, taskId, System.currentTimeMillis() + 300_000L)
        deliver(ReminderContract.ACTION_FIRE)
        assertNotNull("precondition: the notification is up", active())
        TaskStore.replace(context, stored().copy(done = true))

        Reminders.clearDone(context)
        Thread.sleep(300)

        assertNull("the notification must go with the task", active())
        val am = context.getSystemService(AlarmManager::class.java)
        assertNull(
            "no alarm may outlive the task it belongs to",
            am.nextAlarmClock,
        )
    }

    // ---- a snooze must not drag the whole cycle with it ----------------------

    /** Seeds a daily task whose slot is [minutesAgo] minutes in the past. */
    private fun seedDailyDueAt(minutesAgo: Int): Long {
        TaskStore.clear(context)
        val slot = System.currentTimeMillis() - minutesAgo * 60_000L
        taskId = TaskStore.add(context, "Water the monstera", slot, Repeat.DAILY).id
        return slot
    }

    private fun daysBetween(from: Long, to: Long) = (to - from).toDouble() / 86_400_000.0

    @Test fun snoozing_a_repeater_remembers_the_slot_it_came_from() {
        val slot = seedDailyDueAt(5)

        Reminders.snooze(context, taskId, 30)
        Thread.sleep(200)

        val snoozed = stored()
        assertEquals("the slot must be parked, not lost", slot, snoozed.anchorMillis)
        assertTrue(
            "and this firing must have moved forward",
            snoozed.dueMillis > System.currentTimeMillis(),
        )
    }

    /**
     * The change this all exists for: snoozing this morning's buzz must not shift
     * tomorrow's. Counting from the snooze would drag a 9am daily to 9:30 for good.
     */
    @Test fun finishing_a_snoozed_repeater_counts_from_the_slot_not_the_snooze() {
        val slot = seedDailyDueAt(5)
        Reminders.snooze(context, taskId, 30)
        Thread.sleep(200)

        Reminders.toggle(context, taskId)
        Thread.sleep(200)

        val rolled = stored()
        assertNull("the anchor is spent once the cycle turns over", rolled.anchorMillis)
        assertEquals(
            "tomorrow must be one day on from the original slot",
            1.0,
            daysBetween(slot, rolled.dueMillis),
            0.01,
        )
    }

    @Test fun snoozing_twice_keeps_the_first_slot() {
        val slot = seedDailyDueAt(5)

        Reminders.snooze(context, taskId, 30)
        Thread.sleep(200)
        Reminders.snooze(context, taskId, 15)
        Thread.sleep(200)

        assertEquals("the second snooze must not re-anchor", slot, stored().anchorMillis)
    }

    @Test fun snoozing_a_one_off_records_no_anchor() {
        TaskStore.clear(context)
        taskId = TaskStore.add(
            context, "Buy milk", System.currentTimeMillis() - 60_000L, Repeat.ONCE,
        ).id

        Reminders.snooze(context, taskId, 30)
        Thread.sleep(200)

        assertNull("a one-off has no cycle to protect", stored().anchorMillis)
    }

    @Test fun the_anchor_survives_a_reload_from_disk() {
        val slot = seedDailyDueAt(5)
        Reminders.snooze(context, taskId, 30)
        Thread.sleep(200)

        TaskStore.forgetForTest()

        assertEquals(slot, TaskStore.find(context, taskId)!!.anchorMillis)
    }

    // ---- ticking off early must not skip a cycle ----------------------------

    @Test fun done_on_a_repeater_that_is_not_due_yet_does_nothing() {
        TaskStore.clear(context)
        val due = System.currentTimeMillis() + 2 * 3_600_000L
        taskId = TaskStore.add(context, "Water the monstera", due, Repeat.DAILY).id

        val outcome = Reminders.toggle(context, taskId)
        Thread.sleep(200)

        assertEquals(
            "the refusal has to be reported, or the UI cannot explain itself",
            ToggleOutcome.NOT_DUE_YET,
            outcome,
        )
        val after = stored()
        assertEquals("it must not roll forward a cycle you can still act on", due, after.dueMillis)
        assertFalse("and it must not be marked done either", after.done)
        assertNull(after.anchorMillis)
    }

    /**
     * Completing and reopening are reported apart, not as one "it changed".
     * `ActionToast` picks a different sentence for each, and a done one-off edited
     * into a repeater makes them indistinguishable from the task alone.
     */
    @Test fun a_tick_that_lands_reports_which_way_it_went() {
        TaskStore.clear(context)
        taskId = TaskStore.add(
            context, "Book dentist", System.currentTimeMillis() + 60_000L, Repeat.ONCE,
        ).id
        assertEquals(ToggleOutcome.COMPLETED, Reminders.toggle(context, taskId))
        // And again, reopening it.
        assertEquals(ToggleOutcome.REOPENED, Reminders.toggle(context, taskId))
    }

    @Test fun ticking_a_task_that_is_already_gone_reports_it_missing() {
        Reminders.delete(context, taskId)
        assertEquals(ToggleOutcome.MISSING, Reminders.toggle(context, taskId))
    }

    @Test fun a_repeater_whose_slot_has_come_reports_that_it_rolled() {
        seedDailyDueAt(1)
        assertEquals(ToggleOutcome.ADVANCED, Reminders.toggle(context, taskId))
    }

    /** Only repeaters are protected — finishing a one-off early is just finishing it. */
    @Test fun done_on_a_one_off_that_is_not_due_yet_still_finishes_it() {
        TaskStore.clear(context)
        taskId = TaskStore.add(
            context, "Book dentist", System.currentTimeMillis() + 2 * 3_600_000L, Repeat.ONCE,
        ).id

        Reminders.toggle(context, taskId)
        Thread.sleep(200)

        assertTrue(stored().done)
    }

    /**
     * The guard reads the *slot*, not the fire time, so a task you snoozed a minute
     * ago can still be finished — its slot has passed even though the snooze has not.
     */
    @Test fun a_snoozed_repeater_can_still_be_finished_early() {
        val slot = seedDailyDueAt(1)
        Reminders.snooze(context, taskId, 30)
        Thread.sleep(200)
        assertTrue("precondition: the snoozed firing is ahead", stored().dueMillis > System.currentTimeMillis())

        Reminders.toggle(context, taskId)
        Thread.sleep(200)

        assertEquals(
            "it must roll forward from the slot",
            1.0,
            daysBetween(slot, stored().dueMillis),
            0.01,
        )
    }

    // ---- editing an existing task -------------------------------------------

    @Test fun an_edit_clears_the_snooze_anchor() {
        seedDailyDueAt(5)
        Reminders.snooze(context, taskId, 30)
        Thread.sleep(200)
        assertNotNull("precondition: anchored", stored().anchorMillis)

        Reminders.update(
            context,
            taskId,
            "Water the monstera",
            System.currentTimeMillis() + 3_600_000L,
            Repeat.DAILY,
        )
        Thread.sleep(200)

        assertNull("the time just picked IS the new slot", stored().anchorMillis)
    }

    @Test fun an_edit_keeps_the_id_the_notification_is_built_on() {
        Reminders.update(
            context, taskId, "Buy oat milk", System.currentTimeMillis() + 120_000L, Repeat.WEEKLY,
        )
        val task = stored()
        assertEquals("the id doubles as the notification id — it must not move", taskId, task.id)
        assertEquals("Buy oat milk", task.name)
        assertEquals(Repeat.WEEKLY, task.repeat)
    }

    @Test fun moving_a_task_forward_re_arms_the_alarm() {
        val am = context.getSystemService(AlarmManager::class.java)
        val target = System.currentTimeMillis() + 45 * 60_000L

        Reminders.update(context, taskId, stored().name, target, Repeat.ONCE)
        Thread.sleep(300)

        val next = am.nextAlarmClock
        assertNotNull("an edit into the future must arm an alarm", next)
        val delta = Math.abs(next!!.triggerTime - target)
        assertTrue("alarm within 2s of the new time (delta=$delta ms)", delta < 2_000L)
    }

    /** An alarm set in the past goes off the instant it is armed. */
    @Test fun moving_a_task_into_the_past_arms_nothing() {
        ReminderScheduler.schedule(context, stored())

        Reminders.update(
            context, taskId, stored().name, System.currentTimeMillis() - 60_000L, Repeat.ONCE,
        )
        Thread.sleep(300)

        val am = context.getSystemService(AlarmManager::class.java)
        assertNull("no alarm may be armed in the past", am.nextAlarmClock)
    }

    @Test fun an_edit_that_moves_a_task_forward_clears_its_notification() {
        deliver(ReminderContract.ACTION_FIRE)
        assertNotNull("precondition: posted", active())

        Reminders.update(
            context, taskId, stored().name, System.currentTimeMillis() + 3_600_000L, Repeat.ONCE,
        )
        Thread.sleep(400)

        assertNull("what it was pestering about no longer applies", active())
    }

    /**
     * The one that matters. Opening an overdue task and pressing Save — even with
     * nothing changed — must not clear a reminder the user is not allowed to
     * dismiss. The naive version of [Reminders.update] cancelled unconditionally
     * and defeated the whole app with a no-op.
     */
    @Test fun an_edit_that_leaves_a_task_overdue_keeps_its_notification() {
        TaskStore.clear(context)
        taskId = TaskStore.add(
            context, "Buy milk", System.currentTimeMillis() - 60_000L, Repeat.ONCE,
        ).id
        deliver(ReminderContract.ACTION_FIRE)
        assertNotNull("precondition: posted", active())

        Reminders.update(context, taskId, "Buy oat milk", stored().dueMillis, Repeat.ONCE)
        Thread.sleep(400)

        val n = active()
        assertNotNull("an overdue task's pester must survive its own edit", n)
        assertEquals(
            "and must be re-posted under the new name",
            "Buy oat milk",
            n!!.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
        )
        // The surviving home of the Alert.SILENT routing check: this is now the
        // most reachable Reminders.repost caller, the swipe having stopped being
        // one. The user pressed Save a moment ago — buzzing them about their own
        // edit is noise.
        assertEquals(
            "a re-post must not carry a sound",
            ReminderContract.QUIET_CHANNEL_ID,
            n.notification.channelId,
        )
    }

    @Test fun editing_a_done_task_arms_nothing() {
        TaskStore.replace(context, stored().copy(done = true))

        Reminders.update(
            context, taskId, "Book dentist", System.currentTimeMillis() + 3_600_000L, Repeat.ONCE,
        )
        Thread.sleep(300)

        val am = context.getSystemService(AlarmManager::class.java)
        assertNull("a finished task must not be re-armed by an edit", am.nextAlarmClock)
        assertTrue("and it must stay done", stored().done)
        assertEquals("Book dentist", stored().name)
    }

    @Test fun editing_something_already_gone_is_harmless() {
        Reminders.delete(context, taskId)
        Reminders.update(
            context, taskId, "Ghost", System.currentTimeMillis() + 60_000L, Repeat.ONCE,
        )
        assertNull(TaskStore.find(context, taskId))
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
