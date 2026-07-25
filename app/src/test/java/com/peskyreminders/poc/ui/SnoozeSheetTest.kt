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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Calendar
import java.util.TimeZone

/**
 * Drives the snooze picker on the JVM against a frozen clock.
 *
 * Same caveat as the other sheet tests: Compose's pointer injection does not
 * reach into a sheet body under Robolectric, so controls are asserted displayed
 * and then their click action is fired directly.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class SnoozeSheetTest {

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
    private var dismissed = false

    private fun show() {
        compose.setContent {
            SnoozeSheet(
                taskName = "Water the monstera",
                nowMillis = now,
                use24h = false,
                onDismiss = { dismissed = true },
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

    private fun backAt() = compose.onNodeWithTag("back-at")

    // ---- rendering ----------------------------------------------------------

    @Test fun it_names_the_task_being_snoozed() {
        show()
        compose.onNodeWithTag("snooze-task").assertTextEquals("Water the monstera")
    }

    @Test fun it_opens_on_five_minutes() {
        show()
        backAt().assertTextEquals("Back at Today, 2:25 PM")
    }

    @Test fun every_preset_is_offered() {
        show()
        listOf(5, 15, 30, 60).forEach {
            compose.onNodeWithTag("preset-$it").assertIsDisplayed()
        }
    }

    @Test fun both_ways_in_are_labelled() {
        show()
        compose.onNodeWithText("Common").assertIsDisplayed()
        compose.onNodeWithText("…or dial it in").assertIsDisplayed()
    }

    // ---- choosing -----------------------------------------------------------

    @Test fun each_preset_moves_the_readout() {
        val expected = listOf(
            5 to "Back at Today, 2:25 PM",
            15 to "Back at Today, 2:35 PM",
            30 to "Back at Today, 2:50 PM",
            60 to "Back at Today, 3:20 PM",
        )
        show()
        expected.forEach { (minutes, label) ->
            tapPreset(minutes)
            backAt().assertTextEquals(label)
        }
    }

    @Test fun the_wheel_reaches_durations_the_presets_do_not() {
        show()
        tapWheel(45)
        backAt().assertTextEquals("Back at Today, 3:05 PM")

        tapWheel(90)
        backAt().assertTextEquals("Back at Today, 3:50 PM")
    }

    @Test fun the_wheel_goes_all_the_way_to_the_cap() {
        show()
        tapWheel(180)
        backAt().assertTextEquals("Back at Today, 5:20 PM")
    }

    @Test fun a_preset_and_the_wheel_stay_in_step() {
        show()
        tapPreset(60)
        backAt().assertTextEquals("Back at Today, 3:20 PM")
        // 60 is on the wheel too, so picking it there should not change anything.
        tapWheel(60)
        backAt().assertTextEquals("Back at Today, 3:20 PM")
    }

    // ---- committing ---------------------------------------------------------

    @Test fun snoozing_reports_the_chosen_duration() {
        show()
        tapPreset(30)
        act(compose.onNodeWithTag("snooze-button"))
        assertEquals(30, snoozed)
    }

    @Test fun snoozing_without_choosing_uses_the_default() {
        show()
        act(compose.onNodeWithTag("snooze-button"))
        assertEquals(SnoozeOptions.DEFAULT_MINUTES, snoozed)
    }

    @Test fun a_wheel_choice_is_what_gets_committed() {
        show()
        tapPreset(5)
        tapWheel(105)
        act(compose.onNodeWithTag("snooze-button"))
        assertEquals(105, snoozed)
    }

    // ---- backing out --------------------------------------------------------

    @Test fun closing_snoozes_nothing() {
        show()
        tapPreset(60)
        act(compose.onNodeWithContentDescription("Close"))
        assertEquals(true, dismissed)
        assertNull("backing out must leave the reminder alone", snoozed)
    }

    @Test fun tapping_the_scrim_snoozes_nothing() {
        show()
        act(compose.onNodeWithTag("sheet-scrim"))
        assertEquals(true, dismissed)
        assertNull("backing out must leave the reminder alone", snoozed)
    }
}
