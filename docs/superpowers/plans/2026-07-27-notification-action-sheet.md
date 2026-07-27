# Notification Action Sheet Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make a tap on the notification body open one sheet that offers every outcome — Done, four snooze chips (15 min / 30 min / 1 hr / 3 hr) and the duration wheel — with every control committing on the tap, no confirm button.

**Architecture:** The existing snooze picker gains a Done action and loses its confirm button and footer readout; because nothing is ever *held*, all selection state disappears from the sheet. The notification gains a `contentIntent` pointing at the same `PendingIntent` its Snooze action already uses. No change to `Reminders`, the alarm model, or the task list.

**Tech Stack:** Kotlin, Jetpack Compose, AndroidX Core notifications, JUnit4 + Robolectric (JVM), AndroidX Test (instrumented).

Spec: `docs/superpowers/specs/2026-07-27-notification-action-sheet-design.md`

## Global Constraints

- **Environment.** Every command block that uses `adb`, `emulator`, `sdkmanager` or `./gradlew` must be prefixed with:
  ```bash
  export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
  export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
  ```
- **Gradle wrapper only** (`./gradlew`, 8.11.1). The system `gradle` cannot configure AGP 8.7.3.
- **A `PostToolUse` hook runs the JVM suite after every edit to a `.kt`/`.kts`/manifest file.** It runs in the background and only interrupts on failure, so expect noise while a task is mid-rename. Sequence edits so the tree spends as little time as possible uncompilable.
- **Version bumps once for the whole session**, not per commit: `versionName` `0.11.2` → `0.12.0`, `versionCode` `17` → `18`. Done in Task 4 only.
- **`TaskTime` and `SnoozeOptions` stay pure** — no `Context`, no clock reads. `nowMillis` is always a parameter.
- **Press feedback is a scale, not a ripple.** Use `Modifier.pressable(scale = …)` from `ui/Common.kt`, placed **before** `.clip()`/`.background()` in the chain.
- **Text on the accent is `PeskyColors.Text`** (cream), never `Screen`.
- **Copy, verbatim:** section labels are `"Snooze for"` and `"…or dial it in"` (that is a single `…` ellipsis character, not three dots). The Done control reads `"Done"`. The notification actions stay `"Snooze"` and `"Done"`.
- **Presets, verbatim:** `listOf(15, 30, 60, 180)`.
- **Robolectric caveat:** pointer injection does not reach into a sheet body, so tests assert a control is displayed and then fire its click action directly via `performSemanticsAction(SemanticsActions.OnClick)`. Tests pin `TimeZone` to UTC.
- **Nothing is done until a screenshot shows it.** A clean compile and a green JVM suite are not sufficient (Task 4).

---

### Task 1: `SnoozeOptions` — new presets, five minutes moves to the wheel

Presets become 15 / 30 / 1 hr / 3 hr. Five minutes stops being a chip and becomes the wheel's first rung, which deliberately breaks the "every entry is a multiple of `STEP_MINUTES`" rule.

`landsAtAClockTime` / `CLOCK_TIME_ABOVE_MINUTES` are **not** touched here — `SnoozeSheet.kt` still calls them and the tree must compile. They go in Task 2.

**Files:**
- Modify: `app/src/main/java/com/peskyreminders/poc/SnoozeOptions.kt`
- Test: `app/src/test/java/com/peskyreminders/poc/SnoozeOptionsTest.kt`
- Test (drive-by fix): `app/src/test/java/com/peskyreminders/poc/ui/SnoozeSheetTest.kt` — two assertions hard-code the old preset list. That file is replaced wholesale in Task 2; this keeps the suite green in between.

**Interfaces:**
- Consumes: nothing.
- Produces: `SnoozeOptions.PRESETS: List<Int>` == `listOf(15, 30, 60, 180)`; `SnoozeOptions.WHEEL: List<Int>` starting at `5`. `DEFAULT_MINUTES`, `STEP_MINUTES`, `MAX_MINUTES`, `label`, `chipLabel`, `chipUnit` all keep their current signatures and behaviour.

- [ ] **Step 1: Write the failing tests**

In `app/src/test/java/com/peskyreminders/poc/SnoozeOptionsTest.kt`, replace the test named `the_wheel_starts_at_a_quarter_hour_and_reaches_three_days` with:

```kotlin
    @Test fun the_wheel_starts_at_five_minutes_and_reaches_three_days() {
        assertEquals(5, SnoozeOptions.WHEEL.first())
        assertEquals(72 * 60, SnoozeOptions.WHEEL.last())
        assertEquals(72 * 60, SnoozeOptions.MAX_MINUTES)
    }
```

Replace `every_entry_stays_aligned_to_a_quarter_hour` with:

```kotlin
    /**
     * Five minutes is the single exception, and it is the first rung: it is the
     * shortest snooze worth offering and it is not a multiple of the step.
     */
    @Test fun every_entry_above_the_first_rung_stays_aligned_to_a_quarter_hour() {
        assertTrue(
            "an unaligned entry would label as e.g. '1 hr 7'",
            SnoozeOptions.WHEEL.drop(1).all { it % SnoozeOptions.STEP_MINUTES == 0 },
        )
    }
```

