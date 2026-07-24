package com.peskyreminders.poc

import org.junit.Assert.assertEquals
import org.junit.Test

class ReminderContractTest {

    @Test fun triggerAt_adds_offset_to_now() {
        assertEquals(1_015_000L, ReminderContract.triggerAtMillis(1_000_000L, 15_000L))
    }

    @Test fun snooze_is_exactly_five_minutes_out() {
        assertEquals(1_000_000L + 300_000L, ReminderContract.snoozeTriggerAtMillis(1_000_000L))
    }
}
