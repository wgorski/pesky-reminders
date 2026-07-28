package com.wgorski.peskyreminders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** The durations the snooze picker offers, and how they read. */
class SnoozeOptionsTest {

    /**
     * The duration tests are pure minute arithmetic and do not care, but
     * [SnoozeOptions.untilPresets] walks a calendar, so the zone has to be
     * pinned or the ladder tests pass only in UTC+0.
     */
    @Before fun fixTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @Test fun the_wheel_starts_at_five_minutes_and_reaches_three_days() {
        assertEquals(5, SnoozeOptions.WHEEL.first())
        assertEquals(72 * 60, SnoozeOptions.WHEEL.last())
        assertEquals(72 * 60, SnoozeOptions.MAX_MINUTES)
    }

    /**
     * Five minutes is the single exception, and it is the first rung: it is the
     * shortest snooze worth offering and it is not a multiple of the step.
     */
    @Test fun every_entry_above_the_first_rung_stays_aligned_to_a_quarter_hour() {
        assertTrue(
            "an unaligned entry would label as e.g. '1 hr 7'",
            SnoozeOptions.WHEEL.drop(1).all { it % SnoozeOptions.STEP_MINUTES == 0 },
        )
    }

    @Test fun the_wheel_only_ever_goes_up() {
        assertEquals(
            "no duplicates and no backwards steps",
            SnoozeOptions.WHEEL.sorted().distinct(),
            SnoozeOptions.WHEEL,
        )
    }

    /**
     * Quarter-hours all the way to 72 hours would be 288 entries to scroll past.
     * The step widens instead, so the whole range stays reachable.
     */
    @Test fun the_step_coarsens_as_the_durations_grow() {
        val wheel = SnoozeOptions.WHEEL
        assertTrue("far fewer than a uniform quarter-hour wheel", wheel.size < 60)

        fun stepAfter(minutes: Int): Int {
            val i = wheel.indexOf(minutes)
            assertTrue("$minutes should be on the wheel", i in 0 until wheel.lastIndex)
            return wheel[i + 1] - minutes
        }
        assertEquals("quarter hours early on", 15, stepAfter(60))
        assertEquals("half hours past two", 30, stepAfter(2 * 60))
        assertEquals("whole hours past six", 60, stepAfter(6 * 60))
        assertEquals("six-hour jumps past a day", 6 * 60, stepAfter(24 * 60))
    }

    @Test fun the_round_durations_people_actually_reach_for_are_all_on_it() {
        listOf(15, 30, 60, 90, 120, 180, 360, 12 * 60, 24 * 60, 48 * 60, 72 * 60).forEach {
            assertTrue("${SnoozeOptions.label(it)} should be pickable", SnoozeOptions.WHEEL.contains(it))
        }
    }

    @Test fun the_presets_are_the_four_common_snoozes() {
        assertEquals(listOf(15, 30, 60, 180), SnoozeOptions.PRESETS)
    }

    /** It lost its chip when the presets became 15/30/1hr/3hr. */
    @Test fun five_minutes_is_reachable_on_the_wheel_but_is_no_longer_a_chip() {
        assertTrue(SnoozeOptions.WHEEL.contains(5))
        assertFalse(SnoozeOptions.PRESETS.contains(5))
    }

    @Test fun every_preset_also_appears_on_the_wheel() {
        SnoozeOptions.PRESETS.forEach {
            assertTrue("$it should be reachable on the wheel", SnoozeOptions.WHEEL.contains(it))
        }
    }

    @Test fun labels_read_as_minutes_below_an_hour() {
        assertEquals("15 min", SnoozeOptions.label(15))
        assertEquals("45 min", SnoozeOptions.label(45))
    }

    @Test fun labels_switch_to_hours_at_sixty() {
        assertEquals("1h", SnoozeOptions.label(60))
        assertEquals("2h", SnoozeOptions.label(120))
        assertEquals("3h", SnoozeOptions.label(180))
    }

    @Test fun labels_carry_the_remainder_past_the_hour() {
        assertEquals("1h 15", SnoozeOptions.label(75))
        assertEquals("1h 30", SnoozeOptions.label(90))
        assertEquals("2h 45", SnoozeOptions.label(165))
    }

    /** Long durations keep counting hours — the clock time carries the meaning. */
    @Test fun labels_keep_counting_hours_past_a_day() {
        assertEquals("23h", SnoozeOptions.label(23 * 60))
        assertEquals("24h", SnoozeOptions.label(24 * 60))
        assertEquals("30h", SnoozeOptions.label(30 * 60))
        assertEquals("48h", SnoozeOptions.label(48 * 60))
        assertEquals("72h", SnoozeOptions.label(72 * 60))
    }