Replace `five_minutes_is_a_preset_only` and `every_preset_except_five_also_appears_on_the_wheel` with:

```kotlin
    @Test fun the_presets_are_the_four_common_snoozes() {
        assertEquals(listOf(15, 30, 60, 180), SnoozeOptions.PRESETS)
    }

    /** It lost its chip when the presets became 15/30/1hr/3hr. */
    @Test fun five_minutes_is_reachable_on_the_wheel_but_is_no_longer_a_chip() {
        assertTrue(SnoozeOptions.WHEEL.contains(5))
        assertFalse(SnoozeOptions.PRESETS.contains(5))
    }

    @Test fun every_preset_also_appears_on_the_wheel() {
        SnoozeOptions.PRESETS.forEach {
            assertTrue("$it should be reachable on the wheel", SnoozeOptions.WHEEL.contains(it))
        }
    }
```

Replace `chips_split_the_number_from_its_unit` with one that uses the real presets:

```kotlin
    @Test fun chips_split_the_number_from_its_unit() {
        assertEquals("15" to "min", SnoozeOptions.chipLabel(15) to SnoozeOptions.chipUnit(15))
        assertEquals("30" to "min", SnoozeOptions.chipLabel(30) to SnoozeOptions.chipUnit(30))
        assertEquals("1" to "hr", SnoozeOptions.chipLabel(60) to SnoozeOptions.chipUnit(60))
        assertEquals("3" to "hr", SnoozeOptions.chipLabel(180) to SnoozeOptions.chipUnit(180))
    }
```

Rename `the_default_matches_the_snooze_the_app_shipped_with` and reword its intent — five minutes is no longer offered as a chip, but it is still the API default:

```kotlin
    /**
     * The sheet no longer pre-selects anything, but this is still the default
     * argument on `Reminders.snooze` and `snoozeTriggerAtMillis`.
     */
    @Test fun the_snooze_api_still_defaults_to_five_minutes() {
        assertEquals(5, SnoozeOptions.DEFAULT_MINUTES)
        assertEquals(
            1_000_000L + 5 * 60_000L,
            ReminderContract.snoozeTriggerAtMillis(1_000_000L),
        )
    }
```

Leave `the_wheel_only_ever_goes_up`, `the_step_coarsens_as_the_durations_grow`, `the_round_durations_people_actually_reach_for_are_all_on_it`, all four `labels_*` tests and the three `landsAtAClockTime` tests exactly as they are.

- [ ] **Step 2: Run the tests to verify they fail**

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
./gradlew :app:testDebugUnitTest --tests 'com.peskyreminders.poc.SnoozeOptionsTest'
```

Expected: FAIL. `the_wheel_starts_at_five_minutes_and_reaches_three_days` gets `expected:<5> but was:<15>`; `the_presets_are_the_four_common_snoozes` gets `expected:<[15, 30, 60, 180]> but was:<[5, 15, 30, 60]>`.

- [ ] **Step 3: Implement**

In `app/src/main/java/com/peskyreminders/poc/SnoozeOptions.kt`:

Replace the file's KDoc and the `PRESETS` declaration:

```kotlin
/**
 * The durations offered when snoozing.
 *
 * Four chips cover the common cases; the wheel covers everything else, out to
 * three days. The sheet commits on the tap, so neither of them holds a
 * selection — see [com.peskyreminders.poc.ui.ReminderSheet].
 */
object SnoozeOptions {

