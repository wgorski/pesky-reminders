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

    private fun active() =
        nm.activeNotifications.firstOrNull { it.id == ReminderContract.NOTIFICATION_ID }

    private fun deliver(action: String) {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            this.action = action
            putExtra(ReminderContract.EXTRA_TEXT, "Buy milk")
        }
        ReminderReceiver().onReceive(context, intent)
        Thread.sleep(300)
    }

    @Before fun clear() {
        ReminderNotifier.cancel(context)
        Thread.sleep(200)
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

    @Test fun schedule_sets_an_exact_alarm_clock() {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val target = System.currentTimeMillis() + 60_000L
        ReminderScheduler.schedule(context, "Buy milk", target)
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
}
