package com.wgorski.peskyreminders

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.util.Calendar
import java.util.TimeZone

/**
 * The half of [ActionToast] that touches Android: it reaches the screen at all,
 * and it reads the task *after* the action rather than before.
 *
 * [ActionToastTest] pins every sentence; this pins the wiring, which is the part
 * a pure test cannot reach. The one thing it deliberately does not assert is that
 * a new toast cancels the previous — Robolectric's [ShadowToast] records what was
 * shown but not what was withdrawn, so that is checked on the emulator instead.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ActionToastShowTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before fun fixTimeZoneAndEmptyTheStore() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        TaskStore.clear(context)
        ShadowToast.reset()
    }

    private fun at(day: Int, hour: Int): Long = Calendar.getInstance().apply {
        set(2026, Calendar.JULY, day, hour, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private val now = at(28, 9)

    @Test fun show_puts_the_message_on_screen() {
        ActionToast.show(context, "Done.")

        assertEquals("Done.", ShadowToast.getTextOfLatestToast())
    }

    @Test fun the_newest_message_is_the_one_showing() {
        ActionToast.show(context, "Done.")
        ActionToast.show(context, "Snoozed until 3:45 PM.")

        assertEquals("Snoozed until 3:45 PM.", ShadowToast.getTextOfLatestToast())
    }

    /**
     * The point of looking the task up inside [ActionToast.toggled] rather than
     * passing it in: `dueMillis` has to be read *after* the roll-forward, or the
     * toast names the occurrence just finished instead of the next one.
     */
    @Test fun toggled_reads_the_task_as_the_action_left_it() {
        val task = TaskStore.add(context, "Bins", at(28, 9), Repeat.DAILY)
        TaskStore.replace(context, task.copy(dueMillis = at(29, 9)))

        ActionToast.toggled(context, ToggleOutcome.ADVANCED, task.id, now, false)

        assertEquals("Done — next Tomorrow 9:00 AM.", ShadowToast.getTextOfLatestToast())
    }

    @Test fun snoozed_reads_the_task_as_the_action_left_it() {
        val task = TaskStore.add(context, "Bins", at(28, 9), Repeat.ONCE)
        TaskStore.replace(context, task.copy(dueMillis = at(29, 8)))

        ActionToast.snoozed(context, SnoozeOutcome.MOVED, task.id, now, false)

        assertEquals("Snoozed until Tomorrow 8:00 AM.", ShadowToast.getTextOfLatestToast())
    }

    /** A task deleted from under the tap has nothing to report, and reports nothing. */
    @Test fun a_missing_task_posts_no_toast_at_all() {
        ActionToast.toggled(context, ToggleOutcome.MISSING, 99, now, false)

        assertNull(ShadowToast.getTextOfLatestToast())
    }
}
