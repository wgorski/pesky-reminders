# "Snooze until" Absolute Preset Row — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a second row of chips to `ReminderSheet` that snoozes to an absolute time of day (08:00 / 13:00 / 20:00, the first four still in the future) rather than by a duration.

**Architecture:** A pure `SnoozeOptions.untilPresets(nowMillis)` generates the ladder using existing `TaskTime` calendar helpers. The tap commits **absolute epoch millis** through a new `onSnoozeUntil` callback into a new `Reminders.snoozeUntil`, never a duration — `ReminderActivity` snapshots its clock once at `setContent`, so a converted duration would drift for a sheet left open. Both chip rows sit under one `"Snooze"` label.

**Tech Stack:** Kotlin, Jetpack Compose, JUnit4 + Robolectric (JVM), AndroidJUnit4 (instrumented), Gradle wrapper 8.11.1.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-07-28-absolute-snooze-presets-design.md`.
- Rung hours are exactly **08:00, 13:00, 20:00**; candidates span **today + 3 days**; filter is **strictly after** `nowMillis`; take exactly **4**.
- `TaskTime` must stay pure — takes `nowMillis`, never a `Context`, never reads the clock.
- Calendar arithmetic only (`Calendar.add` / `TaskTime` helpers). Never fixed millisecond offsets — they break across DST.
- `Modifier.pressable(...)` goes **before** `.clip()`/`.background()` in the chain.
- Never schedule an alarm in the past — `setAlarmClock` fires it immediately.
- No confirm step: every chip commits on tap and holds no selected state.
- Section label string is exactly `"Snooze"` (replacing `"Snooze for"`). The wheel's `"…or dial it in"` is unchanged.
- Chips follow the existing `use24h` parameter; do not hard-force 24h.
- Tests pin `TimeZone` to UTC (and `Locale.US` wherever banding/labels are involved).
- Every environment command block starts with the `ANDROID_HOME`/`PATH` exports from CLAUDE.md.
- Version bump: **minor** (new user-facing behaviour), once for the whole branch.

---

### Task 1: The ladder — `SnoozeOptions.untilPresets`

Pure date maths, no UI, no Android. Fully covered by the JVM suite.

**Files:**
- Modify: `app/src/main/java/com/wgorski/peskyreminders/SnoozeOptions.kt`
- Test: `app/src/test/java/com/wgorski/peskyreminders/SnoozeOptionsTest.kt`

**Interfaces:**
- Consumes: `TaskTime.plusDays(millis, days)`, `TaskTime.withTimeOfDay(millis, hour)` (both already public; `withTimeOfDay` zeroes minute/second/millisecond).
- Produces: `SnoozeOptions.untilPresets(nowMillis: Long): List<Long>` — exactly 4 epoch-millis targets, ascending, all strictly greater than `nowMillis`. Also `SnoozeOptions.UNTIL_HOURS: List<Int>` and `SnoozeOptions.UNTIL_COUNT: Int`.

- [ ] **Step 1: Write the failing tests**

Add to `app/src/test/java/com/wgorski/peskyreminders/SnoozeOptionsTest.kt`. Add these imports at the top of the file:

```kotlin
import org.junit.Before
import java.util.Calendar
import java.util.TimeZone
```

Add inside the class, after the existing tests:

```kotlin
    // ---- the absolute-time ladder --------------------------------------------

    @Before fun fixTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    private fun at(
        year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0, second: Int = 0,
    ): Long = Calendar.getInstance().apply {
        set(year, month, day, hour, minute, second)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Saturday 25 July 2026. */
    private fun jul(day: Int, hour: Int, minute: Int = 0, second: Int = 0) =
        at(2026, Calendar.JULY, day, hour, minute, second)

    @Test fun early_morning_offers_all_three_of_today_then_tomorrow_morning() {
        assertEquals(
            listOf(jul(25, 8), jul(25, 13), jul(25, 20), jul(26, 8)),
            SnoozeOptions.untilPresets(jul(25, 5)),
        )
    }

    @Test fun mid_afternoon_skips_the_rungs_already_past() {
        assertEquals(
            listOf(jul(25, 20), jul(26, 8), jul(26, 13), jul(26, 20)),
            SnoozeOptions.untilPresets(jul(25, 14)),
        )
    }

    /** Past the last rung, today drops out entirely and tomorrow leads. */
    @Test fun after_the_evening_rung_the_row_starts_tomorrow() {
        assertEquals(
            listOf(jul(26, 8), jul(26, 13), jul(26, 20), jul(27, 8)),
            SnoozeOptions.untilPresets(jul(25, 20, 0, 1)),
        )
    }

    /**
     * "Strictly after" — a rung landing exactly on the clock is spent. Snoozing
     * to the instant that just arrived would leave the reminder due now.
     */
    @Test fun a_rung_exactly_on_the_clock_is_excluded() {
        val presets = SnoozeOptions.untilPresets(jul(25, 13))
        assertFalse("13:00 today is now, not later", presets.contains(jul(25, 13)))
        assertEquals(jul(25, 20), presets.first())
    }

    @Test fun it_always_offers_exactly_four_however_late_it_is() {
        listOf(jul(25, 0), jul(25, 7, 59), jul(25, 13), jul(25, 23, 59, 59)).forEach { now ->
            assertEquals(
                "four chips at ${now}",
                SnoozeOptions.UNTIL_COUNT,
                SnoozeOptions.untilPresets(now).size,
            )
        }
    }

    @Test fun every_target_is_in_the_future_and_ascending() {
        val now = jul(25, 14, 20)
        val presets = SnoozeOptions.untilPresets(now)
        assertTrue("all future", presets.all { it > now })
        assertEquals("ascending", presets.sorted(), presets)
    }

    @Test fun every_target_lands_on_a_whole_rung_hour() {
        SnoozeOptions.untilPresets(jul(25, 14, 20)).forEach { target ->
            assertTrue(
                "hour ${TaskTime.hourOf(target)} is not a rung",
                TaskTime.hourOf(target) in SnoozeOptions.UNTIL_HOURS,
            )
            assertEquals("minutes must be zeroed", 0, TaskTime.minuteOf(target))
        }
    }

    /**
     * Wall-clock, not fixed offsets. Europe/Warsaw springs forward at 02:00 on
     * Sunday 29 March 2026, so the day containing the change is 23 hours long —
     * a `+ 864e5` ladder would put the rungs an hour out for every day after it.
     */
    @Test fun the_rungs_hold_their_wall_clock_hour_across_a_dst_change() {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Warsaw"))
        val saturdayEvening = at(2026, Calendar.MARCH, 28, 21, 0)
        SnoozeOptions.untilPresets(saturdayEvening).forEach { target ->
            assertTrue(
                "hour ${TaskTime.hourOf(target)} drifted",
                TaskTime.hourOf(target) in SnoozeOptions.UNTIL_HOURS,
            )
        }
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
./gradlew :app:testDebugUnitTest --tests 'com.wgorski.peskyreminders.SnoozeOptionsTest'
```

Expected: compilation failure — `Unresolved reference 'untilPresets'`, `'UNTIL_COUNT'`, `'UNTIL_HOURS'`.

- [ ] **Step 3: Implement the ladder**

In `app/src/main/java/com/wgorski/peskyreminders/SnoozeOptions.kt`, add at the end of the object (after `chipUnit`):

```kotlin
    // ---- the absolute-time ladder --------------------------------------------

    /**
     * The times of day the "Snooze" row can land on, in the order they occur.
     *
     * Morning, afternoon, evening. The 8 here is *not* the same decision as
     * `TaskTime`'s own morning hour, which only governs where `defaultDue` lands
     * after 21:00 — they coincide today and are free to diverge, so this does not
     * borrow that constant.
     */
    val UNTIL_HOURS = listOf(8, 13, 20)

    /** Chips in the row, matching the four duration chips above it. */
    const val UNTIL_COUNT = 4

    /**
     * Four days of candidates. Three would strictly do — the worst case is a tap
     * just after the evening rung, which consumes today entirely and needs
     * tomorrow's three plus the next day's morning — so the fourth day is margin
     * that is never displayed.
     */
    private const val UNTIL_DAYS_AHEAD = 3

    /**
     * The next [UNTIL_COUNT] rung times strictly after [nowMillis], ascending.
     *
     * Today is not special-cased: it contributes all three rungs like any other
     * day, so at 06:00 the row opens with today's 08:00.
     *
     * Built with calendar arithmetic, so a rung keeps its wall-clock hour across
     * a DST change — the day containing a spring-forward is 23 hours long, and a
     * fixed-millisecond ladder would drift by an hour from then on.
     */
    fun untilPresets(nowMillis: Long): List<Long> =
        (0..UNTIL_DAYS_AHEAD)
            .flatMap { dayOffset ->
                val day = TaskTime.plusDays(nowMillis, dayOffset)
                UNTIL_HOURS.map { TaskTime.withTimeOfDay(day, it) }
            }
            .filter { it > nowMillis }
            .take(UNTIL_COUNT)
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
./gradlew :app:testDebugUnitTest --tests 'com.wgorski.peskyreminders.SnoozeOptionsTest'
```

Expected: PASS. Then run the whole suite to be sure the new `@Before` timezone pin did not disturb a neighbour:

```bash
./gradlew :app:testDebugUnitTest
```

Expected: PASS, 174 + 8 = 182 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/wgorski/peskyreminders/SnoozeOptions.kt \
        app/src/test/java/com/wgorski/peskyreminders/SnoozeOptionsTest.kt
git commit -m "feat: the absolute-time snooze ladder

08:00/13:00/20:00 across today and the next three days, filtered to those
strictly after now, first four taken. Calendar arithmetic so a rung holds
its wall-clock hour across a DST change."
```

---

### Task 2: `Reminders.snoozeUntil`

The commit path. Independent of Tasks 1 and 3 — it takes a timestamp and does not care where it came from.

**Files:**
- Modify: `app/src/main/java/com/wgorski/peskyreminders/Reminders.kt` (add after `snooze`, around line 304)
- Test: `app/src/androidTest/java/com/wgorski/peskyreminders/ReminderModelTest.kt`

**Interfaces:**
- Consumes: `TaskStore.find`, `TaskStore.replace`, `ReminderNotifier.cancel`, `ReminderNotifier.isShowing`, `ReminderScheduler.schedule`, `ReminderScheduler.cancel`, `ReminderScheduler.cancelNag`, the private `notify(context, taskId)` helper, `Task.repeats`, `Task.slotMillis` — all already used by `snooze` and `update`.
- Produces: `Reminders.snoozeUntil(context: Context, taskId: Int, atMillis: Long)`.

- [ ] **Step 1: Write the failing tests**

Add to `app/src/androidTest/java/com/wgorski/peskyreminders/ReminderModelTest.kt`, after `snooze_clears_and_reschedules_five_minutes_out`:

```kotlin
    @Test fun snoozing_until_a_time_arms_that_exact_alarm() {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        TaskStore.clear(context)
        taskId = TaskStore.add(
            context, "Buy milk", System.currentTimeMillis() - 1_000L, Repeat.ONCE,
        ).id
        deliver(ReminderContract.ACTION_FIRE)
        assertNotNull("precondition: posted", active())

        val target = System.currentTimeMillis() + 3 * 60 * 60 * 1000L
        Reminders.snoozeUntil(context, taskId, target)
        Thread.sleep(300)

        assertNull("a future target clears the notification", active())
        val next = alarmManager.nextAlarmClock
        assertNotNull("a future target must arm an alarm", next)
        val delta = Math.abs(next!!.triggerTime - target)
        assertTrue("alarm on the target, not an offset (delta=$delta ms)", delta < 2_000L)
        assertEquals(
            "the stored due time is the target exactly",
            target,
            TaskStore.find(context, taskId)!!.dueMillis,
        )
    }

    /**
     * The sheet can sit open across a rung: opened at 12:59, "13:00" tapped at
     * 13:00:30. Arming setAlarmClock in the past fires it immediately, so this
     * takes the same line as a task created with a past time — pester me now.
     */
    @Test fun snoozing_until_a_time_already_past_keeps_nagging_and_arms_nothing() {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        TaskStore.clear(context)
        taskId = TaskStore.add(
            context, "Buy milk", System.currentTimeMillis() - 1_000L, Repeat.ONCE,
        ).id
        deliver(ReminderContract.ACTION_FIRE)
        assertNotNull("precondition: posted", active())

        val past = System.currentTimeMillis() - 60_000L
        Reminders.snoozeUntil(context, taskId, past)
        Thread.sleep(300)

        assertNotNull("a past target must leave the reminder on screen", active())
        val next = alarmManager.nextAlarmClock
        if (next != null) {
            assertTrue(
                "must not arm an alarm in the past (was ${next.triggerTime})",
                next.triggerTime > System.currentTimeMillis(),
            )
        }
    }

    /** A snooze moves one firing, not the whole cycle. */
    @Test fun snoozing_a_repeater_until_a_time_keeps_its_slot_as_the_anchor() {
        TaskStore.clear(context)
        val slot = System.currentTimeMillis() - 1_000L
        taskId = TaskStore.add(context, "Water the plants", slot, Repeat.DAILY).id
        deliver(ReminderContract.ACTION_FIRE)

        val target = System.currentTimeMillis() + 30 * 60 * 1000L
        Reminders.snoozeUntil(context, taskId, target)
        Thread.sleep(300)

        val task = TaskStore.find(context, taskId)!!
        assertEquals("fires at the target", target, task.dueMillis)
        assertEquals("but the recurring slot is preserved", slot, task.anchorMillis)
        assertEquals("and slotMillis reads the anchor", slot, task.slotMillis)
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

The emulator must be running. If it is not, boot it (do not ask first):

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
emulator -avd pesky -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect &
adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do sleep 3; done
adb shell wm dismiss-keyguard
```

Then:

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.wgorski.peskyreminders.ReminderModelTest
```

Expected: compilation failure — `Unresolved reference 'snoozeUntil'`.

- [ ] **Step 3: Implement `snoozeUntil`**

In `app/src/main/java/com/wgorski/peskyreminders/Reminders.kt`, insert directly after the closing brace of `snooze` (currently line 304):

```kotlin
    /**
     * Push a reminder to an absolute time rather than by a duration.
     *
     * The counterpart to [snooze], behind the sheet's time chips. The target
     * arrives already computed and is stored verbatim, which is the whole point:
     * converting it to minutes at composition time would drift by however long
     * the user takes to tap, and `ReminderActivity` snapshots its clock once at
     * `setContent` and never refreshes it.
     *
     * The anchor is kept exactly as [snooze] keeps it — a snooze moves one
     * firing, not the cycle, so a daily 09:00 pushed to tomorrow morning still
     * leaves the day after at 09:00.
     */
    fun snoozeUntil(context: Context, taskId: Int, atMillis: Long) {
        val task = TaskStore.find(context, taskId) ?: return
        val now = System.currentTimeMillis()
        // Read before anything is cancelled — the past branch needs to know
        // whether there was a notification to put back.
        val showing = ReminderNotifier.isShowing(context, taskId)
        ReminderScheduler.cancelNag(context, taskId)

        val next = task.copy(
            dueMillis = atMillis,
            anchorMillis = if (task.repeats) task.slotMillis else null,
        )
        TaskStore.replace(context, next)

        if (atMillis > now) {
            ReminderNotifier.cancel(context, taskId)
            ReminderScheduler.schedule(context, next)
        } else {
            // The sheet sat open across the rung it was offering. Never arm
            // setAlarmClock in the past — it fires at once. Take the line
            // `create` takes for a past due time instead: pester me now, so the
            // reminder stays overdue with the notification live on it.
            ReminderScheduler.cancel(context, taskId)
            if (showing) notify(context, taskId)
        }
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.wgorski.peskyreminders.ReminderModelTest
```

Expected: PASS, 60 tests (57 + 3).

Note `ReminderModelTest` calls `TaskStore.clear()`, so this wipes the task list on the device, and the connected-test task uninstalls both APKs when it finishes.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/wgorski/peskyreminders/Reminders.kt \
        app/src/androidTest/java/com/wgorski/peskyreminders/ReminderModelTest.kt
git commit -m "feat: Reminders.snoozeUntil, an absolute-time snooze

Stores the target verbatim rather than converting to a duration, so the
landing time cannot drift from what the chip promised. A target already
past arms nothing and leaves the notification live, the same line create
takes for a past due time."
```

---

### Task 3: The chip row, and both hosts

The sheet's signature changes, which breaks both call sites at once — so the row and its two hosts land together. A reviewer cannot accept a sheet that does not compile.

**Files:**
- Modify: `app/src/main/java/com/wgorski/peskyreminders/ui/ReminderSheet.kt`
- Modify: `app/src/main/java/com/wgorski/peskyreminders/ReminderActivity.kt:53-70`
- Modify: `app/src/main/java/com/wgorski/peskyreminders/ui/PeskyApp.kt:119-135`
- Test: `app/src/test/java/com/wgorski/peskyreminders/ui/ReminderSheetTest.kt`

**Interfaces:**
- Consumes: `SnoozeOptions.untilPresets(nowMillis)` and `SnoozeOptions.UNTIL_COUNT` from Task 1; `Reminders.snoozeUntil(context, taskId, atMillis)` from Task 2; existing `TaskTime.formatTime(millis, use24h)` → `"20:00"` / `"8:00 PM"` and `TaskTime.formatDay(millis, nowMillis)` → `"Today"` / `"Tomorrow"` / `"Thu"`.
- Produces: `ReminderSheet(..., onSnoozeUntil: (atMillis: Long) -> Unit)` — a new **required** parameter placed after `onSnooze`. Chip test tags are `until-0` … `until-3`.

- [ ] **Step 1: Write the failing tests**

In `app/src/test/java/com/wgorski/peskyreminders/ui/ReminderSheetTest.kt`:

First, update the harness. Replace the `snoozed`/`show()` block (lines 53-68) with:

```kotlin
    private var snoozed: Int? = null
    private var snoozedUntil: Long? = null
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
                onSnoozeUntil = { snoozedUntil = it },
            )
        }
    }
```

Then change the existing label assertion at line 113 from `"Snooze for"` to `"Snooze"`:

```kotlin
    @Test fun both_ways_in_are_labelled() {
        show()
        compose.onNodeWithText("Snooze").assertIsDisplayed()
        compose.onNodeWithText("…or dial it in").assertIsDisplayed()
    }
```

Then add these tests at the end of the class. The pinned clock is Saturday 25 July 2026 14:20 UTC with `use24h = false`, so the four targets are today 20:00, tomorrow 08:00, 13:00 and 20:00:

```kotlin
    // ---- the absolute-time chips ---------------------------------------------

    private fun tapUntil(index: Int) = act(compose.onNodeWithTag("until-$index"))

    @Test fun four_absolute_times_are_offered() {
        show()
        repeat(SnoozeOptions.UNTIL_COUNT) {
            compose.onNodeWithTag("until-$it").assertIsDisplayed()
        }
    }

    /** Big line is the clock time, small line says which day. */
    @Test fun each_chip_reads_as_a_time_over_a_day() {
        show()
        listOf(
            "8:00 PM" to "Today",
            "8:00 AM" to "Tomorrow",
            "1:00 PM" to "Tomorrow",
            "8:00 PM" to "Tomorrow",
        ).forEachIndexed { index, (time, day) ->
            compose.onNodeWithTag("until-$index")
                .assertTextEquals(time, day)
        }
    }

    /** The part-of-day words drive generation only; they are never shown. */
    @Test fun the_chips_do_not_name_the_part_of_day() {
        show()
        listOf("Morning", "Afternoon", "Evening").forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
    }

    @Test fun tapping_a_chip_commits_that_exact_time_in_one_tap() {
        show()
        tapUntil(1)
        assertEquals(
            "commits the absolute target, not a duration",
            SnoozeOptions.untilPresets(now)[1],
            snoozedUntil,
        )
        assertNull("and does not go through the duration path", snoozed)
    }

    @Test fun every_chip_commits_its_own_target() {
        repeat(SnoozeOptions.UNTIL_COUNT) { index ->
            snoozedUntil = null
            show()
            tapUntil(index)
            assertEquals(SnoozeOptions.untilPresets(now)[index], snoozedUntil)
        }
    }

    /** Nothing in this sheet holds a selection, these chips included. */
    @Test fun the_absolute_chips_hold_no_selection() {
        show()
        tapUntil(0)
        compose.onNodeWithTag("until-0").assertIsDisplayed()
        compose.onNodeWithTag("snooze-button").assertDoesNotExist()
    }
```

No new imports are needed. `assertDoesNotExist()` is a member of
`SemanticsNodeInteraction`, not an extension — the file already calls it at line 120
without an import, and `androidx.compose.ui.test.assertDoesNotExist` does not exist as an
importable symbol, so adding that import fails to compile.

`assertTextEquals(time, day)` works on the chip's tagged node because `pressable` ends in
`Modifier.clickable`, which sets `mergeDescendants = true` — so the `Column`'s two `Text`
children are collected into the single `until-N` node, in order. `DeleteTaskSheetTest:81`
relies on the same behaviour.

- [ ] **Step 2: Run the tests to verify they fail**

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
./gradlew :app:testDebugUnitTest --tests 'com.wgorski.peskyreminders.ui.ReminderSheetTest'
```

Expected: compilation failure — `No value passed for parameter 'onSnoozeUntil'`.

- [ ] **Step 3: Add the parameter, the label, and the row**

In `app/src/main/java/com/wgorski/peskyreminders/ui/ReminderSheet.kt`:

**3a.** Add the parameter to the signature, after `onSnooze`:

```kotlin
fun ReminderSheet(
    taskName: String,
    nowMillis: Long,
    use24h: Boolean,
    onDismiss: () -> Unit,
    onDone: () -> Unit,
    onSnooze: (minutes: Int) -> Unit,
    onSnoozeUntil: (atMillis: Long) -> Unit,
) {
```

**3b.** Replace the whole duration-chip `Column` (currently lines 67-76) with:

```kotlin
        // Both rows are one choice offered two ways — how long from now, or what
        // time to land on — so they share a heading. "Snooze for" could not cover
        // the second row anyway: *snooze for 20:00* is wrong, which is what makes
        // the label the neutral "Snooze" rather than a matched pair.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Snooze", style = PeskyType.FieldLabel)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SnoozeOptions.PRESETS.forEach { preset ->
                    PresetChip(minutes = preset, modifier = Modifier.weight(1f)) {
                        onSnooze(preset)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SnoozeOptions.untilPresets(nowMillis).forEachIndexed { index, target ->
                    UntilChip(
                        target = target,
                        nowMillis = nowMillis,
                        use24h = use24h,
                        index = index,
                        modifier = Modifier.weight(1f),
                    ) { onSnoozeUntil(target) }
                }
            }
        }
```

**3c.** Add the chip composable at the end of the file, after `PresetChip`:

```kotlin
/**
 * A chip that lands on a time of day rather than after a duration.
 *
 * Same geometry as [PresetChip] and the same hierarchy — big line is the value,
 * small line qualifies it. The part-of-day names the ladder is built from are
 * deliberately absent: once the chip reads "13:00" the word "afternoon" adds
 * nothing, and four chips only get ~81dp each, which "Afternoon" at 15sp very
 * nearly fills on its own.
 *
 * Both labels come from [TaskTime], so the chip cannot disagree with the wheel
 * rows below it about how a time is written.
 */
@Composable
private fun UntilChip(
    target: Long,
    nowMillis: Long,
    use24h: Boolean,
    index: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .testTag("until-$index")
            .pressable(scale = 0.96f, onClick = onClick)
            .clip(R12)
            .background(PeskyColors.Field)
            .border(1.dp, PeskyColors.FieldBorder, R12)
            .padding(vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            TaskTime.formatTime(target, use24h),
            fontFamily = DmSans,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = PeskyColors.Text,
            maxLines = 1,
        )
        Text(
            TaskTime.formatDay(target, nowMillis),
            fontFamily = DmSans,
            fontSize = 10.sp,
            color = PeskyColors.TextDim,
            maxLines = 1,
        )
    }
}
```

**3d.** Update the KDoc on `ReminderSheet` — the second paragraph currently claims the sheet only offers durations. Replace the sentence "finish it, or push it by any duration." with:

```kotlin
 * finish it, push it by a duration, or push it to a time of day.
```

- [ ] **Step 4: Wire both hosts**

In `app/src/main/java/com/wgorski/peskyreminders/ReminderActivity.kt`, add after the `onSnooze` lambda (which ends around line 70):

```kotlin
                onSnoozeUntil = { atMillis ->
                    Reminders.snoozeUntil(this, id, atMillis)
                    close()
                },
```

In `app/src/main/java/com/wgorski/peskyreminders/ui/PeskyApp.kt`, add after the `onSnooze` lambda (which ends around line 135):

```kotlin
                        onSnoozeUntil = { atMillis ->
                            Reminders.snoozeUntil(context, id, atMillis)
                            now = System.currentTimeMillis()
                            remindTaskId = null
                        },
```

Match whatever the neighbouring `onSnooze` lambda does on the way out — read it first and mirror it, including the `now =` refresh and the sheet dismissal.

- [ ] **Step 5: Run the tests to verify they pass**

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
./gradlew :app:testDebugUnitTest
```

Expected: PASS, 182 + 7 = 189 tests.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/wgorski/peskyreminders/ui/ReminderSheet.kt \
        app/src/main/java/com/wgorski/peskyreminders/ReminderActivity.kt \
        app/src/main/java/com/wgorski/peskyreminders/ui/PeskyApp.kt \
        app/src/test/java/com/wgorski/peskyreminders/ui/ReminderSheetTest.kt
git commit -m "feat: a second snooze row that lands on a time of day

Four chips reading time-over-day under one \"Snooze\" label shared with the
duration row. Both hosts wired; the sheet still takes no host parameter."
```

---

### Task 4: Verify on the device, document, publish

A clean compile and a green JVM suite are not evidence the row renders or that its taps land. This task is not done until a screenshot shows it.

**Files:**
- Modify: `app/build.gradle.kts:15,18` (versionCode/versionName)
- Modify: `CLAUDE.md` (the `ReminderSheet.kt` layout line, and the conventions list)
- Modify: `README.md` (the tour, wherever it describes the action panel)

- [ ] **Step 1: Bump the version**

In `app/build.gradle.kts`, `versionCode = 20` → `21`, `versionName = "0.14.0"` → `"0.15.0"`. Minor, because this is new user-facing behaviour.

- [ ] **Step 2: Build, install and drive it**

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop com.wgorski.peskyreminders
adb shell am start -n com.wgorski.peskyreminders/.MainActivity
```

Seed a task list and raise the sheet from an **overdue** row (the only band that opens the panel). Seeding: write `pesky_tasks.xml` into
`/data/data/com.wgorski.peskyreminders/shared_prefs/` and chown it to the app uid — **do not name the shell variable `UID`**, it is readonly in bash and the chown silently uses the host's 501, which leaves the app reading an empty list.

```bash
adb exec-out screencap -p > /tmp/snooze-until.png
```

Look at it. Confirm: two chip rows under a single "Snooze" label, four time chips, the day line legible, and the rows not colliding with the wheel below.

- [ ] **Step 3: Check the two cases the layout can fail**

The 15sp line is the tight one at ~81dp per chip.

```bash
# 24h — what the user's own device shows
adb shell settings put system time_12_24 24
adb shell am force-stop com.wgorski.peskyreminders
adb shell am start -n com.wgorski.peskyreminders/.MainActivity
adb exec-out screencap -p > /tmp/snooze-until-24h.png

# the font scale that broke TaskSheet
adb shell settings put system font_scale 1.3
adb shell am force-stop com.wgorski.peskyreminders
adb shell am start -n com.wgorski.peskyreminders/.MainActivity
adb exec-out screencap -p > /tmp/snooze-until-fontscale.png
adb shell settings put system font_scale 1.0
```

Both screenshots must show unclipped labels. If 12h (`8:00 AM`, ~68dp) clips at 1.3, drop the big line to 14sp rather than truncating — the day line has room to spare and the chip must not lie about the time.

- [ ] **Step 4: Confirm a tap actually commits**

Tap a time chip, then screenshot the list. The row must have moved to the band matching the chip's time, and the notification must be gone.

```bash
adb exec-out screencap -p > /tmp/snooze-until-after.png
```

- [ ] **Step 5: Update the docs**

In `CLAUDE.md`:
- the layout tree's `ReminderSheet.kt` line — it currently reads
  `# Done, 15/30/1h/3h chips, 5 min–72 hr wheel — notification + overdue tap`.
  Add the time chips to it.
- add a convention bullet near the existing snooze ones:

```markdown
- **The time chips commit an absolute millis, the duration chips a count of
  minutes.** They are two callbacks on purpose. Converting a time to
  minutes-from-now at composition time drifts by however long the user takes to
  tap, and `ReminderActivity` snapshots its clock once at `setContent` — a sheet
  left open five minutes would land "Tomorrow 08:00" at 08:05. The chips also do
  not name the part of day they came from: once one reads `13:00`, "afternoon"
  adds nothing, and four chips only get ~81dp each.
```

In `README.md`, mention the second row wherever the tour covers the action panel.

- [ ] **Step 6: Full suite, both tiers**

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest
```

Expected: 189 JVM, 60 instrumented, all green. Run them one at a time — two concurrent Gradle builds on this directory corrupt the incremental compile state and produce phantom `Unresolved reference` failures.

- [ ] **Step 7: Commit**

```bash
git add app/build.gradle.kts CLAUDE.md README.md
git commit -m "chore: bump to 0.15.0 and document the time chips"
```

- [ ] **Step 8: Publish**

```bash
./gradlew :app:assembleRelease
cp app/build/outputs/apk/release/pesky-reminders-0.15.0.apk dist/pesky-reminders-0.15.0.apk
cp app/build/outputs/apk/release/pesky-reminders-0.15.0.apk dist/pesky-reminders.apk
```

Install the **release** APK on the emulator and confirm `versionName` reads 0.15.0 — the debug build passing says nothing about the one being served. Reuse the running `cloudflared` and `python3 -m http.server 9999` from `dist/`; a restarted quick tunnel mints a new hostname and kills every link already shared. Download through the tunnel and hash-match against the staged file before reporting the URL.

---

## Self-Review

**Spec coverage:**

| Spec section | Task |
|---|---|
| The ladder (08:00/13:00/20:00, 4 days, strictly after, take 4) | 1 |
| Exactly four returned | 1 (`it_always_offers_exactly_four_however_late_it_is`) |
| Today not special-cased | 1 (`early_morning_offers_all_three_of_today_then_tomorrow_morning`) |
| Chips: `formatTime` over `formatDay`, no part-of-day words | 3 |
| `PresetChip` geometry reused | 3 |
| `use24h` followed, not forced | 3 (harness uses `use24h = false`; Task 4 Step 3 checks 24h on device) |
| Single "Snooze" label; existing string renamed | 3 |
| Absolute-millis commit, new callback + `Reminders.snoozeUntil` | 2, 3 |
| Anchor preserved | 2 (`snoozing_a_repeater_until_a_time_keeps_its_slot_as_the_anchor`) |
| Past target → pester me now, arm nothing | 2 (`snoozing_until_a_time_already_past_keeps_nagging_and_arms_nothing`) |
| Both hosts wired, no host parameter | 3 |
| No confirm step / no held selection | 3 (`the_absolute_chips_hold_no_selection`) |
| Height headroom | 4 (screenshot + font scale 1.3) |
| DST correctness | 1 (`the_rungs_hold_their_wall_clock_hour_across_a_dst_change`) |
| Minor version bump | 4 |

No gaps.

**Placeholder scan:** every code step carries real code; no TBD/TODO; no "similar to Task N".

**Type consistency:** `untilPresets(Long): List<Long>`, `UNTIL_HOURS: List<Int>`, `UNTIL_COUNT: Int`, `snoozeUntil(Context, Int, Long)`, `onSnoozeUntil: (Long) -> Unit`, tags `until-0`…`until-3` — used identically in Tasks 1, 2 and 3.

**One judgement left to the implementer:** Task 4 Step 3 says to drop the big line to 14sp if 12h format clips at font scale 1.3. That is a real fork, but it is contingent on a measurement that cannot be made before the screenshot exists, and the fallback is specified.