    /** The chips, in the order they are laid out: 15 min, 30 min, 1 hr, 3 hr. */
    val PRESETS = listOf(15, 30, 60, 180)
```

Add the constant just above `BANDS`:

```kotlin
    /** The one wheel entry that is not a multiple of [STEP_MINUTES]. See [WHEEL]. */
    private const val SHORTEST_MINUTES = 5
```

Replace the `WHEEL` declaration and its comment:

```kotlin
    /**
     * 5, then 15, 30 … 2 hr, 2 hr 30 … 6 hr, 7 hr … 1 day, 1 day 6 hr … 3 days.
     *
     * Five minutes is the first rung and the sole break in the [STEP_MINUTES]
     * alignment. It used to be a preset chip; the chips are now 15/30/1hr/3hr, so
     * this is the only place left to reach the shortest useful snooze.
     */
    val WHEEL: List<Int> = buildList {
        add(SHORTEST_MINUTES)
        var previous = 0
        for ((step, upTo) in BANDS) {
            for (minutes in (previous + step)..upTo step step) add(minutes)
            previous = upTo
        }
    }
```

Update the `DEFAULT_MINUTES` KDoc (it currently has none) so the next reader does not delete it as dead:

```kotlin
    /**
     * The default argument on `Reminders.snooze` and
     * `ReminderContract.snoozeTriggerAtMillis`. The sheet pre-selects nothing, so
     * nothing in the UI reads this — it is the API's own fallback.
     */
    const val DEFAULT_MINUTES = 5
```

- [ ] **Step 4: Fix the two stale assertions in the sheet test**

In `app/src/test/java/com/peskyreminders/poc/ui/SnoozeSheetTest.kt`, `every_preset_is_offered` and `each_preset_moves_the_readout` hard-code the old list. The clock is frozen at 14:20, so the new expectations are:

```kotlin
    @Test fun every_preset_is_offered() {
        show()
        listOf(15, 30, 60, 180).forEach {
            compose.onNodeWithTag("preset-$it").assertIsDisplayed()
        }
    }
```

```kotlin
    @Test fun each_preset_moves_the_readout() {
        val expected = listOf(
            15 to "Back at Today, 2:35 PM",
            30 to "Back at Today, 2:50 PM",
            60 to "Back at Today, 3:20 PM",
            180 to "Back at Today, 5:20 PM",
        )
        show()
        expected.forEach { (minutes, label) ->
            tapPreset(minutes)
            backAt().assertTextEquals(label)
        }
    }
```

- [ ] **Step 5: Run the whole JVM suite**

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
./gradlew :app:testDebugUnitTest
```

Expected: PASS, all tests. If `a_preset_and_the_wheel_stay_in_step` fails, check that 60 is still on both the preset list and the wheel.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/peskyreminders/poc/SnoozeOptions.kt \
        app/src/test/java/com/peskyreminders/poc/SnoozeOptionsTest.kt \
        app/src/test/java/com/peskyreminders/poc/ui/SnoozeSheetTest.kt
git commit -m "feat: snooze chips become 15 min / 30 min / 1 hr / 3 hr

Five minutes loses its chip and becomes the wheel's first rung, the
single entry that is not a multiple of the quarter-hour step. It is
still the default argument on Reminders.snooze.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01NzvRRq4EwJ5C1UBAcn7P2Z"
```

---

### Task 2: `ReminderSheet` — Done, one-tap commit, no footer

The sheet is renamed and rewritten. Every control commits on the tap, so the confirm button, the `"Back at …"` readout and all selection state go. Every wheel row now carries the clock time it lands on, which makes `landsAtAClockTime` dead — it and `CLOCK_TIME_ABOVE_MINUTES` are deleted here, along with the three `SnoozeOptionsTest` cases that cover them.

**Files:**
- Rename: `app/src/main/java/com/peskyreminders/poc/ui/SnoozeSheet.kt` → `ui/ReminderSheet.kt` (use `git mv`)
- Rename: `app/src/test/java/com/peskyreminders/poc/ui/SnoozeSheetTest.kt` → `ui/ReminderSheetTest.kt` (use `git mv`)
- Modify: `app/src/main/java/com/peskyreminders/poc/ui/PeskySheet.kt:156` — add a test tag to the title `Text`
- Modify: `app/src/main/java/com/peskyreminders/poc/SnoozeActivity.kt` — call `ReminderSheet`, supply `onDone`
- Modify: `app/src/main/java/com/peskyreminders/poc/SnoozeOptions.kt` — delete `landsAtAClockTime` and `CLOCK_TIME_ABOVE_MINUTES`
- Modify: `app/src/test/java/com/peskyreminders/poc/SnoozeOptionsTest.kt` — delete the three tests for them

**Interfaces:**
- Consumes: `SnoozeOptions.PRESETS`, `SnoozeOptions.WHEEL`, `SnoozeOptions.label(Int): String`, `SnoozeOptions.chipLabel(Int): String`, `SnoozeOptions.chipUnit(Int): String` (Task 1). `TaskTime.formatCompact(millis: Long, nowMillis: Long, use24h: Boolean): String`. `PeskyWheel(title, count, selectedIndex, label, onPick, modifier, height, showTitle, aside)`. `PeskySheet(title, onDismiss, bodyPadding, bodySpacing, footer, body)`. `Modifier.pressable(scale, onClick)` and `Modifier.tap(onClick)` from `ui/Common.kt`. `PeskyIcons.Check`.
- Produces: `ReminderSheet(taskName: String, nowMillis: Long, use24h: Boolean, onDismiss: () -> Unit, onDone: () -> Unit, onSnooze: (minutes: Int) -> Unit)`. Test tags `"done-button"`, `"preset-<minutes>"`, `"sheet-title"`; the wheel keeps tags `"wheel-SNOOZE"` and `"SNOOZE-<index>"`. Task 3 renames the activity that calls this.

- [ ] **Step 1: Move both files with git so history follows**

```bash
git mv app/src/main/java/com/peskyreminders/poc/ui/SnoozeSheet.kt \
       app/src/main/java/com/peskyreminders/poc/ui/ReminderSheet.kt
git mv app/src/test/java/com/peskyreminders/poc/ui/SnoozeSheetTest.kt \
       app/src/test/java/com/peskyreminders/poc/ui/ReminderSheetTest.kt
```

- [ ] **Step 2: Write the failing test**

Replace the entire contents of `app/src/test/java/com/peskyreminders/poc/ui/ReminderSheetTest.kt` with:

```kotlin
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
```

- [ ] **Step 3: Run it to verify it fails**

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
./gradlew :app:testDebugUnitTest --tests 'com.peskyreminders.poc.ui.ReminderSheetTest'
```

Expected: FAIL to compile — `Unresolved reference: ReminderSheet`.

- [ ] **Step 4: Write the sheet**

Replace the entire contents of `app/src/main/java/com/peskyreminders/poc/ui/ReminderSheet.kt` with:

```kotlin
package com.peskyreminders.poc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.peskyreminders.poc.SnoozeOptions
import com.peskyreminders.poc.TaskTime

private val R12 = RoundedCornerShape(12.dp)

/**
 * Everything you can do about a reminder that has gone off, on one screen:
 * finish it, or push it by any duration.
 *
 * Raised by the notification, and nowhere else — both by a tap on its body and
 * by its Snooze action. The task list picks absolute times instead, which is why
 * this no longer takes its title and labels as parameters.
 *
 * **Every control commits the moment it is touched.** There is no confirm
 * button, and therefore no selection to hold and nothing to highlight. It is
 * also why there is no "back at …" footer: with no held choice there is nothing
 * to preview. Each wheel row states the time it lands on instead, so you can see
 * where a tap goes before you take it.
 *
 * The duration always counts from now, matching
 * [com.peskyreminders.poc.Reminders.snooze]. There is deliberately no way to
 * pass a different starting point, because a preview that can disagree with what
 * the tap does is worse than no preview.
 */
@Composable
fun ReminderSheet(
    taskName: String,
    nowMillis: Long,
    use24h: Boolean,
    onDismiss: () -> Unit,
    onDone: () -> Unit,
    onSnooze: (minutes: Int) -> Unit,
) {
    PeskySheet(
        title = taskName,
        onDismiss = onDismiss,
        // A little more room at the foot than the default, since there is no
        // footer left to sit between the wheel and the navigation bar.
        bodyPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 10.dp, bottom = 22.dp),
    ) {
        DoneButton(onDone)

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Snooze for", style = PeskyType.FieldLabel)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SnoozeOptions.PRESETS.forEach { preset ->
                    PresetChip(minutes = preset, modifier = Modifier.weight(1f)) {
                        onSnooze(preset)
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("…or dial it in", style = PeskyType.FieldLabel)
            PeskyWheel(
                title = "SNOOZE",
                showTitle = false,
                count = SnoozeOptions.WHEEL.size,
                // Nothing is ever held, so there is nothing to mark as chosen.
                // PeskyWheel's scroll-into-view effect returns early on a
                // negative index, so this also leaves the list where it opened.
                selectedIndex = -1,
                label = { SnoozeOptions.label(SnoozeOptions.WHEEL[it]) },
                // With the footer readout gone this is the only place a landing
                // time appears, so every row carries one — not just the long
                // durations that are hard to picture.
                aside = { index ->
                    "(" + TaskTime.formatCompact(
                        nowMillis + SnoozeOptions.WHEEL[index] * 60_000L, nowMillis, use24h,
                    ) + ")"
                },
                onPick = { onSnooze(SnoozeOptions.WHEEL[it]) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * The one filled control in the sheet, and the only thing giving it a
 * hierarchy — everything below is a flat chip or a wheel row.
 *
 * [TaskSheet] puts its immediate action at the foot behind a hairline, because
 * everything above it there is a draft waiting for Save and the hairline marks
 * that change of register. Nothing here waits for anything, so there are no two
 * registers to separate and this goes on top as the primary outcome.
 */
@Composable
private fun DoneButton(onDone: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .testTag("done-button")
            .pressable(scale = 0.99f, onClick = onDone)
            .clip(CircleShape)
            .background(PeskyColors.Accent),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = PeskyIcons.Check,
            contentDescription = null,
            // Cream, not the screen colour: near-black on this crimson is muddy.
            tint = PeskyColors.Text,
            modifier = Modifier.size(18.dp),
        )
        Text("Done", style = PeskyType.Action, color = PeskyColors.Text)
    }
}

@Composable
private fun PresetChip(
    minutes: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .testTag("preset-$minutes")
            .pressable(scale = 0.96f, onClick = onClick)
            .clip(R12)
            .background(PeskyColors.Field)
            .border(1.dp, PeskyColors.FieldBorder, R12)
            .padding(vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            SnoozeOptions.chipLabel(minutes),
            fontFamily = DmSans,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = PeskyColors.Text,
        )
        Text(
            SnoozeOptions.chipUnit(minutes),
            fontFamily = DmSans,
            fontSize = 10.sp,
            color = PeskyColors.TextDim,
        )
    }
}
```

- [ ] **Step 5: Give the shared sheet title a test tag**

In `app/src/main/java/com/peskyreminders/poc/ui/PeskySheet.kt`, inside `SheetHeader`, the title `Text` currently has `modifier = Modifier.weight(1f, fill = false)`. Change it to:

```kotlin
            modifier = Modifier.weight(1f, fill = false).testTag("sheet-title"),
```

- [ ] **Step 6: Wire the activity's new callback**

In `app/src/main/java/com/peskyreminders/poc/SnoozeActivity.kt` (still under its old name — Task 3 renames it), change the import and the `setContent` block:

```kotlin
import com.peskyreminders.poc.ui.ReminderSheet
```

```kotlin
        setContent {
            ReminderSheet(
                taskName = task.name,
                nowMillis = System.currentTimeMillis(),
                use24h = DateFormat.is24HourFormat(this),
                onDismiss = { finish() },
                onDone = {
                    // toggle can refuse a repeater whose slot has not come, but
                    // that cannot happen from here: a notification only exists
                    // once the slot has passed, and every snooze cancels it.
                    // There is no PeskyApp to raise a toast on either — this
                    // activity is closing.
                    Reminders.toggle(this, taskId)
                    finish()
                },
                onSnooze = { minutes ->
                    Reminders.snooze(this, taskId, minutes)
                    finish()
                },
            )
        }
```

Also update the class KDoc's last paragraph, which is now wrong — backing out is no longer "only the Snooze button commits":

```kotlin
 * Backing out — the close button, the scrim, or the back gesture — leaves the
 * reminder exactly as it was. Everything else in the sheet commits on the tap.
```

- [ ] **Step 7: Delete the now-dead clock-time predicate**

In `app/src/main/java/com/peskyreminders/poc/SnoozeOptions.kt`, delete these three declarations outright (every wheel row shows its landing time now, so nothing decides per-row any more):

```kotlin
    const val CLOCK_TIME_ABOVE_MINUTES = 3 * HOUR

    fun landsAtAClockTime(minutes: Int): Boolean = minutes > CLOCK_TIME_ABOVE_MINUTES
```

…together with the `/** Past this, a duration stops being something you can picture … */` KDoc above `CLOCK_TIME_ABOVE_MINUTES` and the `/** Whether [minutes] is long enough to be worth spelling out as a time. */` KDoc above `landsAtAClockTime`.

In `app/src/test/java/com/peskyreminders/poc/SnoozeOptionsTest.kt`, delete the whole `// ---- when the clock time is spelled out ----` section: `three_hours_and_under_stand_on_their_own`, `anything_over_three_hours_gets_a_clock_time` and `the_boundary_is_three_hours_exactly`.

- [ ] **Step 8: Run the whole JVM suite**

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
./gradlew :app:testDebugUnitTest
```

Expected: PASS.

Two failures to expect and how to read them:
- `(2:25 PM)` not found in `even_the_shortest_rung_shows_where_it_lands` → the `aside` lambda is still conditional; it must return a string for every index.
- `sheet-title` node not found → Step 5 was skipped or the `.testTag` landed after a `.weight` on a different modifier chain.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/peskyreminders/poc/ui/ReminderSheet.kt \
        app/src/main/java/com/peskyreminders/poc/ui/PeskySheet.kt \
        app/src/main/java/com/peskyreminders/poc/SnoozeActivity.kt \
        app/src/main/java/com/peskyreminders/poc/SnoozeOptions.kt \
        app/src/test/java/com/peskyreminders/poc/ui/ReminderSheetTest.kt \
        app/src/test/java/com/peskyreminders/poc/SnoozeOptionsTest.kt
git commit -m "feat: the snooze sheet commits on the tap, and offers Done

SnoozeSheet becomes ReminderSheet: a Done pill on top, four chips and
the wheel below, and no confirm button — so no held selection and no
'back at' footer. Each wheel row states the time it lands on instead,
which retires landsAtAClockTime.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01NzvRRq4EwJ5C1UBAcn7P2Z"
```

---

### Task 3: The notification body opens the sheet

Adds the `contentIntent` that has never existed, and renames the activity to match the sheet.

**Files:**
- Rename: `app/src/main/java/com/peskyreminders/poc/SnoozeActivity.kt` → `ReminderActivity.kt` (use `git mv`)
- Modify: `app/src/main/AndroidManifest.xml:28-33`
- Modify: `app/src/main/java/com/peskyreminders/poc/ReminderNotifier.kt`
- Test: `app/src/androidTest/java/com/peskyreminders/poc/ReminderModelTest.kt`

**Interfaces:**
- Consumes: `ReminderSheet(...)` (Task 2); `ReminderContract.requestCode(taskId, ReminderContract.SLOT_SNOOZE)`; `ReminderContract.EXTRA_TASK_ID`.
- Produces: `class ReminderActivity : ComponentActivity()`; `ReminderNotifier.post` sets a `contentIntent` that is object-identical to the Snooze action's `PendingIntent`.

- [ ] **Step 1: Write the failing test**

In `app/src/androidTest/java/com/peskyreminders/poc/ReminderModelTest.kt`, add this immediately after `the_snooze_action_opens_an_activity_not_a_broadcast`:

```kotlin
    /**
     * The body used to do nothing at all — there was no contentIntent — so the
     * only way to act on a reminder was the two small action buttons.
     *
     * It must not auto-cancel: tapping is not one of the two sanctioned ways to
     * clear a notification you are not allowed to dismiss.
     */
    @Test fun tapping_the_notification_body_opens_the_same_sheet_as_snooze() {
        deliver(ReminderContract.ACTION_FIRE)
        val n = active()
        assertNotNull("precondition: posted", n)

        val open = n!!.notification.contentIntent
        assertNotNull("the body must be tappable", open)
        assertTrue("must be an activity; a trampoline is blocked on 12+", open.isActivity)

        val snooze = n.notification.actions.first { it.title == "Snooze" }
        assertEquals("the body and Snooze open the same sheet", snooze.actionIntent, open)

        assertEquals(
            "tapping must not clear a reminder you cannot dismiss",
            0,
            n.notification.flags and Notification.FLAG_AUTO_CANCEL,
        )
    }
```

- [ ] **Step 2: Boot the emulator and run it to verify it fails**

If the emulator is not already running, start it — and if it dies mid-task, restart it without asking:

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
emulator -avd pesky -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect &
adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do sleep 2; done
adb shell wm dismiss-keyguard
```

Then (note: connected-test tasks do **not** support `--tests`; use the property form):

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.peskyreminders.poc.ReminderModelTest
```

Expected: FAIL — `tapping_the_notification_body_opens_the_same_sheet_as_snooze` gets `the body must be tappable` (the `contentIntent` is null).

**This wipes the task list on the device** — `ReminderModelTest` calls `TaskStore.clear()`. That is fine; Task 4 reseeds for screenshots.

- [ ] **Step 3: Rename the activity**

```bash
git mv app/src/main/java/com/peskyreminders/poc/SnoozeActivity.kt \
       app/src/main/java/com/peskyreminders/poc/ReminderActivity.kt
```

In the renamed file, change the class name and its KDoc:

```kotlin
/**
 * The reminder's action sheet, opened straight from the notification — by a tap
 * on its body or on its Snooze action.
 *
 * It has to be an activity: since Android 12 a notification action cannot hand
 * off to a background receiver that then shows UI, so there is no way to raise
 * this from [ReminderReceiver]. Drawn translucent over whatever is on screen.
 *
 * Backing out — the close button, the scrim, or the back gesture — leaves the
 * reminder exactly as it was. Everything else in the sheet commits on the tap.
 */
class ReminderActivity : ComponentActivity() {
```

- [ ] **Step 4: Point the manifest at it**

In `app/src/main/AndroidManifest.xml`, replace the comment and `android:name` of that activity:

```xml
        <!-- Opened by a tap on the notification body and by its Snooze action;
             notification actions cannot raise UI via a receiver on Android 12+. -->
        <activity
            android:name=".ReminderActivity"
            android:exported="false"
            android:launchMode="singleTop"
            android:excludeFromRecents="true"
            android:theme="@style/Theme.Pesky.Transparent" />
```

Leave `Theme.Pesky.Transparent` alone — it is already generically named. Update the comment inside `app/src/main/res/values/themes.xml` from "The snooze picker draws over…" to "The reminder sheet draws over…".

- [ ] **Step 5: Set the content intent**

In `app/src/main/java/com/peskyreminders/poc/ReminderNotifier.kt`, rename `snoozePickerIntent` to `openSheetIntent`, point it at `ReminderActivity`, and update its KDoc:

```kotlin
    /**
     * Opens the reminder's action sheet — Done, the snooze chips and the wheel.
     *
     * Serves both the body tap and the Snooze action: same intent, same request
     * code, so it is literally the same PendingIntent and needs no slot of its
     * own in [ReminderContract].
     *
     * An activity, not a broadcast: Android 12+ blocks a notification action
     * from bouncing through a receiver to show UI.
     */
    private fun openSheetIntent(context: Context, taskId: Int): PendingIntent {
        val intent = Intent(context, ReminderActivity::class.java).apply {
            putExtra(ReminderContract.EXTRA_TASK_ID, taskId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            context,
            ReminderContract.requestCode(taskId, ReminderContract.SLOT_SNOOZE),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
```

In `post`, hoist it to a local and use it for both. Insert `val open = openSheetIntent(context, task.id)` just above the `NotificationCompat.Builder(...)` call, then change the two lines in the builder chain:

```kotlin
            .setOnlyAlertOnce(true)
            // A tap on the body opens the same sheet the Snooze action does.
            // autoCancel stays off: tapping is not one of the two sanctioned
            // ways to clear a notification you are not allowed to dismiss.
            .setContentIntent(open)
            .setDeleteIntent(
                broadcast(context, ReminderContract.ACTION_REPOST, task.id, ReminderContract.SLOT_REPOST)
            )
            .addAction(0, "Snooze", open)
```

- [ ] **Step 6: Run the instrumented suite**

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.peskyreminders.poc.ReminderModelTest
```

Expected: PASS, all of `ReminderModelTest`. `the_snooze_action_opens_an_activity_not_a_broadcast` and `fire_posts_ongoing_notification_with_two_actions` must still pass — if either fails, the two actions were disturbed.

- [ ] **Step 7: Run the JVM suite too**

```bash
./gradlew :app:testDebugUnitTest
```

Expected: PASS. This catches any leftover reference to `SnoozeActivity`.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/peskyreminders/poc/ReminderActivity.kt \
        app/src/main/java/com/peskyreminders/poc/ReminderNotifier.kt \
        app/src/main/AndroidManifest.xml \
        app/src/main/res/values/themes.xml \
        app/src/androidTest/java/com/peskyreminders/poc/ReminderModelTest.kt
git commit -m "feat: tapping the notification opens the action sheet

The body had no contentIntent at all, so the only way to act on a
reminder was the two small action buttons. It now opens the same sheet
the Snooze action does — the same PendingIntent, so no new request-code
slot — and autoCancel stays off so the tap cannot clear it.

SnoozeActivity becomes ReminderActivity, matching ReminderSheet.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01NzvRRq4EwJ5C1UBAcn7P2Z"
```

---

### Task 4: Verify on the emulator, bump the version, publish

The JVM suite proves logic and wiring; it does not prove the sheet renders or that taps land. Compose errors — bad modifier order, taps falling through to the wrong layer — compile fine and pass the JVM suite.

**Files:**
- Modify: `app/build.gradle.kts:15,18`
- Modify: `CLAUDE.md`
- Create: `docs/screenshots/` — two new PNGs

**Interfaces:**
- Consumes: everything from Tasks 1–3.
- Produces: `dist/pesky-reminders-0.12.0.apk` and `dist/pesky-reminders.apk`, served over the existing tunnel.

- [ ] **Step 1: Bump the version**

In `app/build.gradle.kts`:

```kotlin
        versionCode = 18
```
```kotlin
        versionName = "0.12.0"
```

Minor, not patch: this is a behaviour change.

- [ ] **Step 2: Build and install the debug APK**

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop com.peskyreminders.poc
adb shell am start -n com.peskyreminders.poc/.MainActivity
```

- [ ] **Step 3: Fire a reminder and screenshot the notification**

`ReminderModelTest` wiped the store in Task 3, so seed one task due a second ago and let the receiver post it. Keep this as straight-line `adb` calls — a shell function called in a loop has exhausted the process table and killed the emulator before.

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
adb shell am broadcast -a com.peskyreminders.poc.FIRE \
  -n com.peskyreminders.poc/.ReminderReceiver --ei extra_task_id 1
sleep 2
adb shell cmd statusbar expand-notifications
sleep 1
adb exec-out screencap -p > /tmp/notif-shade.png
```

If no notification appears, no task with id 1 exists — add one through the app's FAB with a time an hour out, then edit it earlier, or seed `shared_prefs` directly (write the file, then `chown` it to the app's uid) and restart the app.

