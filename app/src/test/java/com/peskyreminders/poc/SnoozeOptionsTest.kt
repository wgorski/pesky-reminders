package com.peskyreminders.poc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The durations the snooze picker offers, and how they read. */
class SnoozeOptionsTest {

    @Test fun the_wheel_walks_quarter_hours_up_to_the_cap() {
        assertEquals(15, SnoozeOptions.WHEEL.first())
        assertEquals(180, SnoozeOptions.WHEEL.last())
        assertEquals(12, SnoozeOptions.WHEEL.size)
        assertTrue("every step is a quarter hour", SnoozeOptions.WHEEL.all { it % 15 == 0 })
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
        assertEquals("1 hr", SnoozeOptions.label(60))
        assertEquals("2 hr", SnoozeOptions.label(120))
        assertEquals("3 hr", SnoozeOptions.label(180))
    }

    @Test fun labels_carry_the_remainder_past_the_hour() {
        assertEquals("1 hr 15", SnoozeOptions.label(75))
        assertEquals("1 hr 30", SnoozeOptions.label(90))
        assertEquals("2 hr 45", SnoozeOptions.label(165))
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