    @Test fun chips_split_the_number_from_its_unit() {
        assertEquals("15" to "min", SnoozeOptions.chipLabel(15) to SnoozeOptions.chipUnit(15))
        assertEquals("30" to "min", SnoozeOptions.chipLabel(30) to SnoozeOptions.chipUnit(30))
        assertEquals("1" to "hr", SnoozeOptions.chipLabel(60) to SnoozeOptions.chipUnit(60))
        assertEquals("3" to "hr", SnoozeOptions.chipLabel(180) to SnoozeOptions.chipUnit(180))
    }

    /**
     * The sheet no longer pre-selects anything, but this is still the default
     * argument on `Reminders.snooze` and `snoozeTriggerAtMillis`.
     */
    @Test fun the_snooze_api_still_defaults_to_five_minutes() {
        assertEquals(5, SnoozeOptions.DEFAULT_MINUTES)
        assertEquals(
            1_000_000L + 5 * 60_000L,
            ReminderContract.snoozeTriggerAtMillis(1_000_000L),
        )
    }

    @Test fun a_chosen_duration_lands_that_many_minutes_out() {
        assertEquals(
            1_000_000L + 45 * 60_000L,
            ReminderContract.snoozeTriggerAtMillis(1_000_000L, 45),
        )
    }

    // ---- the absolute-time ladder --------------------------------------------

    private fun at(
        year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0, second: Int = 0,
    ): Long = Calendar.getInstance().apply {
        set(year, month, day, hour, minute, second)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** July 2026 — the 25th is a Saturday. */
    private fun jul(day: Int, hour: Int, minute: Int = 0, second: Int = 0) =
        at(2026, Calendar.JULY, day, hour, minute, second)

    @Test fun early_morning_offers_all_three_of_today_then_tomorrow_morning() {
        assertEquals(
            listOf(jul(25, 8), jul(25, 13), jul(25, 20), jul(26, 8)),
            SnoozeOptions.untilPresets(jul(25, 5)),
        )
    }

    @Test fun mid_afternoon_skips_the_rungs_already_past() {
        assertEquals(
            listOf(jul(25, 20), jul(26, 8), jul(26, 13), jul(26, 20)),
            SnoozeOptions.untilPresets(jul(25, 14)),
        )
    }

    /** Past the last rung, today drops out entirely and tomorrow leads. */
    @Test fun after_the_evening_rung_the_row_starts_tomorrow() {
        assertEquals(
            listOf(jul(26, 8), jul(26, 13), jul(26, 20), jul(27, 8)),
            SnoozeOptions.untilPresets(jul(25, 20, 0, 1)),
        )
    }

    /**
     * "Strictly after" — a rung landing exactly on the clock is spent. Snoozing
     * to the instant that just arrived would leave the reminder due now.
     */
    @Test fun a_rung_exactly_on_the_clock_is_excluded() {
        val presets = SnoozeOptions.untilPresets(jul(25, 13))
        assertFalse("13:00 today is now, not later", presets.contains(jul(25, 13)))
        assertEquals(jul(25, 20), presets.first())
    }

    @Test fun it_always_offers_exactly_four_however_late_it_is() {
        listOf(jul(25, 0), jul(25, 7, 59), jul(25, 13), jul(25, 23, 59, 59)).forEach { now ->
            assertEquals(
                "four chips at $now",
                SnoozeOptions.UNTIL_COUNT,
                SnoozeOptions.untilPresets(now).size,
            )
        }
    }

    @Test fun every_target_is_in_the_future_and_ascending() {
        val now = jul(25, 14, 20)
        val presets = SnoozeOptions.untilPresets(now)
        assertTrue("all future", presets.all { it > now })
        assertEquals("ascending", presets.sorted(), presets)
    }

    @Test fun every_target_lands_on_a_whole_rung_hour() {
        SnoozeOptions.untilPresets(jul(25, 14, 20)).forEach { target ->
            assertTrue(
                "hour ${TaskTime.hourOf(target)} is not a rung",
                TaskTime.hourOf(target) in SnoozeOptions.UNTIL_HOURS,
            )
            assertEquals("minutes must be zeroed", 0, TaskTime.minuteOf(target))
        }
    }

    /**
     * Wall-clock, not fixed offsets. Europe/Warsaw springs forward at 02:00 on
     * Sunday 29 March 2026, so the day containing the change is 23 hours long —
     * a `+ 864e5` ladder would put every rung after it an hour out.
     */
    @Test fun the_rungs_hold_their_wall_clock_hour_across_a_dst_change() {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Warsaw"))
        val saturdayEvening = at(2026, Calendar.MARCH, 28, 21, 0)
        SnoozeOptions.untilPresets(saturdayEvening).forEach { target ->
            assertTrue(
                "hour ${TaskTime.hourOf(target)} drifted",
                TaskTime.hourOf(target) in SnoozeOptions.UNTIL_HOURS,
            )
        }
    }
}
