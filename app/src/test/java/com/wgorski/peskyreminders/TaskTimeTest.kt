package com.wgorski.peskyreminders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Exercises the pure date maths behind the list labels and the add sheet. */
class TaskTimeTest {

    /**
     * Pin the zone so these assertions hold on any machine, and the locale because
     * [TaskTime.groupOf] asks it which day a week starts on. US = Sunday-first;
     * [grouping_follows_the_locales_first_day_of_the_week] covers the other case.
     */
    @Before fun fixTimeZoneAndLocale() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
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

    // ---- which section of the list a task lands in ---------------------------

    /** Monday 27 July 2026, 09:00 — mid-week, so every band is reachable. */
    private val monday = at(2026, Calendar.JULY, 27, 9)

    @Test fun anything_already_past_is_overdue_whatever_day_it_is() {
        assertEquals(
            DueGroup.OVERDUE,
            TaskTime.groupOf(at(2026, Calendar.JULY, 25, 9), now),
        )
        // Earlier today counts too — being late is about the clock, not the date.
        assertEquals(
            DueGroup.OVERDUE,
            TaskTime.groupOf(at(2026, Calendar.JULY, 25, 14, 19), now),
        )
        assertEquals(
            DueGroup.OVERDUE,
            TaskTime.groupOf(at(2026, Calendar.JULY, 20, 9), now),
        )
    }

    @Test fun the_rest_of_today_is_today() {
        assertEquals(DueGroup.TODAY, TaskTime.groupOf(at(2026, Calendar.JULY, 25, 14, 21), now))
        assertEquals(DueGroup.TODAY, TaskTime.groupOf(at(2026, Calendar.JULY, 25, 23, 59), now))
    }

    @Test fun the_next_day_is_tomorrow() {
        assertEquals(DueGroup.TOMORROW, TaskTime.groupOf(at(2026, Calendar.JULY, 26, 0, 1), now))
        assertEquals(DueGroup.TOMORROW, TaskTime.groupOf(at(2026, Calendar.JULY, 26, 23, 59), now))
    }

    @Test fun the_days_after_tomorrow_split_by_calendar_week() {
        // From Monday the 27th: the 29th is still this week, the 3rd is next.
        assertEquals(DueGroup.THIS_WEEK, TaskTime.groupOf(at(2026, Calendar.JULY, 29, 9), monday))
        assertEquals(DueGroup.THIS_WEEK, TaskTime.groupOf(at(2026, Calendar.AUGUST, 1, 9), monday))
        assertEquals(DueGroup.NEXT_WEEK, TaskTime.groupOf(at(2026, Calendar.AUGUST, 3, 9), monday))
        assertEquals(DueGroup.NEXT_WEEK, TaskTime.groupOf(at(2026, Calendar.AUGUST, 8, 9), monday))
        assertEquals(DueGroup.LATER, TaskTime.groupOf(at(2026, Calendar.AUGUST, 9, 9), monday))
        assertEquals(DueGroup.LATER, TaskTime.groupOf(at(2026, Calendar.DECEMBER, 9, 9), monday))
    }

    /**
     * Tomorrow is named before the weeks are counted, so a Saturday's Sunday reads
     * "TOMORROW" rather than being swept into next week — which is also why THIS
     * WEEK is simply empty on the last day of a week.
     */
    @Test fun tomorrow_wins_over_the_week_it_falls_in() {
        // `now` is Saturday 25 July; Sunday the 26th starts the next US week.
        assertEquals(1, TaskTime.weekDiff(at(2026, Calendar.JULY, 26, 9), now))
        assertEquals(DueGroup.TOMORROW, TaskTime.groupOf(at(2026, Calendar.JULY, 26, 9), now))
    }

