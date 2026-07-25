package com.peskyreminders.poc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** Exercises the pure date maths behind the list labels and the add sheet. */
class TaskTimeTest {

    /** Pin the zone so these assertions hold on any machine. */
    @Before fun fixTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    private fun at(
        year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0,
    ): Long = Calendar.getInstance().apply {
        set(year, month, day, hour, minute, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    // Saturday 25 July 2026, 14:20.
    private val now = at(2026, Calendar.JULY, 25, 14, 20)

    @Test fun day_labels_are_relative_near_today() {
        assertEquals("Today", TaskTime.formatDay(at(2026, Calendar.JULY, 25, 9), now))
        assertEquals("Tomorrow", TaskTime.formatDay(at(2026, Calendar.JULY, 26, 9), now))
        assertEquals("Yesterday", TaskTime.formatDay(at(2026, Calendar.JULY, 24, 9), now))
    }

    @Test fun day_labels_are_weekday_names_inside_the_week() {
        // 28 July 2026 is a Tuesday, three days out.
        assertEquals("Tue", TaskTime.formatDay(at(2026, Calendar.JULY, 28, 9), now))
    }

    @Test fun day_labels_are_dated_beyond_a_week() {
        assertEquals("Fri 14 Aug", TaskTime.formatDay(at(2026, Calendar.AUGUST, 14, 9), now))
    }

    @Test fun times_respect_the_hour_format() {
        val evening = at(2026, Calendar.JULY, 25, 19, 5)
        assertEquals("7:05 PM", TaskTime.formatTime(evening, use24h = false))
        assertEquals("19:05", TaskTime.formatTime(evening, use24h = true))
        assertEquals("12:00 AM", TaskTime.formatTime(at(2026, Calendar.JULY, 25, 0, 0), false))
        assertEquals("12:30 PM", TaskTime.formatTime(at(2026, Calendar.JULY, 25, 12, 30), false))
    }

    @Test fun full_label_joins_day_and_time() {
        assertEquals(
            "Today, 3:00 PM",
            TaskTime.formatFull(at(2026, Calendar.JULY, 25, 15, 0), now, use24h = false),
        )
    }

    /** Today needs no naming — the compact form is for labels with no room. */
    @Test fun the_compact_label_drops_today() {
        assertEquals(
            "21:00",
            TaskTime.formatCompact(at(2026, Calendar.JULY, 25, 21, 0), now, use24h = true),
        )
    }

    @Test fun the_compact_label_keeps_the_day_when_it_is_not_today() {
        assertEquals(
            "Tomorrow 09:00",
            TaskTime.formatCompact(at(2026, Calendar.JULY, 26, 9, 0), now, use24h = true),
        )
        assertEquals(
            "Tue 20:00",
            TaskTime.formatCompact(at(2026, Calendar.JULY, 28, 20, 0), now, use24h = true),
        )
    }

    @Test fun the_compact_label_follows_the_twelve_hour_setting() {
        assertEquals(
            "9:00 PM",
            TaskTime.formatCompact(at(2026, Calendar.JULY, 25, 21, 0), now, use24h = false),
        )
    }

    @Test fun day_diff_counts_calendar_days_not_elapsed_hours() {
        // 23:00 today to 01:00 tomorrow is two hours, but one day.
        val late = at(2026, Calendar.JULY, 25, 23)
        val early = at(2026, Calendar.JULY, 26, 1)
        assertEquals(1, TaskTime.dayDiff(early, late))
    }

    @Test fun once_never_reschedules() {
        val due = at(2026, Calendar.JULY, 20, 9)
        assertEquals(due, TaskTime.nextOccurrence(due, Repeat.ONCE, now))
    }

    @Test fun repeats_skip_forward_past_now() {
        val due = at(2026, Calendar.JULY, 20, 9) // five days ago
        val daily = TaskTime.nextOccurrence(due, Repeat.DAILY, now)
        assertEquals(at(2026, Calendar.JULY, 26, 9), daily)

        val weekly = TaskTime.nextOccurrence(due, Repeat.WEEKLY, now)
        assertEquals(at(2026, Calendar.JULY, 27, 9), weekly)

        val monthly = TaskTime.nextOccurrence(due, Repeat.MONTHLY, now)
        assertEquals(at(2026, Calendar.AUGUST, 20, 9), monthly)
    }

    @Test fun a_future_repeat_still_steps_at_least_once() {
        val due = at(2026, Calendar.JULY, 26, 9)
        assertEquals(
            at(2026, Calendar.JULY, 27, 9),
            TaskTime.nextOccurrence(due, Repeat.DAILY, now),
        )
    }

    @Test fun quick_picks_land_where_the_design_says() {
        val picks = TaskTime.quickPicks(now).associateBy { it.key }
        assertEquals(6, picks.size)

        // now + 3h = 17:20, rounded up to the next quarter hour.
        assertEquals(at(2026, Calendar.JULY, 25, 17, 30), picks.getValue("later").whenMillis)
        // Before 19:00, so "tonight" is 20:00 today.
        assertEquals(at(2026, Calendar.JULY, 25, 20), picks.getValue("tonight").whenMillis)
        assertEquals(at(2026, Calendar.JULY, 26, 9), picks.getValue("tom-am").whenMillis)
        assertEquals(at(2026, Calendar.JULY, 26, 19), picks.getValue("tom-pm").whenMillis)
        // Today IS Saturday, so "this weekend" means next Saturday, never today.
        assertEquals(at(2026, Calendar.AUGUST, 1, 10), picks.getValue("weekend").whenMillis)
        assertEquals(at(2026, Calendar.JULY, 27, 9), picks.getValue("nextweek").whenMillis)
    }

    private fun tonightAt(nowMillis: Long): Long =
        TaskTime.quickPicks(nowMillis).first { it.key == "tonight" }.whenMillis

    @Test fun tonight_rolls_over_once_the_evening_has_started() {
        val late = at(2026, Calendar.JULY, 25, 21, 0)
        assertEquals(at(2026, Calendar.JULY, 26, 20), tonightAt(late))
    }

    /**
     * The bug this replaces: the roll-over tested `HOUR_OF_DAY >= 19` while the
     * chip pointed at 20:00, so for the whole 19:00–20:00 hour "Tonight" offered
     * tomorrow when tonight had not happened yet. The old tests sat at 14:20 and
     * 21:00 — either side of the only hour that was broken.
     */
    @Test fun tonight_still_means_tonight_during_the_hour_before_it() {
        assertEquals(at(2026, Calendar.JULY, 25, 20), tonightAt(at(2026, Calendar.JULY, 25, 19, 36)))
    }

    @Test fun tonight_holds_until_the_last_minute_before_eight() {
        assertEquals(at(2026, Calendar.JULY, 25, 20), tonightAt(at(2026, Calendar.JULY, 25, 19, 59)))
    }

    @Test fun tonight_rolls_the_moment_eight_arrives() {
        assertEquals(at(2026, Calendar.JULY, 26, 20), tonightAt(at(2026, Calendar.JULY, 25, 20, 0)))
    }

    @Test fun tonight_is_never_in_the_past() {
        // Every minute of the day, at both ends of an hour.
        for (hour in 0..23) {
            for (minute in listOf(0, 59)) {
                val now = at(2026, Calendar.JULY, 25, hour, minute)
                assertTrue(
                    "tonight must stay ahead of $hour:$minute",
                    tonightAt(now) > now,
                )
            }
        }
    }

    @Test fun default_due_is_the_next_whole_hour_three_hours_out() {
        assertEquals(at(2026, Calendar.JULY, 25, 17), TaskTime.defaultDue(now))
    }

    @Test fun steppers_move_the_field_they_name() {
        val start = at(2026, Calendar.JULY, 25, 17, 0)
        assertEquals(at(2026, Calendar.JULY, 25, 18, 0), TaskTime.shiftHours(start, 1))
        assertEquals(at(2026, Calendar.JULY, 25, 17, 15), TaskTime.shiftMinutes(start, 15))
        assertEquals(at(2026, Calendar.JULY, 25, 9, 0), TaskTime.withTimeOfDay(start, 9))
        assertEquals(at(2026, Calendar.JULY, 25, 21, 0), TaskTime.withHour(start, 21))
        assertEquals(at(2026, Calendar.JULY, 25, 17, 45), TaskTime.withMinute(start, 45))
    }

    @Test fun day_wheel_keeps_the_time_and_moves_the_date() {
        val start = at(2026, Calendar.JULY, 25, 17, 30)
        // Offset 3 means "three days from today", regardless of where we started.
        assertEquals(
            at(2026, Calendar.JULY, 28, 17, 30),
            TaskTime.withDayOffset(start, now, 3),
        )
    }

    @Test fun calendar_grid_lines_up_with_the_month() {
        val august = TaskTime.monthStart(now, 1)
        assertEquals("August 2026", TaskTime.monthTitle(august))
        assertEquals(31, TaskTime.daysInMonth(august))
        // 1 August 2026 is a Saturday — six blanks before it.
        assertEquals(6, TaskTime.leadingBlanks(august))
        val september = TaskTime.monthStart(now, 2)
        assertEquals("September 2026", TaskTime.monthTitle(september))
        assertEquals(30, TaskTime.daysInMonth(september))
    }

    @Test fun today_label_is_a_shouty_stamp() {
        assertEquals("SAT 25 JUL", TaskTime.todayLabel(now))
    }

    @Test fun later_today_is_always_in_the_future() {
        val later = TaskTime.quickPicks(now).first { it.key == "later" }
        assertTrue(later.whenMillis > now)
    }
}
