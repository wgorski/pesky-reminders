package com.wgorski.peskyreminders.ui

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Drives the settings sheet on the JVM.
 *
 * Same caveat as [AddTaskSheetTest]: Compose's pointer injection does not reach
 * into a sheet body under Robolectric, so controls are asserted displayed and then
 * their click action is fired directly. The slider is driven through its
 * `SetProgress` action for the same reason — see [PeskySliderTest], which pins the
 * arithmetic a real drag would go through.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class SettingsSheetTest {

    @get:Rule val compose = createComposeRule()

    // Real state, so a toggle or a chip actually re-renders the sheet.
    private val nagEnabled = mutableStateOf(true)
    private val nagMinutes = mutableIntStateOf(5)
    private val swipeMinutes = mutableIntStateOf(5)
    private var dismissed = false

    private fun show(enabled: Boolean = true, minutes: Int = 5, swipe: Int = 5) {
        nagEnabled.value = enabled
        nagMinutes.intValue = minutes
        swipeMinutes.intValue = swipe
        compose.setContent {
            SettingsSheet(
                nagEnabled = nagEnabled.value,
                nagMinutes = nagMinutes.intValue,
                swipeSnoozeMinutes = swipeMinutes.intValue,
                onNagEnabled = { nagEnabled.value = it },
                onNagMinutes = { nagMinutes.intValue = it },
                onSwipeSnoozeMinutes = { swipeMinutes.intValue = it },
                onDismiss = { dismissed = true },
            )
        }
    }

    private fun tap(tag: String) {
        compose.onNodeWithTag(tag)
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    /** Moves a slider the way a screen reader would — no pointer involved. */
    private fun slide(tag: String, to: Int) {
        compose.onNodeWithTag(tag)
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(to.toFloat()) }
        compose.waitForIdle()
    }

    private fun switchState(): ToggleableState =
        compose.onNodeWithTag("nag-switch")
            .fetchSemanticsNode()
            .config[SemanticsProperties.ToggleableState]

    private fun countOf(text: String): Int =
        compose.onAllNodesWithText(text).fetchSemanticsNodes().size

    /** Zero means the control is absent, not merely hidden or greyed out. */
    private fun countTagged(tag: String): Int =
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    // ---- rendering ----------------------------------------------------------

    @Test fun the_sheet_shows_both_settings_under_one_heading() {
        show()
        compose.onNodeWithText("WHEN A REMINDER IS DUE").assertIsDisplayed()
        compose.onNodeWithText("Keep buzzing").assertIsDisplayed()
        compose.onNodeWithText("Swipe to snooze").assertIsDisplayed()
        assertEquals(ToggleableState.On, switchState())
    }

    @Test fun each_card_names_what_it_changes() {
        show()
        compose.onNodeWithText("Buzz every").assertIsDisplayed()
        compose.onNodeWithText("Snooze for").assertIsDisplayed()
    }

    /** Both cards offer the same three, which is why nothing here goes by text. */
    @Test fun both_cards_offer_the_same_three_durations() {
        show()
        assertEquals(2, countOf("5 min"))
        assertEquals(2, countOf("15 min"))
        assertEquals(2, countOf("30 min"))
        assertEquals(2, countOf("Custom"))
    }

    @Test fun a_chip_is_labelled_with_the_duration_it_commits() {
        show()
        compose.onNodeWithTag("nag-preset-5").assertTextEquals("5 min")
        compose.onNodeWithTag("nag-preset-15").assertTextEquals("15 min")
        compose.onNodeWithTag("nag-preset-30").assertTextEquals("30 min")
        compose.onNodeWithTag("nag-custom").assertTextEquals("Custom")
    }

    // ---- the summary at the foot ---------------------------------------------

    @Test fun the_summary_states_what_the_two_settings_add_up_to() {
        show(enabled = true, minutes = 5, swipe = 5)
        compose.onNodeWithTag("settings-summary").assertTextEquals(
            "Pesky buzzes every 5 min until you snooze it or tick it off. " +
                "A swipe pushes it back 5 min."
        )
    }

    @Test fun the_summary_follows_a_change_rather_than_going_stale() {
        show(minutes = 5, swipe = 5)
        tap("swipe-preset-30")
        compose.onNodeWithTag("settings-summary").assertTextEquals(
            "Pesky buzzes every 5 min until you snooze it or tick it off. " +
                "A swipe pushes it back 30 min."
        )
    }

    @Test fun the_summary_reports_the_quiet_case_too() {
        show(enabled = false, swipe = 15)
        compose.onNodeWithTag("settings-summary")
            .assertTextEquals("Pesky buzzes once and waits. A swipe pushes it back 15 min.")
    }

    // ---- the switch ---------------------------------------------------------

    @Test fun the_switch_turns_nagging_off_and_back_on() {
        show(enabled = true)
        tap("nag-switch")
        assertEquals(ToggleableState.Off, switchState())
        assertEquals(false, nagEnabled.value)

        tap("nag-switch")
        assertEquals(ToggleableState.On, switchState())
        assertEquals(true, nagEnabled.value)
    }

    @Test fun the_switch_announces_itself_as_a_switch() {
        show()
        compose.onNodeWithTag("nag-switch").assert(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)
        )
    }

    /**
     * Gone, not greyed. A disabled row costs its full height to say nothing, and
     * the switch immediately above has already said why it is absent.
     */
    @Test fun turning_nagging_off_takes_the_interval_away_entirely() {
        show(enabled = true)
        compose.onNodeWithText("Buzz every").assertIsDisplayed()

        tap("nag-switch")
        assertEquals(0, countOf("Buzz every"))
        assertEquals(0, countTagged("nag-preset-5"))
    }

    @Test fun turning_nagging_back_on_brings_the_interval_back() {
        show(enabled = false)
        assertEquals(0, countOf("Buzz every"))

        tap("nag-switch")
        compose.onNodeWithText("Buzz every").assertIsDisplayed()
        compose.onNodeWithTag("nag-preset-5").assertIsSelected()
    }

    /** The swipe is unconditional, so nothing about the switch reaches it. */
    @Test fun turning_nagging_off_leaves_the_swipe_snooze_alone() {
        show(enabled = false, swipe = 5)
        compose.onNodeWithText("Snooze for").assertIsDisplayed()
        tap("swipe-preset-30")
        assertEquals(30, swipeMinutes.intValue)
    }

    // ---- the chips ----------------------------------------------------------

    @Test fun the_stored_interval_is_the_chip_that_reads_as_chosen() {
        show(minutes = 15)
        compose.onNodeWithTag("nag-preset-15").assertIsSelected()
        compose.onNodeWithTag("nag-preset-5").assertIsNotSelected()
        compose.onNodeWithTag("nag-preset-30").assertIsNotSelected()
        compose.onNodeWithTag("nag-custom").assertIsNotSelected()
    }

    @Test fun tapping_a_chip_commits_it_at_once() {
        show(minutes = 5)
        tap("nag-preset-30")
        assertEquals(30, nagMinutes.intValue)
        compose.onNodeWithTag("nag-preset-30").assertIsSelected()
        compose.onNodeWithTag("nag-preset-5").assertIsNotSelected()
    }

    @Test fun the_swipe_chips_commit_their_own_setting() {
        show(swipe = 5)
        compose.onNodeWithTag("swipe-preset-5").assertIsSelected()
        tap("swipe-preset-15")
        assertEquals(15, swipeMinutes.intValue)
        compose.onNodeWithTag("swipe-preset-15").assertIsSelected()
    }

    /**
     * `MinutesPicker` is shared between the two cards, so this is exactly where a
     * hoisted-state bug would live: one draft, or one open-custom flag, serving
     * both.
     */
    @Test fun choosing_a_swipe_snooze_leaves_the_nag_interval_alone() {
        show(minutes = 15, swipe = 5)
        tap("swipe-preset-30")
        assertEquals(30, swipeMinutes.intValue)
        assertEquals(15, nagMinutes.intValue)
        compose.onNodeWithTag("nag-preset-15").assertIsSelected()
    }

    @Test fun choosing_a_nag_interval_leaves_the_swipe_snooze_alone() {
        show(minutes = 5, swipe = 15)
        tap("nag-preset-30")
        assertEquals(30, nagMinutes.intValue)
        assertEquals(15, swipeMinutes.intValue)
        compose.onNodeWithTag("swipe-preset-15").assertIsSelected()
    }

    // ---- the custom slider --------------------------------------------------

    @Test fun the_slider_is_out_of_the_way_until_custom_is_asked_for() {
        show(minutes = 5)
        assertEquals(0, countTagged("nag-slider"))
        tap("nag-custom")
        compose.onNodeWithTag("nag-slider").assertIsDisplayed()
        compose.onNodeWithTag("nag-readout").assertTextEquals("5 min")
    }

    /** Opening the slider changes nothing on its own — it only offers the range. */
    @Test fun asking_for_custom_commits_nothing() {
        show(minutes = 5)
        tap("nag-custom")
        assertEquals(5, nagMinutes.intValue)
        compose.onNodeWithTag("nag-custom").assertIsSelected()
        compose.onNodeWithTag("nag-preset-5").assertIsNotSelected()
    }

    @Test fun the_track_is_marked_with_the_range_it_covers() {
        show(minutes = 5)
        tap("nag-custom")
        compose.onNodeWithTag("nag-floor").assertTextEquals("1 min")
        compose.onNodeWithTag("nag-ceiling").assertTextEquals("180 min")
    }

    @Test fun the_slider_commits_where_it_is_let_go() {
        show(minutes = 5)
        tap("nag-custom")
        slide("nag-slider", 47)
        assertEquals(47, nagMinutes.intValue)
        compose.onNodeWithTag("nag-readout").assertTextEquals("47 min")
    }

    @Test fun the_slider_cannot_leave_its_own_range() {
        show(minutes = 5)
        tap("nag-custom")
        slide("nag-slider", 9_999)
        assertEquals(180, nagMinutes.intValue)
        slide("nag-slider", -40)
        assertEquals(1, nagMinutes.intValue)
        compose.onNodeWithTag("nag-readout").assertTextEquals("1 min")
    }

    /**
     * A value that is not one of the chips has to open on the slider, or the sheet
     * would show a stored 47 with nothing selected and no way to see it.
     */
    @Test fun a_stored_value_off_the_chips_opens_on_the_slider() {
        show(minutes = 47, swipe = 5)
        compose.onNodeWithTag("nag-custom").assertIsSelected()
        compose.onNodeWithTag("nag-readout").assertTextEquals("47 min")
        // …and the other card, whose value *is* a chip, does not.
        compose.onNodeWithTag("swipe-preset-5").assertIsSelected()
        assertEquals(0, countTagged("swipe-slider"))
    }

    /**
     * The slider passes straight over 5, 15 and 30 on its way anywhere. Deriving
     * "is this custom?" from the value would snap the sheet shut mid-drag.
     */
    @Test fun the_slider_stays_open_when_it_lands_on_a_chip_value() {
        show(minutes = 47)
        tap("nag-custom")
        slide("nag-slider", 30)
        assertEquals(30, nagMinutes.intValue)
        compose.onNodeWithTag("nag-slider").assertIsDisplayed()
        compose.onNodeWithTag("nag-custom").assertIsSelected()
        compose.onNodeWithTag("nag-preset-30").assertIsNotSelected()
    }

    /** …and picking a chip is what closes it again. */
    @Test fun choosing_a_chip_puts_the_slider_away() {
        show(minutes = 47)
        compose.onNodeWithTag("nag-slider").assertIsDisplayed()
        tap("nag-preset-15")
        assertEquals(15, nagMinutes.intValue)
        assertEquals(0, countTagged("nag-slider"))
        compose.onNodeWithTag("nag-preset-15").assertIsSelected()
    }

    @Test fun the_swipe_card_has_a_slider_of_its_own() {
        show(minutes = 5, swipe = 5)
        tap("swipe-custom")
        slide("swipe-slider", 90)
        assertEquals(90, swipeMinutes.intValue)
        assertEquals("the other card is untouched", 5, nagMinutes.intValue)
        compose.onNodeWithTag("swipe-readout").assertTextEquals("90 min")
        assertEquals(0, countTagged("nag-slider"))
    }

    // ---- dismissal ----------------------------------------------------------

    @Test fun closing_dismisses() {
        show()
        compose.onNodeWithContentDescription("Close")
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(true, dismissed)
    }

    @Test fun tapping_the_scrim_dismisses() {
        show()
        compose.onNodeWithTag("sheet-scrim")
            .performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(true, dismissed)
    }

    /**
     * Nothing is held back, so closing has nothing to rescue. This pins the
     * simplification: the typed field this replaced had to be clamped on the way
     * out, and a chip or a slider cannot leave an out-of-range value behind.
     */
    @Test fun closing_changes_nothing_by_itself() {
        show(minutes = 12, swipe = 20)
        compose.onNodeWithContentDescription("Close")
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(12, nagMinutes.intValue)
        assertEquals(20, swipeMinutes.intValue)
    }
}
