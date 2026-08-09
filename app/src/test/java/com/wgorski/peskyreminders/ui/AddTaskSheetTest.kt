package com.wgorski.peskyreminders.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wgorski.peskyreminders.Repeat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Drives every control in the add sheet against a frozen clock, on the JVM.
 *
 * The sheet takes `nowMillis` as a parameter rather than reading the clock, so
 * every expected label below is a fixed string — no tolerance windows, no
 * "runs differently after midnight".
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class AddTaskSheetTest {

    @get:Rule val compose = createComposeRule()

    /**
     * Pin the zone so every label below is a fixed string, and the locale
     * because the calendar grid asks it which day a week starts on.
     */
    @Before fun fixTimeZoneAndLocale() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
    }

    /** One test below sets the locale to UK; restore it so later classes in the same JVM don't inherit it. */
    @After fun restoreLocale() {
        Locale.setDefault(Locale.US)
    }

    /** Saturday 25 July 2026, 14:20 UTC. */
    private val now: Long
        get() = Calendar.getInstance().apply {
            set(2026, Calendar.JULY, 25, 14, 20, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private var savedName: String? = null
    private var savedDue: Long? = null
    private var savedRepeat: Repeat? = null
    private var dismissed = false

    private fun showSheet() {
        compose.setContent {
            AddTaskSheet(
                nowMillis = now,
                use24h = false,
                onDismiss = { dismissed = true },
                onSave = { name, due, repeat ->
                    savedName = name; savedDue = due; savedRepeat = repeat
                },
            )
        }
    }

    private fun dueLabel() = compose.onNodeWithTag("due-label")

    private fun typeName(text: String = "Feed the sourdough") =
        compose.onNodeWithTag("name-field").performTextInput(text)

    /**
     * Assert the control is really on screen, then fire its click action.
     *
     * Compose's synthetic pointer injection does not reach into this sheet's
     * scrolling body under Robolectric — `performClick()` lands on nothing,
     * while the identical taps work on a device (every control here was driven
     * by hand on the emulator, see docs/verification). Rather than skip the
     * coverage, we check the node is displayed (so a control that got clipped,
     * detached or covered still fails) and then invoke its registered onClick.
     *
     * What this does NOT cover: hit-test geometry — a control that renders in
     * the wrong place but is still "displayed" would pass here. The emulator
     * pass and `ReminderModelTest` are what cover real touch dispatch.
     */
    private fun act(node: SemanticsNodeInteraction) {
        // Not everything sits in a scroll container (the scrim, the footer).
        runCatching { node.performScrollTo() }
        node.assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private fun tap(text: String) = act(compose.onNodeWithText(text))

    private fun tapTag(tag: String) = act(compose.onNodeWithTag(tag))

    private fun tapCd(description: String) = act(compose.onNodeWithContentDescription(description))

    private fun tapWheel(wheel: String, index: Int) {
        compose.onNodeWithTag("wheel-$wheel").performScrollToNode(hasTestTag("$wheel-$index"))
        tapTag("$wheel-$index")
    }

    /**
     * A time other than the preselected one, for the tests that only care about
     * what happens after one is chosen. The wheels work from the current selection
     * (15:00), so picking hour 20 lands on 8pm tonight.
     */
    private fun pickATime() = tapWheel("HOUR", 20)

    // ---- the name field -----------------------------------------------------

    /**
     * A new pester opens ready to type. The name is the one thing the sheet
     * cannot default, so the keyboard is wanted every time — unlike an edit,
     * which is pinned to the opposite in [EditTaskSheetTest].
     */
    @Test fun the_name_field_takes_focus_on_open() {
        showSheet()
        compose.onNodeWithTag("name-field").assertIsFocused()
    }

    /**
     * Tapping anything else puts the keyboard away. Every tappable thing in the
     * app is a `pressable` or a `tap`, and both clear focus, so a repeat chip
     * stands in for all of them here.
     */
    @Test fun tapping_elsewhere_releases_the_keyboard() {
        showSheet()
        typeName()
        compose.onNodeWithTag("name-field").assertIsFocused()

        tapTag("repeat-Daily")

        compose.onNodeWithTag("name-field").assertIsNotFocused()
    }

    /**
     * The dead space *inside* the scrolling body — a field label, the gap under
     * the text box — must release it too. That space belongs to no control, so it
     * relies on the body's own swallow layer; the sheet-wide one cannot see it,
     * because `verticalScroll` is a pointer-input node and shadows it. Tapping
     * there did nothing until the body got a swallow of its own.
     */
    @Test fun tapping_the_body_dead_space_releases_the_keyboard() {
        showSheet()
        typeName()
        compose.onNodeWithTag("name-field").assertIsFocused()

        compose.onNodeWithTag("sheet-body-swallow")
            .performSemanticsAction(SemanticsActions.OnClick)

        compose.onNodeWithTag("name-field").assertIsNotFocused()
    }

    /** …and does not cost the user what they had already typed. */
    @Test fun releasing_the_keyboard_keeps_the_typed_name() {
        showSheet()
        typeName("Feed the sourdough")
        tapTag("repeat-Daily")
        tapTag("save-button")
        assertEquals("Feed the sourdough", savedName)
    }

    // ---- the preselected time -----------------------------------------------

    /** 14:20 + about an hour, on the hour. Nothing to pick before you can save. */
    @Test fun the_sheet_opens_on_a_time_already_chosen() {
        showSheet()
        dueLabel().assertTextEquals("Today, 3:00 PM")
    }

    @Test fun saving_without_touching_the_time_takes_the_default() {
        showSheet()
        typeName()
        tapTag("save-button")
        val expected = Calendar.getInstance().apply {
            set(2026, Calendar.JULY, 25, 15, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertEquals(expected, savedDue)
    }

    // ---- gating -------------------------------------------------------------

    @Test fun save_is_inert_until_a_name_is_given() {
        showSheet()
        compose.onNodeWithTag("save-button").assertHasNoClickAction()

        typeName()
        compose.onNodeWithTag("save-button").assertHasClickAction()

        pickATime()
        dueLabel().assertTextEquals("Today, 8:00 PM")
        tapTag("save-button")
        assertEquals("Feed the sourdough", savedName)
    }

    /** Adding says "Pester me"; only the edit sheet says "Save changes". */
    @Test fun the_button_asks_to_be_pestered() {
        showSheet()
        compose.onNodeWithText("Pester me").assertExists()
        compose.onNodeWithText("Save changes").assertDoesNotExist()
    }

    // ---- mode tabs ----------------------------------------------------------

    @Test fun mode_tabs_swap_the_picker() {
        showSheet()
        compose.onNodeWithText("DAY").assertExists()

        tap("Calendar")
        compose.onNodeWithText("July 2026").assertExists()
        compose.onNodeWithText("DAY").assertDoesNotExist()

        tap("Quick pick")
        compose.onNodeWithText("DAY").assertExists()
        compose.onNodeWithText("July 2026").assertDoesNotExist()
    }

    /**
     * The visible half of the week-start rule. TaskTime having the right
     * answer buys nothing if the grid still draws its old hardcoded literal,
     * and no other test looks at the header row at all.
     */
    @Test fun the_calendar_header_starts_on_the_locales_first_day() {
        Locale.setDefault(Locale.UK)
        showSheet()
        tap("Calendar")

        compose.onNodeWithTag("dow-0").assertTextEquals("M")
        compose.onNodeWithTag("dow-1").assertTextEquals("T")
        compose.onNodeWithTag("dow-2").assertTextEquals("W")
        compose.onNodeWithTag("dow-3").assertTextEquals("T")
        compose.onNodeWithTag("dow-4").assertTextEquals("F")
        compose.onNodeWithTag("dow-5").assertTextEquals("S")
        compose.onNodeWithTag("dow-6").assertTextEquals("S")
    }

    // ---- wheels -------------------------------------------------------------

    @Test fun each_wheel_column_sets_its_own_field() {
        showSheet()
        // Each column moves only its own field, working from the 15:00 default.
        tapWheel("DAY", 3)
        dueLabel().assertTextEquals("Tue, 3:00 PM")

        tapWheel("HOUR", 21)
        dueLabel().assertTextEquals("Tue, 9:00 PM")

        tapWheel("MIN", 2)
        dueLabel().assertTextEquals("Tue, 9:30 PM")
    }

    // ---- calendar -----------------------------------------------------------

    @Test fun calendar_day_cells_set_the_date_and_keep_the_time() {
        showSheet()
        tap("Calendar")
        tapTag("day-30")
        dueLabel().assertTextEquals("Thu, 3:00 PM")
    }

    @Test fun past_days_are_shown_but_not_selectable() {
        showSheet()
        tap("Calendar")
        // Today is the 25th: earlier days render, but carry no click action at all.
        compose.onNodeWithTag("day-20").performScrollTo().assertIsDisplayed().assertHasNoClickAction()
        compose.onNodeWithTag("day-24").performScrollTo().assertIsDisplayed().assertHasNoClickAction()
        compose.onNodeWithTag("day-25").performScrollTo().assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithTag("day-26").performScrollTo().assertIsDisplayed().assertHasClickAction()
        dueLabel().assertTextEquals("Today, 3:00 PM")
    }

    @Test fun month_arrows_page_the_grid_both_ways() {
        showSheet()
        tap("Calendar")
        compose.onNodeWithText("July 2026").assertExists()

        tapCd("Next month")
        compose.onNodeWithText("August 2026").assertExists()

        tapCd("Previous month")
        tapCd("Previous month")
        compose.onNodeWithText("June 2026").assertExists()
    }

    @Test fun hour_steppers_move_the_time_by_an_hour() {
        showSheet()
        tap("Calendar")
        tap("−1 hr")
        dueLabel().assertTextEquals("Today, 2:00 PM")
        tap("+1 hr")
        tap("+1 hr")
        dueLabel().assertTextEquals("Today, 4:00 PM")
    }

    @Test fun time_of_day_chips_snap_to_their_hour() {
        val expected = listOf(
            "Morning 9:00" to "Today, 9:00 AM",
            "Noon" to "Today, 12:00 PM",
            "Evening 7:00" to "Today, 7:00 PM",
            "Night 9:00" to "Today, 9:00 PM",
        )
        showSheet()
        tap("Calendar")
        expected.forEach { (chip, label) ->
            tap(chip)
            dueLabel().assertTextEquals(label)
        }
    }

    @Test fun the_fifteen_minute_chip_nudges_the_time() {
        showSheet()
        tap("Calendar")
        tap("Morning 9:00")
        tap("+15 min")
        dueLabel().assertTextEquals("Today, 9:15 AM")
        tap("+15 min")
        dueLabel().assertTextEquals("Today, 9:30 AM")
    }

    // ---- repeat + save ------------------------------------------------------

    /** Picks [pill] and saves, so the assertion covers the whole pill → task path. */
    private fun saveWithRepeat(pill: String): Repeat? {
        showSheet()
        typeName()
        pickATime()
        tap(pill)
        tapTag("save-button")
        return savedRepeat
    }

    @Test fun repeat_defaults_to_once() {
        showSheet()
        typeName()
        pickATime()
        tapTag("save-button")
        assertEquals(Repeat.ONCE, savedRepeat)
    }

    @Test fun the_daily_pill_saves_a_daily_task() {
        assertEquals(Repeat.DAILY, saveWithRepeat("Daily"))
    }

    @Test fun the_weekly_pill_saves_a_weekly_task() {
        assertEquals(Repeat.WEEKLY, saveWithRepeat("Weekly"))
    }

    @Test fun the_monthly_pill_saves_a_monthly_task() {
        assertEquals(Repeat.MONTHLY, saveWithRepeat("Monthly"))
    }

    @Test fun a_repeat_pill_can_be_switched_back_to_once() {
        showSheet()
        typeName()
        pickATime()
        tap("Monthly")
        tap("Once")
        tapTag("save-button")
        assertEquals(Repeat.ONCE, savedRepeat)
    }

    /**
     * All four rules stay reachable on one line. "Monthly" used to wrap onto a
     * second row, which grew the footer by a step.
     */
    @Test fun every_repeat_rule_sits_in_the_row() {
        showSheet()
        Repeat.entries.forEach { option ->
            compose.onNodeWithTag("repeat-${option.label}").assertExists()
        }
    }

    @Test fun saving_reports_the_name_the_time_and_the_repeat() {
        showSheet()
        typeName("Water the monstera")
        tapWheel("DAY", 1)
        tapWheel("HOUR", 9)
        tap("Weekly")
        tapTag("save-button")

        assertEquals("Water the monstera", savedName)
        assertEquals(Repeat.WEEKLY, savedRepeat)
        val expected = Calendar.getInstance().apply {
            set(2026, Calendar.JULY, 26, 9, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertEquals(expected, savedDue)
    }

    @Test fun the_name_is_trimmed_before_saving() {
        showSheet()
        typeName("   Pay the water bill   ")
        pickATime()
        tapTag("save-button")
        assertEquals("Pay the water bill", savedName)
    }

    // ---- dismissal ----------------------------------------------------------

    @Test fun the_close_button_dismisses() {
        showSheet()
        tapCd("Close")
        assertEquals(true, dismissed)
    }

    @Test fun tapping_the_scrim_dismisses() {
        showSheet()
        tapTag("sheet-scrim")
        assertEquals(true, dismissed)
    }
}