    @Test fun grouping_follows_the_locales_first_day_of_the_week() {
        val sunday = at(2026, Calendar.AUGUST, 2, 9)

        // US weeks start on Sunday, so the 2nd opens a new week from Monday the 27th.
        Locale.setDefault(Locale.US)
        assertEquals(DueGroup.NEXT_WEEK, TaskTime.groupOf(sunday, monday))

        // UK weeks start on Monday, so the 2nd is still the same week's tail.
        Locale.setDefault(Locale.UK)
        assertEquals(DueGroup.THIS_WEEK, TaskTime.groupOf(sunday, monday))
    }

    @Test fun every_group_is_reachable_and_they_come_out_in_order() {
        val samples = listOf(
            at(2026, Calendar.JULY, 26, 9) to DueGroup.OVERDUE, // yesterday, from Monday
            at(2026, Calendar.JULY, 27, 18) to DueGroup.TODAY,
            at(2026, Calendar.JULY, 28, 9) to DueGroup.TOMORROW,
            at(2026, Calendar.JULY, 30, 9) to DueGroup.THIS_WEEK,
            at(2026, Calendar.AUGUST, 4, 9) to DueGroup.NEXT_WEEK,
            at(2026, Calendar.AUGUST, 20, 9) to DueGroup.LATER,
        )
        samples.forEach { (due, expected) ->
            assertEquals("for $due", expected, TaskTime.groupOf(due, monday))
        }
        // Chronological order and declaration order have to agree, because the list
        // lays its sections out by walking the enum.
        val order = samples.map { it.second.ordinal }
        assertEquals(order.sorted(), order)
        assertEquals("every band covered", DueGroup.entries.size, samples.size)
    }

    // ---- what the new-pester sheet opens on ---------------------------------

    @Test fun default_due_is_about_an_hour_out_on_the_hour() {
        // 14:20 + 1h = 15:20, and the nearest hour to that is 15:00.
        assertEquals(at(2026, Calendar.JULY, 25, 15), TaskTime.defaultDue(now))
        assertEquals(
            at(2026, Calendar.JULY, 25, 15),
            TaskTime.defaultDue(at(2026, Calendar.JULY, 25, 14, 29)),
        )
        // 14:59 + 1h = 15:59 rounds *up*, so the default is never a minute away.
        assertEquals(
            at(2026, Calendar.JULY, 25, 16),
            TaskTime.defaultDue(at(2026, Calendar.JULY, 25, 14, 59)),
        )
        // 21:00 has not arrived yet, so this still lands tonight.
        assertEquals(
            at(2026, Calendar.JULY, 25, 22),
            TaskTime.defaultDue(at(2026, Calendar.JULY, 25, 20, 45)),
        )
    }

    @Test fun default_due_gives_up_on_today_once_the_clock_reads_nine() {
        val tomorrowMorning = at(2026, Calendar.JULY, 26, 8)
        assertEquals(tomorrowMorning, TaskTime.defaultDue(at(2026, Calendar.JULY, 25, 21, 0)))
        assertEquals(tomorrowMorning, TaskTime.defaultDue(at(2026, Calendar.JULY, 25, 22, 30)))
        assertEquals(tomorrowMorning, TaskTime.defaultDue(at(2026, Calendar.JULY, 25, 23, 59)))
    }

    /** Past midnight it is a new day, so "in an hour" is back on. */
    @Test fun default_due_comes_back_after_midnight() {
        assertEquals(
            at(2026, Calendar.JULY, 25, 1),
            TaskTime.defaultDue(at(2026, Calendar.JULY, 25, 0, 20)),
        )
    }

    /**
     * The point of rounding to the nearest hour rather than down: a default you
     * are about to be nagged about is no use.
     */
    @Test fun default_due_is_never_less_than_half_an_hour_away() {
        for (hour in 0..23) {
            for (minute in listOf(0, 1, 29, 30, 31, 59)) {
                val nowish = at(2026, Calendar.JULY, 25, hour, minute)
                val ahead = TaskTime.defaultDue(nowish) - nowish
                assertTrue(
                    "at $hour:$minute the default was only ${ahead / 60_000} min out",
                    ahead >= 30 * 60_000L,
                )
            }
        }
    }

