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
}
