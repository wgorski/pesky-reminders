package com.wgorski.peskyreminders.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wgorski.peskyreminders.Repeat
import com.wgorski.peskyreminders.Task
import com.wgorski.peskyreminders.ToggleOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Drives the list screen against a frozen clock, on the JVM. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class TaskListScreenTest {

    @get:Rule val compose = createComposeRule()

    /** The locale decides where a week starts, so the sections depend on it. */
    @Before fun fixTimeZoneAndLocale() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
    }

    /** Saturday 25 July 2026, 14:20 UTC. */
    private val now: Long
        get() = at(2026, Calendar.JULY, 25, 14, 20)

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance().apply {
            set(year, month, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private val toggled = mutableListOf<Int>()
    private val opened = mutableListOf<Int>()
    private val reminded = mutableListOf<Int>()
    private var addTapped = false
    private var sectionToggled = 0
    private var clearTapped = 0

    /**
     * What the screen's toggle callback reports back. A completion by default —
     * the common case — and set per-test where the outcome is the thing under
     * test. JUnit builds a fresh instance per test, so this resets on its own.
     */
    private var outcome = ToggleOutcome.COMPLETED

    private fun show(tasks: List<Task>, doneExpanded: Boolean = false) {
        compose.setContent {
            TaskListScreen(
                tasks = tasks,
                nowMillis = now,
                use24h = false,
                doneExpanded = doneExpanded,
                onToggleDoneSection = { sectionToggled++ },
                onToggleTask = { toggled += it; outcome },
                onAdd = { addTapped = true },
                onOpenTask = { opened += it },
                onRemindTask = { reminded += it },
                onClearDone = { clearTapped++ },
            )
        }
    }

    private val overdueTask =
        Task(1, "Call the vet", at(2026, Calendar.JULY, 25, 9), Repeat.ONCE)
    private val upNextTask =
        Task(2, "Feed the sourdough", at(2026, Calendar.JULY, 29, 9), Repeat.WEEKLY)
    private val doneTask =
        Task(3, "Book dentist", at(2026, Calendar.JULY, 24, 9), Repeat.ONCE, done = true)

    // ---- rendering ----------------------------------------------------------

    @Test fun the_header_carries_no_date_stamp() {
        show(emptyList())
        compose.onNodeWithText("SAT 25 JUL").assertDoesNotExist()
    }

    @Test fun the_header_is_the_wordmark_and_the_settings_button() {
        show(emptyList())
        compose.onNodeWithText("Pesky.").assertExists()
        compose.onNodeWithContentDescription("Settings").assertExists()
    }

    @Test fun an_empty_list_shows_the_empty_state() {
        show(emptyList())
        compose.onNodeWithText("Nothing to pester you about").assertExists()
        compose.onNodeWithText("OVERDUE").assertDoesNotExist()
        compose.onNodeWithText("TODAY").assertDoesNotExist()
        compose.onNodeWithText("NEXT WEEK").assertDoesNotExist()
    }

    @Test fun tasks_split_by_whether_they_are_late() {
        show(listOf(overdueTask, upNextTask))

        compose.onNodeWithText("OVERDUE").assertExists()
        compose.onNodeWithText("Call the vet").assertExists()
        compose.onNodeWithText("Was due Today, 9:00 AM").assertExists()

        // 29 July is the Wednesday after this Saturday, so it opens a new week.
        compose.onNodeWithText("NEXT WEEK").assertExists()
        compose.onNodeWithText("Feed the sourdough").assertExists()
        compose.onNodeWithText("Wed, 9:00 AM").assertExists()

        compose.onNodeWithText("Nothing to pester you about").assertDoesNotExist()
    }

    // ---- the date bands -----------------------------------------------------

    /** Monday 27 July 2026, 09:00 — mid-week, so every band can be populated. */
    private val monday = at(2026, Calendar.JULY, 27, 9)

    private fun showFromMonday(tasks: List<Task>) {
        compose.setContent {
            TaskListScreen(
                tasks = tasks,
                nowMillis = monday,
                use24h = false,
                doneExpanded = false,
                onToggleDoneSection = { sectionToggled++ },
                onToggleTask = { toggled += it; outcome },
                onAdd = { addTapped = true },
                onOpenTask = { opened += it },
                onRemindTask = { reminded += it },
                onClearDone = { clearTapped++ },
            )
        }
    }

    @Test fun each_band_gets_its_own_heading() {
        showFromMonday(
            listOf(
                Task(1, "Late", at(2026, Calendar.JULY, 27, 8), Repeat.ONCE),
                Task(2, "Tonight", at(2026, Calendar.JULY, 27, 20), Repeat.ONCE),
                Task(3, "Tomorrow", at(2026, Calendar.JULY, 28, 9), Repeat.ONCE),
                Task(4, "Thursday", at(2026, Calendar.JULY, 30, 9), Repeat.ONCE),
                Task(5, "Next week", at(2026, Calendar.AUGUST, 4, 9), Repeat.ONCE),
                Task(6, "August", at(2026, Calendar.AUGUST, 20, 9), Repeat.ONCE),
            )
        )
        listOf("OVERDUE", "TODAY", "TOMORROW", "THIS WEEK", "NEXT WEEK", "LATER")
            .forEach { compose.onNodeWithText(it).assertExists() }
    }

    /** A band with nothing in it must not leave a heading behind. */
    @Test fun empty_bands_are_not_drawn() {
        showFromMonday(listOf(Task(3, "Tomorrow", at(2026, Calendar.JULY, 28, 9), Repeat.ONCE)))
        compose.onNodeWithText("TOMORROW").assertExists()
        listOf("OVERDUE", "TODAY", "THIS WEEK", "NEXT WEEK", "LATER")
            .forEach { compose.onNodeWithText(it).assertDoesNotExist() }
    }

    @Test fun the_bands_are_laid_out_in_chronological_order() {
        showFromMonday(
            listOf(
                Task(6, "August", at(2026, Calendar.AUGUST, 20, 9), Repeat.ONCE),
                Task(1, "Late", at(2026, Calendar.JULY, 27, 8), Repeat.ONCE),
                Task(4, "Thursday", at(2026, Calendar.JULY, 30, 9), Repeat.ONCE),
            )
        )
        val tops = listOf("OVERDUE", "THIS WEEK", "LATER").map {
            compose.onNodeWithText(it).fetchSemanticsNode().positionInRoot.y
        }
        assertEquals("headings must run down the screen in order", tops.sorted(), tops)
    }

    /** Only the overdue band paints its rows red and prefixes "Was due". */
    @Test fun lateness_is_marked_only_in_the_overdue_band() {
        showFromMonday(
            listOf(
                Task(1, "Late", at(2026, Calendar.JULY, 27, 8), Repeat.ONCE),
                Task(2, "Tonight", at(2026, Calendar.JULY, 27, 20), Repeat.ONCE),
            )
        )
        compose.onNodeWithText("Was due Today, 8:00 AM").assertExists()
        compose.onNodeWithText("Today, 8:00 PM").assertExists()
    }

    @Test fun only_repeating_tasks_carry_a_repeat_pill() {
        show(listOf(overdueTask, upNextTask))
        compose.onNodeWithText("Weekly").assertExists()
        compose.onNodeWithText("Once").assertDoesNotExist()
    }

    @Test fun the_done_section_counts_but_hides_its_rows_until_expanded() {
        show(listOf(upNextTask, doneTask))
        compose.onNodeWithText("DONE (1)").assertExists()
        compose.onNodeWithText("Book dentist").assertDoesNotExist()
    }

    @Test fun an_expanded_done_section_lists_its_rows() {
        show(listOf(upNextTask, doneTask), doneExpanded = true)
        compose.onNodeWithText("Book dentist").assertExists()
    }

    // ---- interaction --------------------------------------------------------

    @Test fun the_done_header_toggles_the_section() {
        show(listOf(doneTask))
        compose.onNodeWithText("DONE (1)").performClick()
        assertEquals(1, sectionToggled)
    }

    @Test fun ticking_an_active_task_reports_its_id() {
        show(listOf(overdueTask, upNextTask))
        compose.onNodeWithTag("check-1").performClick()
        compose.waitUntil { toggled.size == 1 }
        compose.onNodeWithTag("check-2").performClick()
        compose.waitUntil { toggled.size == 2 }
        assertEquals(listOf(1, 2), toggled)
    }

    @Test fun un_ticking_a_done_task_reports_its_id_too() {
        show(listOf(doneTask), doneExpanded = true)
        compose.onNodeWithTag("check-3").performClick()
        assertEquals(listOf(3), toggled)
    }

    @Test fun the_add_button_reports() {
        show(emptyList())
        compose.onNodeWithContentDescription("Add a task").performClick()
        assertTrue(addTapped)
    }

    // ---- clearing the done list ---------------------------------------------

    @Test fun there_is_nothing_to_clear_while_the_done_section_is_shut() {
        show(listOf(upNextTask, doneTask))
        compose.onNodeWithTag("done-clear").assertDoesNotExist()
    }

    @Test fun opening_the_done_section_offers_to_clear_it() {
        show(listOf(doneTask), doneExpanded = true)
        compose.onNodeWithTag("done-clear").assertIsDisplayed()
    }

    @Test fun clearing_reports_it() {
        show(listOf(doneTask), doneExpanded = true)
        compose.onNodeWithTag("done-clear").performClick()
        assertEquals(1, clearTapped)
    }

    /**
     * The regression that a clickable ancestor would cause: wrapping the header
     * row in one merges "CLEAR" into the expand toggle, and every tap on it
     * would collapse the section instead of clearing — or do both.
     */
    @Test fun clearing_does_not_also_collapse_the_section() {
        show(listOf(doneTask), doneExpanded = true)
        compose.onNodeWithTag("done-clear").performClick()
        assertEquals("CLEAR must not reach the expand toggle", 0, sectionToggled)
    }

    @Test fun the_header_still_toggles_while_the_clear_label_shares_its_row() {
        show(listOf(doneTask), doneExpanded = true)
        compose.onNodeWithText("DONE (1)").performClick()
        assertEquals(1, sectionToggled)
        assertEquals("collapsing must not clear anything", 0, clearTapped)
    }

    // ---- opening a task -----------------------------------------------------

    /**
     * Once something is late the intent is almost always "done" or "not now",
     * so the tap goes to the action panel rather than to the editor.
     */
    @Test fun tapping_an_overdue_row_raises_the_action_panel() {
        show(listOf(overdueTask, upNextTask))
        compose.onNodeWithTag("row-1").performClick()
        assertEquals(listOf(1), reminded)
        assertTrue("an overdue tap must not open the editor", opened.isEmpty())
    }

    @Test fun tapping_a_row_that_is_not_late_opens_it_for_editing() {
        show(listOf(overdueTask, upNextTask))
        compose.onNodeWithTag("row-2").performClick()
        assertEquals(listOf(2), opened)
        assertTrue("only overdue rows raise the panel", reminded.isEmpty())
    }

    /**
     * One rule to remember, and the thing that keeps a repeater's only exit
     * reachable: Delete lives in the edit sheet, and an overdue repeater would
     * otherwise never reach it.
     */
    @Test fun holding_any_active_row_opens_the_editor() {
        show(listOf(overdueTask, upNextTask))
        compose.onNodeWithTag("row-1").performSemanticsAction(SemanticsActions.OnLongClick)
        compose.onNodeWithTag("row-2").performSemanticsAction(SemanticsActions.OnLongClick)
        assertEquals(listOf(1, 2), opened)
        assertTrue("holding must never raise the panel", reminded.isEmpty())
    }

    @Test fun a_done_row_opens_too() {
        show(listOf(doneTask), doneExpanded = true)
        compose.onNodeWithTag("row-3").performClick()
        assertEquals(listOf(3), opened)
    }

    /** Nothing in the done section is late, so nothing there has a hold gesture. */
    @Test fun a_done_row_carries_no_long_press_action() {
        show(listOf(doneTask), doneExpanded = true)
        val config = compose.onNodeWithTag("row-3").fetchSemanticsNode().config
        assertFalse(config.contains(SemanticsActions.OnLongClick))
    }

    /**
     * The check circle is a nested target inside a row that now opens the task,
     * so a tap on it has to tick the task off and stop there. If it fell through
     * as well, every completion would raise the edit sheet over the top of it.
     */
    @Test fun ticking_a_task_does_not_also_open_it() {
        show(listOf(overdueTask))
        compose.onNodeWithTag("check-1").performClick()
        compose.waitUntil { toggled.isNotEmpty() }
        assertEquals(listOf(1), toggled)
        assertTrue("the circle must not reach the row beneath it", opened.isEmpty())
    }

    /**
     * The circle is nested inside a row whose tap now raises the action panel,
     * so a tap on it has to tick the task off and stop there — otherwise every
     * completion would raise the panel over the top of it.
     */
    @Test fun ticking_an_overdue_task_does_not_also_raise_the_panel() {
        show(listOf(overdueTask))
        compose.onNodeWithTag("check-1").performClick()
        compose.waitUntil { toggled.isNotEmpty() }
        assertEquals(listOf(1), toggled)
        assertTrue("the circle must not reach the row beneath it", reminded.isEmpty())
    }

    // ---- the tick's beat -----------------------------------------------------

    /**
     * How many circles are currently wearing a tick. Only correct for an
     * active-only task list — an expanded Done section wears one tick per row
     * of its own, and this helper cannot tell those apart from the beat.
     */
    private fun checkedCircles() =
        compose.onAllNodesWithContentDescription("Done").fetchSemanticsNodes().size

    /**
     * The whole feature: the check is drawn *before* the store changes. Committing
     * first would remove the row, so the circle that was tapped would never spend
     * a single frame checked — which is exactly the old behaviour.
     */
    @Test fun the_check_fills_before_the_task_is_committed() {
        show(listOf(overdueTask))
        compose.mainClock.autoAdvance = false

        compose.onNodeWithTag("check-1").performClick()
        compose.mainClock.advanceTimeBy(100) // well inside the 280ms beat

        assertEquals("the circle should be wearing its tick", 1, checkedCircles())
        assertTrue("the commit must wait for the beat", toggled.isEmpty())
    }

    @Test fun the_tick_commits_once_the_beat_is_over() {
        show(listOf(overdueTask))

        compose.onNodeWithTag("check-1").performClick()
        compose.waitUntil { toggled.isNotEmpty() }

        assertEquals(listOf(1), toggled)
    }

    /** A completion keeps its check, and rides out with the row's exit fade. */
    @Test fun a_completed_tick_keeps_its_check() {
        outcome = ToggleOutcome.COMPLETED
        show(listOf(overdueTask))

        compose.onNodeWithTag("check-1").performClick()
        compose.waitUntil { toggled.isNotEmpty() }
        compose.mainClock.advanceTimeBy(300) // past TICK_FILL, so a drain would have finished
        compose.waitForIdle()

        assertEquals(1, checkedCircles())
    }

    /**
     * A repeater whose slot has not come refuses the tick and says so. The circle
     * has to come back with it — a check left behind would claim a completion the
     * app declined to make.
     */
    @Test fun a_refused_tick_gives_the_hollow_ring_back() {
        outcome = ToggleOutcome.NOT_DUE_YET
        show(listOf(upNextTask))
        // autoAdvance idles the clock straight through the whole beat while
        // fetching semantics nodes, so a waitUntil { checkedCircles() == 0 }
        // is satisfied vacuously — 0 whether or not a check was ever drawn.
        // Stepping the clock by hand is what proves the fill happened first.
        compose.mainClock.autoAdvance = false

        compose.onNodeWithTag("check-2").performClick()
        compose.mainClock.advanceTimeBy(100)
        assertEquals("it fills first", 1, checkedCircles())

        compose.mainClock.advanceTimeBy(400) // past the beat and the drain
        assertEquals(listOf(2), toggled)
        assertEquals("and drains after the refusal", 0, checkedCircles())
    }

    /**
     * A repeater rolling forward is not done either — the next occurrence has not
     * been finished, so the check drains as the row moves to its new band.
     */
    @Test fun a_rolled_forward_repeater_gives_the_hollow_ring_back_too() {
        outcome = ToggleOutcome.ADVANCED
        show(listOf(upNextTask))
        // Same trap as the refusal test above: fetchSemanticsNodes() idles the
        // clock while autoAdvance is on, so a bare waitUntil { == 0 } cannot
        // tell "drained" from "never drawn". Step the clock by hand instead.
        compose.mainClock.autoAdvance = false

        compose.onNodeWithTag("check-2").performClick()
        compose.mainClock.advanceTimeBy(100)
        assertEquals("it fills first", 1, checkedCircles())

        compose.mainClock.advanceTimeBy(400) // past the beat and the drain
        assertEquals(listOf(2), toggled)
        assertEquals("and drains after the roll-forward", 0, checkedCircles())
    }

    /**
     * A second tap inside the beat has to be swallowed by the circle, not fall
     * through to the row — which is why the circle stays enabled and no-ops
     * rather than disabling itself. A disabled `clickable` consumes nothing, so
     * the tap would carry on up the hit path to the row and raise the action
     * panel.
     */
    @Test fun a_second_tap_inside_the_beat_commits_once_and_opens_nothing() {
        show(listOf(overdueTask))
        compose.mainClock.autoAdvance = false

        compose.onNodeWithTag("check-1").performClick()
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("check-1").performClick()
        compose.mainClock.autoAdvance = true
        compose.waitUntil { toggled.isNotEmpty() }

        // The second tap landed at t≈100, so a hypothetical second commit would
        // land at t≈380. Advance well past that before asserting, or "commits
        // once" is unproven — waitUntil above only proves the first one arrived.
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()

        assertEquals(listOf(1), toggled)
        assertTrue("the circle must not reach the row beneath it", reminded.isEmpty())
        assertTrue(opened.isEmpty())
    }

    /**
     * Un-ticking is an undo, not an achievement, so the done row's circle commits
     * on the tap with nothing in front of it. The asymmetry is deliberate and is
     * pinned here, or a later tidy-up "restoring" the symmetry would put a quarter
     * second in front of every undo.
     */
    @Test fun un_ticking_a_done_row_has_no_beat() {
        show(listOf(doneTask), doneExpanded = true)
        compose.mainClock.autoAdvance = false

        compose.onNodeWithTag("check-3").performClick()

        assertEquals(listOf(3), toggled)
    }

    /**
     * FIX 1's regression pin. The pending tick used to be `remember`ed and timed
     * from inside `TaskRow` itself, which is a `LazyColumn` item — and a lazy
     * list disposes an item's subcomposition the moment it scrolls out of the
     * viewport, cancelling that coroutine mid-`delay` and throwing the
     * completion away. "Tick the top row, then fling down the list" is an
     * ordinary gesture, not a corner case. The fix hoists the pending state into
     * `TaskListScreen`, above the `LazyColumn`, so the row's disposal cannot
     * touch it. A list of 30 is enough to push row 1 well outside the viewport
     * and the lazy layout's prefetch window on this test's device qualifiers.
     */
    @Test fun a_tick_survives_its_row_scrolling_out_of_view() {
        val many = (1..30).map { i ->
            Task(i, "Task $i", at(2026, Calendar.JULY, 25, 8, i - 1), Repeat.ONCE)
        }
        show(many)
        compose.mainClock.autoAdvance = false

        compose.onNodeWithTag("check-1").performClick()
        compose.mainClock.advanceTimeBy(100) // inside the beat
        compose.onNodeWithTag("task-list").performScrollToIndex(29)
        compose.mainClock.autoAdvance = true

        compose.waitUntil { toggled.isNotEmpty() }
        assertEquals(listOf(1), toggled)
    }

    // ---- ordering -----------------------------------------------------------

    @Test fun active_tasks_are_ordered_by_when_they_are_due() {
        val later = Task(4, "Later", at(2026, Calendar.JULY, 30, 9), Repeat.ONCE)
        val sooner = Task(5, "Sooner", at(2026, Calendar.JULY, 26, 9), Repeat.ONCE)
        show(listOf(later, sooner))

        val soonerTop = compose.onNodeWithText("Sooner").fetchSemanticsNode().positionInRoot.y
        val laterTop = compose.onNodeWithText("Later").fetchSemanticsNode().positionInRoot.y
        assertTrue("the sooner task must sit above the later one", soonerTop < laterTop)
    }
}
