package com.peskyreminders.poc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The durations the snooze picker offers, and how they read. */
class SnoozeOptionsTest {

    @Test fun the_wheel_starts_at_a_quarter_hour_and_reaches_three_days() {
        assertEquals(15, SnoozeOptions.WHEEL.first())
        assertEquals(72 * 60, SnoozeOptions.WHEEL.last())
        assertEquals(72 * 60, SnoozeOptions.MAX_MINUTES)
    }

    @Test fun every_entry_stays_aligned_to_a_quarter_hour() {
        assertTrue(
            "an unaligned entry would label as e.g. '1 hr 7'",
            SnoozeOptions.WHEEL.all { it % SnoozeOptions.STEP_MINUTES == 0 },
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

    @Test fun five_minutes_is_a_preset_only() {
        // It is not a multiple of the step, so the wheel cannot reach it.
        assertTrue(SnoozeOptions.PRESETS.contains(5))
        assertFalse(SnoozeOptions.WHEEL.contains(5))
    }

    @Test fun every_preset_except_five_also_appears_on_the_wheel() {
        SnoozeOptions.PRESETS.filter { it != 5 }.forEach {
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

    // ---- when the clock time is spelled out ---------------------------------

    @Test fun three_hours_and_under_stand_on_their_own() {
        listOf(15, 60, 120, 180).forEach {
            assertFalse(
                "${SnoozeOptions.label(it)} needs no clock time",
                SnoozeOptions.landsAtAClockTime(it),
            )
        }
    }

    @Test fun anything_over_three_hours_gets_a_clock_time() {
        listOf(240, 6 * 60, 24 * 60, 72 * 60).forEach {
            assertTrue(
                "${SnoozeOptions.label(it)} should show where it lands",
                SnoozeOptions.landsAtAClockTime(it),
            )
        }
    }

    @Test fun the_boundary_is_three_hours_exactly() {
        assertEquals(180, SnoozeOptions.CLOCK_TIME_ABOVE_MINUTES)
        assertFalse(SnoozeOptions.landsAtAClockTime(180))
        assertTrue(SnoozeOptions.landsAtAClockTime(181))
    }

    @Test fun chips_split_the_number_from_its_unit() {
        assertEquals("5" to "min", SnoozeOptions.chipLabel(5) to SnoozeOptions.chipUnit(5))
        assertEquals("30" to "min", SnoozeOptions.chipLabel(30) to SnoozeOptions.chipUnit(30))
        assertEquals("1" to "hr", SnoozeOptions.chipLabel(60) to SnoozeOptions.chipUnit(60))
    }

    @Test fun the_default_matches_the_snooze_the_app_shipped_with() {
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
}
