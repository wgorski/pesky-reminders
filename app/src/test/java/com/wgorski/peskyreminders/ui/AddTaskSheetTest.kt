package com.wgorski.peskyreminders.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wgorski.peskyreminders.Repeat
import com.wgorski.peskyreminders.RepeatUnit
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

    private fun showSheet(use24h: Boolean = false) {
        compose.setContent {
            AddTaskSheet(
                nowMillis = now,
                use24h = use24h,
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

        tapWheel("MIN", 6)
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
        tap("−1h")
        dueLabel().assertTextEquals("Today, 2:00 PM")
        tap("+1h")
        tap("+1h")
        dueLabel().assertTextEquals("Today, 4:00 PM")
    }

    @Test fun time_of_day_chips_snap_to_their_hour() {
        val expected = listOf(
            9 to "Today, 9:00 AM",
            12 to "Today, 12:00 PM",
            19 to "Today, 7:00 PM",
            21 to "Today, 9:00 PM",
        )
        showSheet()
        tap("Calendar")
        expected.forEach { (hour, label) ->
            tapTag("hour-chip-$hour")
            dueLabel().assertTextEquals(label)
        }
    }

    /**
     * The chips used to write their hour out by hand — "Evening 7:00" — so on a
     * 24-hour device they named 19:00 in a format nothing else in the app uses.
     * The label now comes from the very millis the tap commits, so it cannot
     * disagree with either the readout above it or the row it will produce.
     */
    @Test fun time_of_day_chips_are_written_in_the_clock_format() {
        showSheet(use24h = true)
        tap("Calendar")
        listOf(9 to "09:00", 12 to "12:00", 19 to "19:00", 21 to "21:00")
            .forEach { (hour, text) ->
                compose.onNodeWithTag("hour-chip-$hour").assertTextEquals(text)
            }
    }

    /**
     * The quarter-hour steps sit in the stepper row rather than among the
     * chips, because they are relative nudges like ±1 hr and not absolute
     * landings like the chips. Both directions are pinned: the minus one was
     * added second and a sign slip there would be invisible on the plus side.
     */
    @Test fun the_quarter_hour_steppers_nudge_the_time_both_ways() {
        showSheet()
        tap("Calendar")
        tapTag("hour-chip-9")
        tap("+15m")
        dueLabel().assertTextEquals("Today, 9:15 AM")
        tap("+15m")
        dueLabel().assertTextEquals("Today, 9:30 AM")
        tap("−15m")
        dueLabel().assertTextEquals("Today, 9:15 AM")
        // Twice more, so it crosses the hour it started on rather than only
        // undoing the two steps above.
        tap("−15m")
        tap("−15m")
        dueLabel().assertTextEquals("Today, 8:45 AM")
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
     * All four presets and Custom stay reachable on one line. "Monthly" used to
     * wrap onto a second row, which grew the footer by a step.
     */
    @Test fun every_repeat_rule_sits_in_the_row() {
        showSheet()
        (Repeat.PRESETS.map { it.label } + "Custom").forEach { label ->
            compose.onNodeWithTag("repeat-$label").assertExists()
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

    // ---- the minute wheel ---------------------------------------------------

    @Test fun the_minute_wheel_steps_every_five_minutes() {
        showSheet()
        compose.onNodeWithTag("wheel-MIN").performScrollToNode(hasTestTag("MIN-11"))
        compose.onNodeWithTag("MIN-11").assertTextEquals(":55")
        tapWheel("MIN", 7)
        dueLabel().assertTextEquals("Today, 3:35 PM")
    }

    // ---- custom repeat ------------------------------------------------------
    //
    // The sheet opens on Saturday 25 July, 3:00 PM.

    private fun openCustom() = tapTag("repeat-Custom")

    private fun readout() = compose.onNodeWithTag("custom-readout")

    @Test fun custom_opens_on_every_week_on_the_tasks_own_weekday() {
        showSheet()
        openCustom()
        readout().assertTextEquals("Weekly on Sat")
        compose.onNodeWithTag("custom-weekday-${Calendar.SATURDAY}").assertIsSelected()
        compose.onNodeWithTag("custom-weekday-${Calendar.MONDAY}").assertIsNotSelected()
    }

    @Test fun a_custom_weekly_rule_moves_the_date_onto_it_and_is_spelt_out() {
        showSheet()
        typeName()
        openCustom()
        tapTag("custom-weekday-${Calendar.MONDAY}")
        tapTag("custom-weekday-${Calendar.SATURDAY}")
        tapTag("custom-every-plus")
        readout().assertTextEquals("Every 2 weeks on Mon")
        tapTag("custom-done")

        dueLabel().assertTextEquals("Mon, 3:00 PM")
        compose.onNodeWithTag("repeat-summary").assertTextEquals("Every 2 weeks on Mon")
        compose.onNodeWithTag("custom-readout").assertDoesNotExist()

        tapTag("save-button")
        assertEquals(Repeat(RepeatUnit.WEEK, 2, weekdays = setOf(Calendar.MONDAY)), savedRepeat)
        assertEquals(
            Calendar.getInstance().apply {
                set(2026, Calendar.JULY, 27, 15, 0, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis,
            savedDue,
        )
    }

    @Test fun the_last_ticked_day_stays_ticked() {
        showSheet()
        openCustom()
        tapTag("custom-weekday-${Calendar.SATURDAY}")
        compose.onNodeWithTag("custom-weekday-${Calendar.SATURDAY}").assertIsSelected()
        readout().assertTextEquals("Weekly on Sat")
    }

    @Test fun the_count_never_drops_below_one_and_the_units_pluralise() {
        showSheet()
        openCustom()
        tapTag("custom-every-minus")
        compose.onNodeWithTag("custom-every").assertTextEquals("1")
        compose.onNodeWithTag("custom-unit-DAY").assertTextEquals("day")
        tapTag("custom-every-plus")
        compose.onNodeWithTag("custom-unit-DAY").assertTextEquals("days")
    }

    @Test fun days_and_years_ask_for_no_days() {
        showSheet()
        openCustom()
        tapTag("custom-unit-DAY")
        compose.onNodeWithTag("custom-weekday-${Calendar.MONDAY}").assertDoesNotExist()
        tapTag("custom-unit-YEAR")
        compose.onNodeWithTag("custom-weekday-${Calendar.MONDAY}").assertDoesNotExist()
        readout().assertTextEquals("Yearly")
    }

    @Test fun a_month_can_repeat_on_any_date_not_just_the_tasks() {
        showSheet()
        typeName()
        openCustom()
        tapTag("custom-unit-MONTH")
        compose.onNodeWithTag("custom-monthday-25").assertIsSelected()
        tapTag("custom-monthday-15")
        tapTag("custom-every-plus")
        readout().assertTextEquals("Every 2 months on the 15th")
        tapTag("custom-done")

        dueLabel().assertTextEquals("Sat 15 Aug, 3:00 PM")
        compose.onNodeWithTag("repeat-summary").assertTextEquals("Every 2 months on the 15th")
        tapTag("save-button")
        assertEquals(Repeat(RepeatUnit.MONTH, 2, monthDay = 15), savedRepeat)
    }

    /**
     * Once the date has moved to the 15th, "monthly on the 15th" is exactly what
     * the Monthly preset means there — so that is the chip that lights.
     */
    @Test fun monthly_on_the_date_it_lands_on_is_just_monthly() {
        showSheet()
        typeName()
        openCustom()
        tapTag("custom-unit-MONTH")
        tapTag("custom-monthday-15")
        tapTag("custom-done")
        dueLabel().assertTextEquals("Sat 15 Aug, 3:00 PM")
        compose.onNodeWithTag("repeat-summary").assertDoesNotExist()
        tapTag("save-button")
        assertEquals(Repeat.MONTHLY, savedRepeat)
    }

    @Test fun a_month_can_repeat_on_any_weekday_not_just_the_tasks() {
        showSheet()
        openCustom()
        tapTag("custom-unit-MONTH")
        tapTag("custom-month-by-weekday")
        // 25 July is the 4th Saturday of its month.
        readout().assertTextEquals("Monthly on the 4th Saturday")
        tapTag("custom-ordinal-2")
        tapTag("custom-weekday-${Calendar.TUESDAY}")
        readout().assertTextEquals("Monthly on the 2nd Tuesday")
        tapTag("custom-done")

        dueLabel().assertTextEquals("Tue 11 Aug, 3:00 PM")
        compose.onNodeWithTag("repeat-summary").assertTextEquals("Monthly on the 2nd Tuesday")
    }

    @Test fun switching_units_keeps_what_each_one_had() {
        showSheet()
        openCustom()
        tapTag("custom-weekday-${Calendar.MONDAY}")
        tapTag("custom-unit-MONTH")
        tapTag("custom-unit-WEEK")
        readout().assertTextEquals("Weekly on Mon, Sat")
    }

    @Test fun a_custom_rule_a_preset_can_say_lights_the_preset() {
        showSheet()
        typeName()
        openCustom()
        tapTag("custom-done")
        compose.onNodeWithTag("repeat-summary").assertDoesNotExist()
        tapTag("save-button")
        assertEquals(Repeat.WEEKLY, savedRepeat)
    }

    @Test fun dismissing_the_editor_keeps_the_rule_that_was_there() {
        showSheet()
        typeName()
        openCustom()
        tapTag("custom-weekday-${Calendar.MONDAY}")
        act(compose.onAllNodesWithContentDescription("Close").onLast())
        compose.onNodeWithTag("custom-readout").assertDoesNotExist()
        compose.onNodeWithTag("repeat-summary").assertDoesNotExist()
        dueLabel().assertTextEquals("Today, 3:00 PM")
        assertEquals(false, dismissed)
        tapTag("save-button")
        assertEquals(Repeat.ONCE, savedRepeat)
    }

    @Test fun picking_a_date_the_rule_does_not_name_moves_on_to_one_it_does() {
        showSheet()
        openCustom()
        tapTag("custom-weekday-${Calendar.MONDAY}")
        tapTag("custom-weekday-${Calendar.WEDNESDAY}")
        tapTag("custom-weekday-${Calendar.SATURDAY}")
        tapTag("custom-done")
        dueLabel().assertTextEquals("Mon, 3:00 PM")
        // Thursday 30 July is neither, so it lands on the Monday after.
        tapWheel("DAY", 5)
        dueLabel().assertTextEquals("Mon 3 Aug, 3:00 PM")
    }

    @Test fun a_preset_follows_the_date_instead() {
        showSheet()
        tap("Weekly")
        tapWheel("DAY", 3)
        dueLabel().assertTextEquals("Tue, 3:00 PM")
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
