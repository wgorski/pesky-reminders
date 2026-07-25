package com.peskyreminders.poc.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.peskyreminders.poc.Repeat
import com.peskyreminders.poc.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Calendar
import java.util.TimeZone

/** Drives the list screen against a frozen clock, on the JVM. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class TaskListScreenTest {

    @get:Rule val compose = createComposeRule()

    @Before fun fixTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
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
    private var addTapped = false
    private var sectionToggled = 0

    private fun show(tasks: List<Task>, doneExpanded: Boolean = false) {
        compose.setContent {
            TaskListScreen(
                tasks = tasks,
                nowMillis = now,
                use24h = false,
                doneExpanded = doneExpanded,
                onToggleDoneSection = { sectionToggled++ },
                onToggleTask = { toggled += it },
                onAdd = { addTapped = true },
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

    @Test fun the_header_stamps_todays_date() {
        show(emptyList())
        compose.onNodeWithText("SAT 25 JUL").assertExists()
    }

    @Test fun an_empty_list_shows_the_empty_state() {
        show(emptyList())
        compose.onNodeWithText("Nothing to pester you about").assertExists()
        compose.onNodeWithText("OVERDUE").assertDoesNotExist()
        compose.onNodeWithText("UP NEXT").assertDoesNotExist()
    }

    @Test fun tasks_split_by_whether_they_are_late() {
        show(listOf(overdueTask, upNextTask))

        compose.onNodeWithText("OVERDUE").assertExists()
        compose.onNodeWithText("Call the vet").assertExists()
        compose.onNodeWithText("Was due Today, 9:00 AM").assertExists()

        compose.onNodeWithText("UP NEXT").assertExists()
        compose.onNodeWithText("Feed the sourdough").assertExists()
        compose.onNodeWithText("Wed, 9:00 AM").assertExists()

        compose.onNodeWithText("Nothing to pester you about").assertDoesNotExist()
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
        compose.onNodeWithTag("check-2").performClick()
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
