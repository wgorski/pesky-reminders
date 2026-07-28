package com.wgorski.peskyreminders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * What the app says after a snooze or a done.
 *
 * Pure string building — no clock, no context — which is what lets every sentence
 * the app can utter be pinned here rather than read off a screenshot. Every time
 * goes through [TaskTime], so these also pin that a toast cannot invent a format.
 */
class ActionToastTest {

    @Before fun fixTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance().apply {
            set(2026, Calendar.JULY, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    /** Tuesday 28 July 2026, 09:05. */
    private val now = at(28, 9, 5)

    private fun task(dueMillis: Long, repeat: Repeat = Repeat.ONCE, done: Boolean = false) =
        Task(id = 1, name = "Bins", dueMillis = dueMillis, repeat = repeat, done = done)

    // ---- done ---------------------------------------------------------------

    @Test fun a_completed_one_off_says_only_that() {
        assertEquals(
            "Done.",
            ActionToast.forToggle(
                ToggleOutcome.COMPLETED, task(at(28, 9), done = true), now, false,
            ),
        )
    }

    @Test fun reopening_says_where_it_went() {
        assertEquals(
            "Back on the list.",
            ActionToast.forToggle(ToggleOutcome.REOPENED, task(at(28, 9)), now, false),
        )
    }

    /**
     * The most opaque thing the app does: the row appears to stay put, having moved
     * from OVERDUE to some future band, and the new time is never named on screen.
     */
    @Test fun an_advanced_repeater_names_its_next_occurrence() {
        assertEquals(
            "Done — next Tomorrow 9:00 AM.",
            ActionToast.forToggle(
                ToggleOutcome.ADVANCED, task(at(29, 9), Repeat.DAILY), now, false,
            ),
        )
    }

    @Test fun a_refused_tick_keeps_the_wording_it_already_had() {
        assertEquals(
            "Not due until Tomorrow, 8:00 AM.",
            ActionToast.forToggle(
                ToggleOutcome.NOT_DUE_YET, task(at(29, 8), Repeat.DAILY), now, false,
            ),
        )
    }

    @Test fun a_task_deleted_from_under_the_tap_says_nothing() {
        assertNull(ActionToast.forToggle(ToggleOutcome.MISSING, null, now, false))
        assertNull(ActionToast.forSnooze(SnoozeOutcome.MISSING, null, now, false))
    }

    // ---- snooze -------------------------------------------------------------

    /** Same day, so the day is redundant. This is the whole reason it is formatCompact. */
    @Test fun a_snooze_landing_today_names_only_the_time() {
        assertEquals(
            "Snoozed until 3:45 PM.",
            ActionToast.forSnooze(SnoozeOutcome.MOVED, task(at(28, 15, 45)), now, false),
        )
    }

    @Test fun a_snooze_landing_tomorrow_names_the_day_too() {
        assertEquals(
            "Snoozed until Tomorrow 8:00 AM.",
            ActionToast.forSnooze(SnoozeOutcome.MOVED, task(at(29, 8)), now, false),
        )
    }

    /**
     * The sheet sat open across the rung it was offering, so the task did not move
     * — it is still overdue with its notification live. "Snoozed until 8:00 AM"
     * would be the one case where the toast contradicts what the app did.
     */
    @Test fun a_target_that_has_gone_by_says_so_instead() {
        assertEquals(
            "8:00 AM has passed — still due.",
            ActionToast.forSnooze(SnoozeOutcome.ALREADY_PAST, task(at(28, 8)), now, false),
        )
    }

    // ---- the clock the user set ---------------------------------------------

    @Test fun a_24_hour_device_gets_24_hour_times() {
        assertEquals(
            "Snoozed until 15:45.",
            ActionToast.forSnooze(SnoozeOutcome.MOVED, task(at(28, 15, 45)), now, true),
        )
        assertEquals(
            "Done — next Tomorrow 09:00.",
            ActionToast.forToggle(
                ToggleOutcome.ADVANCED, task(at(29, 9), Repeat.DAILY), now, true,
            ),
        )
    }
}