    @Test fun default_due_always_lands_on_a_whole_hour() {
        for (hour in 0..23) {
            val due = TaskTime.defaultDue(at(2026, Calendar.JULY, 25, hour, 37))
            assertEquals("at $hour:37", 0, TaskTime.minuteOf(due))
        }
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

    @Test fun the_grid_starts_on_the_locales_first_day_of_the_week() {
        val august = TaskTime.monthStart(now, 1)

        // 1 August 2026 is a Saturday. US weeks open on Sunday, so it is the
        // seventh column — six blanks before it.
        Locale.setDefault(Locale.US)
        assertEquals(6, TaskTime.leadingBlanks(august))
        assertEquals(listOf("S", "M", "T", "W", "T", "F", "S"), TaskTime.weekdayInitials())

        // UK weeks open on Monday, so the same Saturday moves one column left.
        Locale.setDefault(Locale.UK)
        assertEquals(5, TaskTime.leadingBlanks(august))
        assertEquals(listOf("M", "T", "W", "T", "F", "S", "S"), TaskTime.weekdayInitials())
    }

    /**
     * The header letters and the blank count are two halves of one claim, and a
     * later edit could rotate one without the other. Pin them against each
     * other across a whole week: a day's blank count *is* the column it lands
     * in, so the header letter above that column must be that day's own initial.
     *
     * Checked column-by-column rather than by naming days, so the two "S" and
     * two "T" in the row cannot make a wrong rotation pass.
     */
    @Test fun the_grids_header_agrees_with_its_blanks() {
        // Sunday 1 February 2026 through Saturday the 7th — one full week.
        val week = (0..6).map { at(2026, Calendar.FEBRUARY, 1 + it) }
        // Far enough back that formatDay spells the weekday out ("Sun 1 Feb").
        val longAgo = at(2026, Calendar.JANUARY, 1)

        listOf(Locale.US, Locale.UK).forEach { locale ->
            Locale.setDefault(locale)
            val initials = TaskTime.weekdayInitials()
            week.forEach { day ->
                val expected = TaskTime.formatDay(day, longAgo).take(1)
                assertEquals(
                    "$locale, day $day",
                    expected,
                    initials[TaskTime.leadingBlanks(day)],
                )
            }
        }
    }

    /**
     * Counts calendar months, not 30-day steps: the last hour of July and the
     * first of August are one month apart, however close together they are.
     */
    @Test fun the_month_offset_counts_months() {
        assertEquals(0, TaskTime.monthOffsetOf(at(2026, Calendar.JULY, 31, 23), now))
        assertEquals(1, TaskTime.monthOffsetOf(at(2026, Calendar.AUGUST, 1, 0), now))
        assertEquals(-1, TaskTime.monthOffsetOf(at(2026, Calendar.JUNE, 30, 9), now))
        assertEquals(7, TaskTime.monthOffsetOf(at(2027, Calendar.FEBRUARY, 3, 9), now))
        assertEquals(-12, TaskTime.monthOffsetOf(at(2025, Calendar.JULY, 25, 9), now))
    }

    /**
     * The offset only earns its keep if [TaskTime.monthStart] undoes it — that
     * round trip is what puts the edit sheet's calendar on the task's own month.
     */
    @Test fun the_month_offset_round_trips_through_month_start() {
        listOf(
            at(2026, Calendar.JULY, 25, 14) to "July 2026",
            at(2026, Calendar.NOVEMBER, 9, 14) to "November 2026",
            at(2027, Calendar.JANUARY, 1, 8) to "January 2027",
            at(2025, Calendar.DECEMBER, 31, 8) to "December 2025",
        ).forEach { (due, expected) ->
            val opened = TaskTime.monthStart(now, TaskTime.monthOffsetOf(due, now))
            assertEquals(expected, TaskTime.monthTitle(opened))
        }
    }

    @Test fun today_label_is_a_shouty_stamp() {
        assertEquals("SAT 25 JUL", TaskTime.todayLabel(now))
    }
}
