package com.wgorski.peskyreminders

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.Calendar.FRIDAY
import java.util.Calendar.MONDAY
import java.util.Calendar.SATURDAY
import java.util.Calendar.SUNDAY
import java.util.Calendar.THURSDAY
import java.util.Calendar.TUESDAY
import java.util.Calendar.WEDNESDAY
import java.util.Locale
import java.util.TimeZone

/** The rule maths behind custom repeats: stepping, snapping, binding and wording. */
class RecurrenceTest {

    /** UTC and US (Sunday-first) unless a test says otherwise; the week tests do. */
    @Before fun fixTimeZoneAndLocale() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
    }

    @After fun restore() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
    }

    private fun at(
        year: Int, month: Int, day: Int, hour: Int = 9, minute: Int = 0,
    ): Long = Calendar.getInstance().apply {
        set(year, month, day, hour, minute, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** The first occurrence after a minute past [slot] — "tick it off right after it fired". */
    private fun next(slot: Long, rule: Repeat) = Recurrence.nextOccurrence(slot, rule, slot + 60_000)

    private fun week(vararg days: Int, every: Int = 1) =
        Repeat(RepeatUnit.WEEK, every, weekdays = days.toSet())

    private fun onDay(day: Int, every: Int = 1) = Repeat(RepeatUnit.MONTH, every, monthDay = day)

    private fun onThe(ordinal: Int, weekday: Int, every: Int = 1) =
        Repeat(RepeatUnit.MONTH, every, ordinal = ordinal, weekday = weekday)

    // Saturday 25 July 2026.
    private val saturday = at(2026, Calendar.JULY, 25)

    // ---- binding and normalising ----------------------------------------------

    @Test fun binding_pins_a_presets_weekday_or_day_to_the_date() {
        assertEquals(week(SATURDAY), Recurrence.bindTo(Repeat.WEEKLY, saturday))
        assertEquals(onDay(25), Recurrence.bindTo(Repeat.MONTHLY, saturday))
        assertEquals(Repeat.DAILY, Recurrence.bindTo(Repeat.DAILY, saturday))
    }

    @Test fun binding_leaves_a_rule_that_names_its_days_alone() {
        assertEquals(week(MONDAY, WEDNESDAY), Recurrence.bindTo(week(MONDAY, WEDNESDAY), saturday))
        assertEquals(onDay(15), Recurrence.bindTo(onDay(15), saturday))
        assertEquals(onThe(2, TUESDAY), Recurrence.bindTo(onThe(2, TUESDAY), saturday))
    }

    @Test fun a_custom_rule_that_says_what_a_preset_says_becomes_the_preset() {
        assertEquals(Repeat.WEEKLY, Recurrence.normalise(week(SATURDAY), saturday))
        assertEquals(Repeat.MONTHLY, Recurrence.normalise(onDay(25), saturday))
        assertEquals(Repeat.DAILY, Recurrence.normalise(Repeat(RepeatUnit.DAY), saturday))
        assertEquals(Repeat.ONCE, Recurrence.normalise(Repeat.ONCE, saturday))
    }

    @Test fun a_rule_the_presets_cannot_say_stays_custom() {
        val friday = at(2026, Calendar.JULY, 24)
        assertEquals(week(SATURDAY), Recurrence.normalise(week(SATURDAY), friday))
        assertEquals(onDay(15), Recurrence.normalise(onDay(15), saturday))
        assertTrue(Recurrence.isCustom(Recurrence.normalise(Repeat(RepeatUnit.DAY, 3), saturday)))
        assertTrue(Recurrence.isCustom(Repeat(RepeatUnit.YEAR)))
        Repeat.PRESETS.forEach { assertFalse(Recurrence.isCustom(it)) }
    }

    // ---- stepping ---------------------------------------------------------------

    @Test fun every_n_days_steps_n_days() {
        val slot = at(2026, Calendar.JULY, 20)
        val now = at(2026, Calendar.JULY, 25, 14, 20)
        assertEquals(
            at(2026, Calendar.JULY, 26),
            Recurrence.nextOccurrence(slot, Repeat(RepeatUnit.DAY, 3), now),
        )
    }

    @Test fun a_weekly_rule_walks_its_days_then_wraps_into_the_next_week() {
        val rule = week(MONDAY, WEDNESDAY)
        assertEquals(at(2026, Calendar.JULY, 29), next(at(2026, Calendar.JULY, 27), rule))
        assertEquals(at(2026, Calendar.AUGUST, 3), next(at(2026, Calendar.JULY, 29), rule))
    }

    @Test fun every_two_weeks_skips_the_week_between() {
        val rule = week(MONDAY, WEDNESDAY, every = 2)
        assertEquals(at(2026, Calendar.JULY, 29), next(at(2026, Calendar.JULY, 27), rule))
        assertEquals(at(2026, Calendar.AUGUST, 10), next(at(2026, Calendar.JULY, 29), rule))
    }

    /**
     * "Every 2 weeks on Sun and Mon" depends on which of the two starts the week.
     * Sunday-first, Monday is the *second* day of a pair; Monday-first, Sunday is
     * the end of the same week.
     */
    @Test fun where_the_week_starts_follows_the_locale() {
        val rule = week(SUNDAY, MONDAY, every = 2)
        val monday = at(2026, Calendar.JULY, 27)
        assertEquals(at(2026, Calendar.AUGUST, 9), next(monday, rule))

        Locale.setDefault(Locale.UK)
        assertEquals(at(2026, Calendar.AUGUST, 2), next(monday, rule))
    }

    @Test fun a_late_day_of_month_falls_back_in_short_months_and_comes_back_after() {
        val rule = onDay(31)
        val february = next(at(2026, Calendar.JANUARY, 31), rule)
        assertEquals(at(2026, Calendar.FEBRUARY, 28), february)
        assertEquals(at(2026, Calendar.MARCH, 31), next(february, rule))
    }

    @Test fun the_last_day_is_always_the_last_day() {
        val rule = onDay(Repeat.LAST)
        val feb = next(at(2026, Calendar.JANUARY, 31), rule)
        val mar = next(feb, rule)
        assertEquals(at(2026, Calendar.FEBRUARY, 28), feb)
        assertEquals(at(2026, Calendar.MARCH, 31), mar)
        assertEquals(at(2026, Calendar.APRIL, 30), next(mar, rule))
    }

    @Test fun the_29th_exists_in_a_leap_february() {
        assertEquals(at(2028, Calendar.FEBRUARY, 29), next(at(2028, Calendar.JANUARY, 29), onDay(29)))
    }

    /** The drift this replaces: stepping from Feb 28 used to leave the task on the 28th. */
    @Test fun the_monthly_preset_remembers_its_day_once_bound() {
        val bound = Recurrence.bindTo(Repeat.MONTHLY, at(2026, Calendar.JANUARY, 31))
        val feb = next(at(2026, Calendar.JANUARY, 31), bound)
        assertEquals(at(2026, Calendar.MARCH, 31), next(feb, bound))
    }

    @Test fun every_two_months_skips_the_month_between() {
        assertEquals(
            at(2026, Calendar.SEPTEMBER, 15),
            next(at(2026, Calendar.JULY, 15), onDay(15, every = 2)),
        )
    }

    @Test fun the_nth_weekday_is_found_in_each_month() {
        // 2nd Tuesday: 14 Jul, then 11 Aug (August starts on a Saturday).
        assertEquals(at(2026, Calendar.AUGUST, 11), next(at(2026, Calendar.JULY, 14), onThe(2, TUESDAY)))
        // 4th Thursday: 22 Oct, then 26 Nov.
        assertEquals(at(2026, Calendar.NOVEMBER, 26), next(at(2026, Calendar.OCTOBER, 22), onThe(4, THURSDAY)))
    }

    @Test fun the_last_weekday_is_counted_back_from_the_end() {
        assertEquals(
            at(2026, Calendar.OCTOBER, 28),
            next(at(2026, Calendar.SEPTEMBER, 30), onThe(Repeat.LAST, WEDNESDAY)),
        )
        // February 2026 ends on a Saturday, so its last Friday is the 27th.
        assertEquals(
            at(2026, Calendar.FEBRUARY, 27),
            next(at(2026, Calendar.JANUARY, 30), onThe(Repeat.LAST, FRIDAY)),
        )
    }

    @Test fun every_n_years_steps_n_years() {
        assertEquals(at(2028, Calendar.JULY, 25), next(saturday, Repeat(RepeatUnit.YEAR, 2)))
    }

    @Test fun a_step_keeps_the_wall_clock_time_across_dst() {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Warsaw"))
        Locale.setDefault(Locale.UK)
        // Clocks go forward overnight into Sunday 29 March 2026.
        val saturdayBefore = at(2026, Calendar.MARCH, 28)
        val landed = next(saturdayBefore, week(SATURDAY, MONDAY))
        assertEquals(at(2026, Calendar.MARCH, 30), landed)
        assertEquals(9, TaskTime.hourOf(landed))
    }

    @Test fun a_slot_long_past_steps_until_it_is_in_the_future() {
        val now = at(2026, Calendar.JULY, 25, 14, 20)
        assertEquals(
            at(2026, Calendar.JULY, 27),
            Recurrence.nextOccurrence(at(2026, Calendar.JUNE, 1), week(MONDAY, WEDNESDAY), now),
        )
    }

    // ---- snapping ---------------------------------------------------------------

    @Test fun snapping_moves_to_the_first_date_the_rule_names_keeping_the_time() {
        val wednesday = at(2026, Calendar.SEPTEMBER, 30, 17, 5)
        assertEquals(at(2026, Calendar.OCTOBER, 15, 17, 5), Recurrence.firstMatchOnOrAfter(wednesday, onDay(15)))
        assertEquals(
            at(2026, Calendar.OCTOBER, 5, 17, 5),
            Recurrence.firstMatchOnOrAfter(at(2026, Calendar.OCTOBER, 1, 17, 5), week(MONDAY, WEDNESDAY)),
        )
        assertEquals(
            at(2026, Calendar.SEPTEMBER, 30),
            Recurrence.firstMatchOnOrAfter(at(2026, Calendar.SEPTEMBER, 1), onThe(Repeat.LAST, WEDNESDAY)),
        )
    }

    @Test fun a_date_that_already_matches_stays_put() {
        val wednesday = at(2026, Calendar.SEPTEMBER, 30, 17, 5)
        assertEquals(wednesday, Recurrence.firstMatchOnOrAfter(wednesday, week(MONDAY, WEDNESDAY)))
        assertEquals(wednesday, Recurrence.firstMatchOnOrAfter(wednesday, onDay(Repeat.LAST)))
    }

    @Test fun presets_and_open_ended_units_never_move_the_date() {
        listOf(Repeat.ONCE, Repeat.DAILY, Repeat.WEEKLY, Repeat.MONTHLY, Repeat(RepeatUnit.YEAR, 2))
            .forEach { assertEquals(saturday, Recurrence.firstMatchOnOrAfter(saturday, it)) }
    }

    // ---- wording ----------------------------------------------------------------

    @Test fun the_sentence_names_the_step_and_the_days() {
        assertEquals("Every 3 days", Recurrence.describe(Repeat(RepeatUnit.DAY, 3), saturday))
        assertEquals("Every 2 weeks on Mon, Wed", Recurrence.describe(week(MONDAY, WEDNESDAY, every = 2), saturday))
        assertEquals("Weekly on Sat", Recurrence.describe(Repeat.WEEKLY, saturday))
        assertEquals("Monthly on the 15th", Recurrence.describe(onDay(15), saturday))
        assertEquals("Monthly on the last day", Recurrence.describe(onDay(Repeat.LAST), saturday))
        assertEquals("Monthly on the 2nd Tuesday", Recurrence.describe(onThe(2, TUESDAY), saturday))
        assertEquals(
            "Every 2 months on the last Friday",
            Recurrence.describe(onThe(Repeat.LAST, FRIDAY, every = 2), saturday),
        )
        assertEquals("Yearly", Recurrence.describe(Repeat(RepeatUnit.YEAR), saturday))
        assertEquals("Every 2 years", Recurrence.describe(Repeat(RepeatUnit.YEAR, 2), saturday))
    }

    @Test fun runs_of_three_days_or_more_collapse() {
        assertEquals(
            "Weekly on Mon–Fri",
            Recurrence.describe(week(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY), saturday),
        )
        assertEquals(
            "Weekly on Mon–Wed, Fri",
            Recurrence.describe(week(MONDAY, TUESDAY, WEDNESDAY, FRIDAY), saturday),
        )
    }

    @Test fun days_are_listed_in_the_locales_week_order() {
        assertEquals("Weekly on Sun, Sat", Recurrence.describe(week(SATURDAY, SUNDAY), saturday))
        Locale.setDefault(Locale.UK)
        assertEquals("Weekly on Sat, Sun", Recurrence.describe(week(SATURDAY, SUNDAY), saturday))
    }

    @Test fun ordinals_take_the_right_suffix() {
        assertEquals(
            listOf("1st", "2nd", "3rd", "4th", "11th", "12th", "13th", "21st", "22nd", "23rd", "31st"),
            listOf(1, 2, 3, 4, 11, 12, 13, 21, 22, 23, 31).map(Recurrence::ordinalSuffix),
        )
    }

    @Test fun the_list_pill_says_a_presets_name_or_a_short_rule() {
        assertEquals("Weekly", Recurrence.shortLabel(week(SATURDAY), saturday))
        assertEquals("Monthly", Recurrence.shortLabel(onDay(25), saturday))
        assertEquals("Mon–Fri", Recurrence.shortLabel(week(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY), saturday))
        assertEquals("Every 2 weeks", Recurrence.shortLabel(week(MONDAY, every = 2), saturday))
        assertEquals("Monthly · 15th", Recurrence.shortLabel(onDay(15), saturday))
        assertEquals("Monthly · last Fri", Recurrence.shortLabel(onThe(Repeat.LAST, FRIDAY), saturday))
        assertEquals("Every 3 days", Recurrence.shortLabel(Repeat(RepeatUnit.DAY, 3), saturday))
        assertEquals("Yearly", Recurrence.shortLabel(Repeat(RepeatUnit.YEAR), saturday))
    }

    // ---- saved state ------------------------------------------------------------

    @Test fun a_rule_survives_being_flattened_to_ints() {
        listOf(
            Repeat.ONCE, Repeat.DAILY, Repeat.WEEKLY, Repeat.MONTHLY,
            week(MONDAY, WEDNESDAY, SATURDAY, every = 3),
            onDay(Repeat.LAST, every = 2),
            onThe(Repeat.LAST, FRIDAY),
            onThe(4, THURSDAY, every = 12),
            Repeat(RepeatUnit.YEAR, 99),
        ).forEach { assertEquals(it, Repeat.fromInts(it.toInts())) }
    }
}