Look at `/tmp/notif-shade.png`. Confirm: the reminder is there, with **Snooze** and **Done** actions.

- [ ] **Step 4: Tap the notification body and screenshot the sheet**

`screencap` is full device resolution, 1080×2400 on the `pesky` AVD — scale tap coordinates if you measured them on a downscaled image.

```bash
adb shell input tap 540 400
sleep 2
adb exec-out screencap -p > /tmp/sheet-open.png
```

Look at `/tmp/sheet-open.png` and confirm every one of these:
- the sheet is open, titled with the **task's name** (not "Snooze until");
- a filled crimson **Done** pill with a check mark sits at the top;
- the chips read **15 min / 30 min / 1 hr / 3 hr** — number above, unit below;
- **no** Snooze button and **no** "Back at …" line anywhere;
- every visible wheel row carries a parenthesised clock time, including the short ones;
- the wheel's first row is **5 min**;
- nothing is highlighted as selected — no accent wash on any chip or row.

- [ ] **Step 5: Prove a tap commits, and that the notification clears**

```bash
adb exec-out screencap -p > /tmp/before-tap.png
```

Note the y-coordinate of the "30 min" chip from `/tmp/sheet-open.png`, then tap it (the chips span the sheet's width in four equal columns, so the second one is at roughly x=405 on a 1080-wide screen):

```bash
adb shell input tap 405 <y-of-the-chip-row>
sleep 2
adb exec-out screencap -p > /tmp/after-snooze.png
adb shell dumpsys alarm | grep -A2 peskyreminders | head -20
```

Confirm in `/tmp/after-snooze.png`: the sheet closed immediately on the single tap, with no confirm step. Confirm in the `dumpsys` output that an alarm is armed roughly 30 minutes out.

Then re-fire and check Done the same way:

```bash
adb shell am broadcast -a com.peskyreminders.poc.FIRE \
  -n com.peskyreminders.poc/.ReminderReceiver --ei extra_task_id 1
sleep 2
adb shell cmd statusbar expand-notifications
sleep 1
adb shell input tap 540 400
sleep 2
adb exec-out screencap -p > /tmp/sheet-again.png
```

Tap the Done pill using its y-coordinate from `/tmp/sheet-again.png`:

```bash
adb shell input tap 540 <y-of-the-done-pill>
sleep 2
adb shell cmd statusbar expand-notifications
sleep 1
adb exec-out screencap -p > /tmp/after-done.png
```

Confirm in `/tmp/after-done.png` that the notification is gone and the task is in the Done section of the list.

- [ ] **Step 6: Check the sheet at a large font scale**

The task sheet only just fits its ceiling; this one is shorter (it lost a footer and gained a pill) but check it anyway at the case that broke before — 440dpi, font scale 1.3:

```bash
adb shell settings put system font_scale 1.3
adb shell am force-stop com.peskyreminders.poc
adb shell am broadcast -a com.peskyreminders.poc.FIRE \
  -n com.peskyreminders.poc/.ReminderReceiver --ei extra_task_id 1
sleep 2
adb shell cmd statusbar expand-notifications
sleep 1
adb shell input tap 540 400
sleep 2
adb exec-out screencap -p > /tmp/sheet-large-font.png
adb shell settings put system font_scale 1.0
```

Confirm the whole sheet still fits without the body becoming scrollable by a few dp — the tell is the first field label sliding under the header.

- [ ] **Step 7: Save two screenshots for the docs**

```bash
cp /tmp/sheet-open.png docs/screenshots/reminder-sheet.png
cp /tmp/notif-shade.png docs/screenshots/notification.png
```

- [ ] **Step 8: Update `CLAUDE.md`**

Five edits:

1. **"What this is", first paragraph** — it says the notification's Snooze is "+5 min". Replace that clause with: `only the notification's own **Snooze** and **Done** actions, or the action sheet a tap on it opens, can clear it.`
2. **Project layout** — rename three entries and reword them:
   - `SnoozeActivity.kt   # translucent host for the snooze picker` → `ReminderActivity.kt # translucent host for the notification's action sheet`
   - `SnoozeSheet.kt      # "Snooze until" — notification only: presets + 15 min–72 hr wheel` → `ReminderSheet.kt     # notification only: Done, 15/30/1h/3h chips, 5 min–72 hr wheel`
   - `ui/SnoozeSheetTest.kt #   presets, wheel, readout, commit` → `ui/ReminderSheetTest.kt #  Done, chips, wheel, one-tap commit`
3. **Testing section** — update both counts. Run `./gradlew :app:testDebugUnitTest` and read the actual totals out of `app/build/reports/tests/testDebugUnitTest/index.html` rather than guessing; the instrumented count goes from 56 to 57.
4. **The "Snooze counts from the clock" bullet** — its last sentence says the sheet "is now reachable only from the notification's Snooze action". Replace with: `It is reachable only from the notification — its Snooze action, or a tap on its body — because the task list picks absolute times instead.`
5. **Add a new bullet** to Conventions & gotchas, after the snooze one:

```markdown
- **The reminder sheet has no confirm step.** Every chip and every wheel row
  commits on the tap, which is why nothing in it holds a selection: the chips
  have no chosen state and the wheel is passed `selectedIndex = -1`. It is also
  why there is no "back at …" footer — with no held choice there is nothing to
  preview, so each wheel row states the time it lands on instead. Adding a
  highlight back would promise a confirm step that does not exist.
```

- [ ] **Step 9: Run the full JVM suite one last time and commit**

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
./gradlew :app:testDebugUnitTest
```

Expected: PASS.

```bash
git add app/build.gradle.kts CLAUDE.md docs/screenshots/reminder-sheet.png docs/screenshots/notification.png
git commit -m "chore: bump to 0.12.0 and document the action sheet

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01NzvRRq4EwJ5C1UBAcn7P2Z"
```

- [ ] **Step 10: Build the release APK and install it on the emulator**

The debug build passing is not evidence about the one being served.

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/pesky-reminders-0.12.0.apk
adb shell dumpsys package com.peskyreminders.poc | grep versionName
```

Expected: `versionName=0.12.0`.

- [ ] **Step 11: Stage both names and republish**

```bash
mkdir -p dist
cp app/build/outputs/apk/release/pesky-reminders-0.12.0.apk dist/pesky-reminders-0.12.0.apk
cp app/build/outputs/apk/release/pesky-reminders-0.12.0.apk dist/pesky-reminders.apk
```

The version-pinned name is immutable once its URL is out; `pesky-reminders.apk` is the "latest" pointer.

**Reuse the running `cloudflared`** — a quick tunnel mints a new hostname on every restart, so leaving it alone keeps previously shared links alive. Check it is up:

```bash
curl -sI http://localhost:9999/pesky-reminders.apk | head -1
```

A 502 through the tunnel means the local server died, not the tunnel — restart `python3 -m http.server 9999` from `dist/`.

- [ ] **Step 12: Verify the download through the tunnel before reporting the URL**

```bash
shasum -a 256 dist/pesky-reminders.apk
curl -sL <tunnel-url>/pesky-reminders.apk | shasum -a 256
```

The two hashes must match. Only then report the URL.

---

## Self-Review

**Spec coverage.** Every spec section maps to a task: presets and the five-minute rung → Task 1; the sheet's Done pill, instant commit, dropped footer, unconditional clock times, the rename, `PeskySheet`'s test tag and the `ReminderActivity` callback → Task 2; the `contentIntent`, the activity rename, the manifest and the instrumented test → Task 3; version, docs and the emulator pass → Task 4. The spec's "Out of scope" items (the task list's reschedule path, undo, live-updating clock times) have no task, correctly.

**Type consistency.** `ReminderSheet`'s six parameters are declared in Task 2 and consumed in Task 2 Step 6 and Task 3 Step 3 with the same names. `openSheetIntent` is named identically in its definition and its two call sites. Test tags `done-button`, `preset-<minutes>`, `sheet-title`, `wheel-SNOOZE`, `SNOOZE-<index>` are used consistently across the test file and the sheet.

**Known ordering hazard.** Task 1 deliberately does not remove `landsAtAClockTime`, because `SnoozeSheet.kt` still calls it and the tree must compile between commits. Task 1 also patches two assertions in a test file that Task 2 replaces wholesale — churn accepted to keep every commit green under the pre-push hook.
