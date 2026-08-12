package com.wgorski.peskyreminders

import org.junit.Assert.assertEquals
import org.junit.Test

/** The clamping that keeps a hand-typed nag interval sane. */
class SettingsTest {

    @Test fun a_sensible_interval_is_left_alone() {
        assertEquals(5, Settings.coerceMinutes(5))
        assertEquals(1, Settings.coerceMinutes(1))
        assertEquals(180, Settings.coerceMinutes(180))
    }

    @Test fun zero_and_negatives_are_pulled_up_to_the_minimum() {
        // A zero-minute interval would re-arm the alarm the instant it fired.
        assertEquals(1, Settings.coerceMinutes(0))
        assertEquals(1, Settings.coerceMinutes(-7))
    }

    @Test fun an_absurd_interval_is_pulled_down_to_the_maximum() {
        assertEquals(180, Settings.coerceMinutes(181))
        assertEquals(180, Settings.coerceMinutes(100_000))
        assertEquals(180, Settings.coerceMinutes(Int.MAX_VALUE))
    }

    @Test fun the_default_sits_inside_the_range() {
        assertEquals(
            Settings.DEFAULT_NAG_MINUTES,
            Settings.coerceMinutes(Settings.DEFAULT_NAG_MINUTES),
        )
    }

    // ---- how long a swipe hides a reminder ----------------------------------

    @Test fun a_sensible_swipe_snooze_is_left_alone() {
        assertEquals(5, Settings.coerceSwipeSnoozeMinutes(5))
        assertEquals(1, Settings.coerceSwipeSnoozeMinutes(1))
        assertEquals(180, Settings.coerceSwipeSnoozeMinutes(180))
    }

    @Test fun a_zero_swipe_snooze_is_pulled_up_to_the_minimum() {
        // Zero would put the notification straight back, which is the behaviour
        // this setting exists to replace.
        assertEquals(1, Settings.coerceSwipeSnoozeMinutes(0))
        assertEquals(1, Settings.coerceSwipeSnoozeMinutes(-7))
    }

    @Test fun an_absurd_swipe_snooze_is_pulled_down_to_the_maximum() {
        assertEquals(180, Settings.coerceSwipeSnoozeMinutes(181))
        assertEquals(180, Settings.coerceSwipeSnoozeMinutes(Int.MAX_VALUE))
    }

    @Test fun the_swipe_snooze_default_sits_inside_its_own_range() {
        assertEquals(
            Settings.DEFAULT_SWIPE_SNOOZE_MINUTES,
            Settings.coerceSwipeSnoozeMinutes(Settings.DEFAULT_SWIPE_SNOOZE_MINUTES),
        )
    }

    /**
     * The two clamps have their own bounds even though all six numbers coincide
     * today. They answer different questions and are free to diverge; this pins
     * that neither reads the other's constants.
     */
    @Test fun the_swipe_snooze_clamp_uses_its_own_bounds() {
        assertEquals(
            Settings.MIN_SWIPE_SNOOZE_MINUTES,
            Settings.coerceSwipeSnoozeMinutes(Settings.MIN_SWIPE_SNOOZE_MINUTES - 1),
        )
        assertEquals(
            Settings.MAX_SWIPE_SNOOZE_MINUTES,
            Settings.coerceSwipeSnoozeMinutes(Settings.MAX_SWIPE_SNOOZE_MINUTES + 1),
        )
    }
}
