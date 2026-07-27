package com.peskyreminders.poc.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.peskyreminders.poc.SnoozeOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Calendar
import java.util.TimeZone

/**
 * Drives the notification's action sheet on the JVM against a frozen clock.
 *
 * Same caveat as the other sheet tests: Compose's pointer injection does not
 * reach into a sheet body under Robolectric, so controls are asserted displayed
 * and then their click action is fired directly.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class ReminderSheetTest {

    @get:Rule val compose = createComposeRule()

    @Before fun fixTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    /** Saturday 25 July 2026, 14:20 UTC. */
    private val now: Long
        get() = Calendar.getInstance().apply {
            set(2026, Calendar.JULY, 25, 14, 20, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private var snoozed: Int? = null
    private var done = false
    private var dismissed = false

    private fun show() {
        compose.setContent {
            ReminderSheet(
                taskName = "Water the monstera",
                nowMillis = now,
                use24h = false,
                onDismiss = { dismissed = true },
                onDone = { done = true },
                onSnooze = { snoozed = it },
            )
        }
    }

    private fun act(node: SemanticsNodeInteraction) {
        runCatching { node.performScrollTo() }
        node.assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private fun tapPreset(minutes: Int) = act(compose.onNodeWithTag("preset-$minutes"))

    private fun tapWheel(minutes: Int) {
        val index = SnoozeOptions.WHEEL.indexOf(minutes)
        compose.onNodeWithTag("wheel-SNOOZE").performScrollToNode(hasTestTag("SNOOZE-$index"))
        act(compose.onNodeWithTag("SNOOZE-$index"))
    }

    /** Brings a wheel entry into view without selecting it. */
    private fun scrollToWheel(minutes: Int) {
        val index = SnoozeOptions.WHEEL.indexOf(minutes)
        compose.onNodeWithTag("wheel-SNOOZE").performScrollToNode(hasTestTag("SNOOZE-$index"))
        compose.waitForIdle()
    }

    // ---- rendering ----------------------------------------------------------

    @Test fun the_title_is_the_task_being_acted_on() {
        show()
        compose.onNodeWithTag("sheet-title").assertTextEquals("Water the monstera")
    }

    @Test fun finishing_it_is_offered_first() {
        show()
        compose.onNodeWithTag("done-button").assertIsDisplayed()
        compose.onNodeWithText("Done").assertIsDisplayed()
    }

    @Test fun every_preset_is_offered() {
        show()
        listOf(15, 30, 60, 180).forEach {
            compose.onNodeWithTag("preset-$it").assertIsDisplayed()
        }
    }

    @Test fun both_ways_in_are_labelled() {
        show()
        compose.onNodeWithText("Snooze for").assertIsDisplayed()
        compose.onNodeWithText("…or dial it in").assertIsDisplayed()
    }

    /** Every control commits on the tap, so there is nothing left to confirm. */
    @Test fun there_is_no_confirm_step() {
        show()
        compose.onNodeWithTag("snooze-button").assertDoesNotExist()
        compose.onNodeWithTag("back-at").assertDoesNotExist()
    }

    // ---- the clock time beside every duration -------------------------------

    /**
     * The footer readout is gone, so the wheel is the only place a landing time
     * appears. Short durations used to be left to speak for themselves.
     */
    @Test fun even_the_shortest_rung_shows_where_it_lands() {
        show()
        scrollToWheel(5)
        compose.onNodeWithText("5 min").assertExists()
        compose.onNodeWithText("(2:25 PM)").assertExists()
    }

    @Test fun a_quarter_hour_shows_where_it_lands() {
        show()
        scrollToWheel(15)
        compose.onNodeWithText("15 min").assertExists()
        compose.onNodeWithText("(2:35 PM)").assertExists()
    }

    @Test fun a_long_duration_shows_where_it_lands() {
        show()
        scrollToWheel(240)
        compose.onNodeWithText("4h").assertExists()
        compose.onNodeWithText("(6:20 PM)").assertExists()
    }

    @Test fun a_duration_landing_on_another_day_names_the_day() {
        show()
        scrollToWheel(30 * 60)
        compose.onNodeWithText("30h").assertExists()
        compose.onNodeWithText("(Tomorrow 8:20 PM)").assertExists()
    }

    // ---- committing ---------------------------------------------------------

    @Test fun each_preset_commits_the_moment_it_is_tapped() {
        show()
        listOf(15, 30, 60, 180).forEach { minutes ->
            snoozed = null
            tapPreset(minutes)
            assertEquals("tapping the $minutes chip must snooze by $minutes", minutes, snoozed)
            assertFalse("snoozing must not also finish the task", done)
        }
    }

    @Test fun a_wheel_row_commits_the_moment_it_is_tapped() {
        show()
        tapWheel(45)
        assertEquals(45, snoozed)

        snoozed = null
        tapWheel(105)
        assertEquals(105, snoozed)
    }

    @Test fun the_wheels_first_rung_is_the_five_minute_snooze() {
        show()
        tapWheel(5)
        assertEquals(5, snoozed)
    }

    @Test fun done_finishes_the_task_and_snoozes_nothing() {
        show()
        act(compose.onNodeWithTag("done-button"))
        assertTrue(done)
        assertNull("finishing must not also push it out", snoozed)
    }

    // ---- backing out --------------------------------------------------------

    @Test fun closing_changes_nothing() {
        show()
        act(compose.onNodeWithContentDescription("Close"))
        assertTrue(dismissed)
        assertNull("backing out must leave the reminder alone", snoozed)
        assertFalse("backing out must leave the reminder alone", done)
    }

    @Test fun tapping_the_scrim_changes_nothing() {
        show()
        act(compose.onNodeWithTag("sheet-scrim"))
        assertTrue(dismissed)
        assertNull("backing out must leave the reminder alone", snoozed)
        assertFalse("backing out must leave the reminder alone", done)
    }
}
