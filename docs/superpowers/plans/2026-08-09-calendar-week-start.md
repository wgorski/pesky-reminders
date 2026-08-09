# Calendar Week Start Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the task sheet's month grid start on the locale's first day of week, the same day the list's THIS WEEK / NEXT WEEK bands already cut on.

**Architecture:** `TaskTime.startOfWeek` already asks `Calendar.getFirstDayOfWeek()`. Two spots ignore it and hardcode Sunday: `TaskTime.leadingBlanks` (`DAY_OF_WEEK - 1`) and a literal `listOf("S","M","T","W","T","F","S")` in `CalendarPicker`. Point both at the locale, and put the header letters behind a single pure function so the letter that leads and the column the 1st lands in cannot disagree.

**Tech Stack:** Kotlin, Jetpack Compose, JUnit4 + Robolectric (JVM suite), Gradle wrapper 8.11.1, AGP 8.7.3.

**Spec:** `docs/superpowers/specs/2026-08-09-calendar-week-start-design.md`

## Global Constraints

- **`TaskTime` must stay pure.** No `Context` parameter, no clock reads. `firstDayOfWeek` comes off the default `Locale` via `Calendar`, exactly as `startOfWeek` reads it today.
- **Calendar field arithmetic, never fixed millisecond offsets.** A week containing a DST change is 167 or 169 hours long.
- **No new user setting.** Nothing is added to `Settings.kt`. The 12/24h format keeps following the device's system setting via `DateFormat.is24HourFormat`.
- **Day letters stay English, merely rotated.** `WEEKDAYS` is hardcoded English; localising day names is out of scope.
- **Version bumps once for the whole branch**, in Task 3 only: `versionName` `0.22.0` → `0.23.0`, `versionCode` `28` → `29`.
- **Every command block that uses `adb`, `emulator`, `sdkmanager`, or `./gradlew` must be prefixed with:**
  ```bash
  export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
  export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
  ```
- **Use the Gradle wrapper** (`./gradlew`), never the system `gradle`.
- The JVM suite runs automatically after every `.kt` edit via the `PostToolUse` hook; it only interrupts on failure. Run it explicitly anyway at the steps that say to.

---

### Task 1: `TaskTime` asks the locale where a week starts

**Files:**
- Modify: `app/src/main/java/com/wgorski/peskyreminders/TaskTime.kt:248-250` (`leadingBlanks`), and add `weekdayInitials` beside it
- Modify: `app/src/main/java/com/wgorski/peskyreminders/TaskTime.kt:257-267` (`startOfWeek` KDoc)
- Test: `app/src/test/java/com/wgorski/peskyreminders/TaskTimeTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces:
  - `TaskTime.leadingBlanks(monthStartMillis: Long): Int` — unchanged signature, locale-aware behaviour.
  - `TaskTime.weekdayInitials(): List<String>` — **new**. Exactly seven single-character strings, rotated so index 0 is the locale's first day of week. Task 2 renders this.

- [ ] **Step 1: Write the failing tests**

Add both tests to `app/src/test/java/com/wgorski/peskyreminders/TaskTimeTest.kt`. Put them directly after the existing `calendar_grid_lines_up_with_the_month` test (around line 292). `Locale` is already imported (line 8) and `@Before` already pins `Locale.US`, so each test starts from a known first-day and may change it freely.

```kotlin
    @Test fun the_grid_starts_on_the_locales_first_day_of_the_week() {
        val august = TaskTime.monthStart(now, 1)

        // 1 August 2026 is a Saturday. US weeks open on Sunday, so it is the
        // seventh column — six blanks before it.
        Locale.setDefault(Locale.US)
        assertEquals(6, TaskTime.leadingBlanks(august))
        assertEquals(listOf("S", "M", "T", "W", "T", "F", "S"), TaskTime.weekdayInitials())

        // UK weeks open on Monday, so the same Saturday moves one column left.
        Locale.setDefault(Locale.UK)
        assertEquals(5, TaskTime.leadingBlanks(august))
        assertEquals(listOf("M", "T", "W", "T", "F", "S", "S"), TaskTime.weekdayInitials())
    }

    /**
     * The header letters and the blank count are two halves of one claim, and a
     * later edit could rotate one without the other. Pin them against each
     * other across a whole week: a day's blank count *is* the column it lands
     * in, so the header letter above that column must be that day's own initial.
     *
     * Checked column-by-column rather than by naming days, so the two "S" and
     * two "T" in the row cannot make a wrong rotation pass.
     */
    @Test fun the_grids_header_agrees_with_its_blanks() {
        // Sunday 1 February 2026 through Saturday the 7th — one full week.
        val week = (0..6).map { at(2026, Calendar.FEBRUARY, 1 + it) }
        // Far enough back that formatDay spells the weekday out ("Sun 1 Feb").
        val longAgo = at(2026, Calendar.JANUARY, 1)

        listOf(Locale.US, Locale.UK).forEach { locale ->
            Locale.setDefault(locale)
            val initials = TaskTime.weekdayInitials()
            week.forEach { day ->
                val expected = TaskTime.formatDay(day, longAgo).take(1)
                assertEquals(
                    "$locale, day $day",
                    expected,
                    initials[TaskTime.leadingBlanks(day)],
                )
            }
        }
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
./gradlew :app:testDebugUnitTest --tests 'com.wgorski.peskyreminders.TaskTimeTest'
```

Expected: **compilation failure** — `Unresolved reference: weekdayInitials`. That is the correct first failure; the tests cannot run until Step 3 adds the function.

- [ ] **Step 3: Make `leadingBlanks` locale-aware and add `weekdayInitials`**

In `TaskTime.kt`, replace the whole `leadingBlanks` declaration (lines 248-250):

```kotlin
    /** Blank cells before the 1st, with Sunday as the first column. */
    fun leadingBlanks(monthStartMillis: Long): Int =
        cal(monthStartMillis).get(Calendar.DAY_OF_WEEK) - 1
```

with:

```kotlin
    /**
     * Blank cells before the 1st, counted from the locale's first day of week —
     * the same day [startOfWeek] cuts on, so the grid and the list's THIS WEEK /
     * NEXT WEEK bands agree about where a week begins.
     *
     * Field arithmetic rather than a subtraction of millis: a week containing a
     * DST change is 167 or 169 hours long, and dividing that by [DAY_MILLIS]
     * could put the 1st in the wrong column.
     */
    fun leadingBlanks(monthStartMillis: Long): Int = cal(monthStartMillis).run {
        (get(Calendar.DAY_OF_WEEK) - firstDayOfWeek + 7) % 7
    }

    /**
     * The grid's seven column headers, rotated so the first is the locale's
     * first day of week.
     *
     * This is the header's only source, which is what stops the letters
     * disagreeing with [leadingBlanks] about which day leads — the same
     * reasoning that has the snooze chip labels come from here rather than
     * being written out at the call site. Shares [WEEKDAYS] with [formatDay].
     *
     * English initials, merely rotated: [WEEKDAYS] is English and localising
     * day names is a separate job.
     */
    fun weekdayInitials(): List<String> {
        val first = Calendar.getInstance().firstDayOfWeek - 1
        return List(7) { WEEKDAYS[(first + it) % 7].take(1) }
    }
```

- [ ] **Step 4: Correct the `startOfWeek` KDoc**

The last sentence of `startOfWeek`'s KDoc (`TaskTime.kt:262-263`) is now false. Replace:

```kotlin
     * the user's calendar, not ours. Note the month grid in the task sheet is still
     * hardcoded Sunday-first; the two can disagree.
```

with:

```kotlin
     * the user's calendar, not ours. The month grid asks the same question through
     * [leadingBlanks] and [weekdayInitials], so the two cannot disagree.
```

- [ ] **Step 5: Run the tests to verify they pass**

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
./gradlew :app:testDebugUnitTest --tests 'com.wgorski.peskyreminders.TaskTimeTest'
```

Expected: PASS, including the pre-existing `calendar_grid_lines_up_with_the_month` — 1 August 2026 is a Saturday and `@Before` pins `Locale.US`, so its `assertEquals(6, ...)` is unchanged. If that one now fails, `leadingBlanks` has the modulo backwards.

- [ ] **Step 6: Run the whole JVM suite**

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
./gradlew :app:testDebugUnitTest
```

Expected: all green (236 tests before this task's two additions).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/wgorski/peskyreminders/TaskTime.kt \
        app/src/test/java/com/wgorski/peskyreminders/TaskTimeTest.kt
git commit -m "$(cat <<'EOF'
feat: the grid's week starts where the locale says, not on Sunday

leadingBlanks hardcoded Sunday while startOfWeek — and so the THIS WEEK /
NEXT WEEK bands — asked the locale. Point both at the same answer, and put
the header letters behind weekdayInitials so the letter that leads cannot
drift from the column the 1st lands in.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01VjkBkEhQiGaTYdCHwauHvJ
EOF
)"
```

---

### Task 2: the grid draws the rotated header

**Files:**
- Modify: `app/src/main/java/com/wgorski/peskyreminders/ui/TimePickers.kt:177-190` (the header `Row` in `CalendarPicker`)
- Test: `app/src/test/java/com/wgorski/peskyreminders/ui/AddTaskSheetTest.kt`
- Test: `app/src/test/java/com/wgorski/peskyreminders/ui/EditTaskSheetTest.kt` (locale pinning only)

**Interfaces:**
- Consumes: `TaskTime.weekdayInitials(): List<String>` from Task 1.
- Produces: seven header cells tagged `dow-0` … `dow-6`, left to right.

- [ ] **Step 1: Pin the locale in both sheet tests**

Both classes host `CalendarPicker`, which now depends on the default `Locale`. Left unpinned they would pass or fail according to whatever locale the machine happens to run in.

In `app/src/test/java/com/wgorski/peskyreminders/ui/AddTaskSheetTest.kt`, add `import java.util.Locale` beside the existing `java.util.TimeZone` import (line 30), then replace lines 45-47:

```kotlin
    @Before fun fixTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }
```

with:

```kotlin
    /**
     * Pin the zone so every label below is a fixed string, and the locale
     * because the calendar grid asks it which day a week starts on.
     */
    @Before fun fixTimeZoneAndLocale() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
    }
```

Make the identical change in `app/src/test/java/com/wgorski/peskyreminders/ui/EditTaskSheetTest.kt` — same `import java.util.Locale` beside line 32, same replacement at lines 53-55.

- [ ] **Step 2: Write the failing test**

Add to `AddTaskSheetTest`, next to the existing `tap("Calendar")` tests (after the one asserting `"July 2026"` appears, around line 226). Add `import androidx.compose.ui.test.assertTextEquals` to the imports.

```kotlin
    /**
     * The visible half of the week-start rule. [TaskTime] having the right
     * answer buys nothing if the grid still draws its old hardcoded literal,
     * and no other test looks at the header row at all.
     */
    @Test fun the_calendar_header_starts_on_the_locales_first_day() {
        Locale.setDefault(Locale.UK)
        showSheet()
        tap("Calendar")

        compose.onNodeWithTag("dow-0").assertTextEquals("M")
        compose.onNodeWithTag("dow-1").assertTextEquals("T")
        compose.onNodeWithTag("dow-5").assertTextEquals("S")
        compose.onNodeWithTag("dow-6").assertTextEquals("S")
    }
```

`Locale.setDefault` must come **before** `showSheet()` — the header is read during composition.

- [ ] **Step 3: Run the test to verify it fails**

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
./gradlew :app:testDebugUnitTest --tests 'com.wgorski.peskyreminders.ui.AddTaskSheetTest'
```

Expected: FAIL — "Reason: Expected exactly '1' node but could not find any node that satisfies: (TestTag = 'dow-0')". The tags do not exist yet.

- [ ] **Step 4: Point the header at `weekdayInitials`**

In `TimePickers.kt`, replace lines 177-190:

```kotlin
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            listOf("S", "M", "T", "W", "T", "F", "S").forEachIndexed { index, dow ->
                Text(
                    dow,
                    modifier = Modifier.weight(1f).padding(vertical = 4.dp),
```

with:

```kotlin
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            // Rotated to the locale's first day, from the same source the blank
            // count comes from — the letter and the column cannot disagree.
            TaskTime.weekdayInitials().forEachIndexed { index, dow ->
                Text(
                    dow,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("dow-$index")
                        .padding(vertical = 4.dp),
```

Leave the rest of the `Text` call (`fontFamily`, `fontSize`, `fontWeight`, `letterSpacing`, `color`, `textAlign`) exactly as it is. `TaskTime` (line 32) and `testTag` (line 26) are already imported.

- [ ] **Step 5: Run the test to verify it passes**

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
./gradlew :app:testDebugUnitTest --tests 'com.wgorski.peskyreminders.ui.AddTaskSheetTest'
```

Expected: PASS.

- [ ] **Step 6: Run the whole JVM suite**

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
./gradlew :app:testDebugUnitTest
```

Expected: all green. `EditTaskSheetTest` in particular — it drives the same grid and has just had its locale pinned.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/wgorski/peskyreminders/ui/TimePickers.kt \
        app/src/test/java/com/wgorski/peskyreminders/ui/AddTaskSheetTest.kt \
        app/src/test/java/com/wgorski/peskyreminders/ui/EditTaskSheetTest.kt
git commit -m "$(cat <<'EOF'
feat: the calendar header rotates with the locale's first day

The letters were a literal beside a blank count that had just learned to
ask the locale; now both come from weekdayInitials. Both sheet tests pin
Locale as well as TimeZone, because the grid they host now reads it.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01VjkBkEhQiGaTYdCHwauHvJ
EOF
)"
```

---

### Task 3: verify on the emulator, update the docs, and stage the release

**Files:**
- Modify: `CLAUDE.md:568-572` (the week-boundaries gotcha)
- Modify: `app/build.gradle.kts:42` (`versionCode`), `app/build.gradle.kts:45` (`versionName`)
- Create: `dist/pesky-reminders-0.23.0.apk`, `dist/pesky-reminders.apk` (both gitignored)

**Interfaces:**
- Consumes: `TaskTime.weekdayInitials` and the rotated header from Tasks 1 and 2.
- Produces: nothing later tasks depend on — this is the last task.

- [ ] **Step 1: Correct the CLAUDE.md gotcha**

Replace `CLAUDE.md` lines 568-572:

```markdown
- **Week boundaries follow the locale, not our calendar grid.** `startOfWeek` asks
  `Calendar.getFirstDayOfWeek()` — Sunday in the US, Monday across most of Europe —
  because "this week" is a claim about the user's calendar. The month grid in the task
  sheet is still hardcoded Sunday-first, so **the two can disagree**; unify them if it
  ever shows. Tests that touch banding must pin `Locale` as well as `TimeZone`.
```

with:

```markdown
- **Week boundaries follow the locale, the month grid included.** `startOfWeek` asks
  `Calendar.getFirstDayOfWeek()` — Sunday in the US, Monday across most of Europe —
  because "this week" is a claim about the user's calendar. The grid asks the same
  question through `leadingBlanks` and `weekdayInitials`, which is what keeps the
  column the 1st lands in, and the letter drawn above it, agreeing with the list's
  THIS WEEK / NEXT WEEK bands. The two used to disagree — the grid was hardcoded
  Sunday-first in two independent spots, a literal header row and a `DAY_OF_WEEK - 1`
  blank count — so **anything new that reasons about a week has to go through one of
  those two functions**, not a third copy. Tests that touch banding *or the grid* must
  pin `Locale` as well as `TimeZone`; `AddTaskSheetTest` and `EditTaskSheetTest` both
  do, because both host the grid.
```

- [ ] **Step 2: Bump the version**

In `app/build.gradle.kts`, line 42: `versionCode = 28` → `versionCode = 29`. Line 45: `versionName = "0.22.0"` → `versionName = "0.23.0"`.

Minor, not patch: the calendar visibly redraws on a Monday-first device.

- [ ] **Step 3: Build and install the debug APK**

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
adb devices
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop com.wgorski.peskyreminders
adb shell am start -n com.wgorski.peskyreminders/.MainActivity
```

If `adb devices` lists nothing, the emulator is down — boot it and do not ask first:

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
nohup emulator -avd pesky -no-window -no-audio -no-boot-anim -no-snapshot \
  -gpu swiftshader_indirect >/tmp/emulator.log 2>&1 &
adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do sleep 2; done
adb shell wm dismiss-keyguard
```

- [ ] **Step 4: Screenshot the grid as shipped (en-US — Sunday-first)**

The AVD is en-US, so this leg proves the change did not break the existing rendering. Drive it in a straight-line script — a shell function in a loop has exhausted the process table and killed the emulator before. Coordinates are for the AVD's full 1080×2400 resolution.

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
adb shell input tap 950 2180      # the FAB, bottom right
sleep 1
adb exec-out screencap -p > /tmp/grid-sheet.png
```

Look at `/tmp/grid-sheet.png`, find the "Calendar" tab, tap it at its real coordinates, then:

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
adb exec-out screencap -p > /tmp/grid-us.png
```

Expected in `/tmp/grid-us.png`: header reads **S M T W T F S**, and August 2026's 1st sits in the last column.

- [ ] **Step 5: Screenshot the grid under a Monday-first locale (en-GB)**

Per-app locale, so no reboot and no root:

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
adb shell cmd locale set-app-locales com.wgorski.peskyreminders --user current --locales en-GB
adb shell am force-stop com.wgorski.peskyreminders
adb shell am start -n com.wgorski.peskyreminders/.MainActivity
sleep 2
adb shell input tap 950 2180
sleep 1
adb exec-out screencap -p > /tmp/grid-gb-sheet.png
```

Tap "Calendar" at the coordinates read off that screenshot, then capture:

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
adb exec-out screencap -p > /tmp/grid-gb.png
```

Expected in `/tmp/grid-gb.png`: header reads **M T W T F S S**, and every date has moved one column left of where `/tmp/grid-us.png` had it. Actually look at both images. If the header rotated but the dates did not (or the reverse), `leadingBlanks` and `weekdayInitials` have disagreed — the exact failure Task 1's second test exists to prevent, so re-run it.

Then put the emulator back:

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
adb shell cmd locale set-app-locales com.wgorski.peskyreminders --user current --locales ""
adb shell am force-stop com.wgorski.peskyreminders
```

- [ ] **Step 6: Build the release APK and stage it in `dist/`**

`-PuseDebugSigning` is required: an APK meant for sideloading must stay debug-signed or it cannot install over the copy already on the phone.

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
./gradlew :app:assembleRelease -PuseDebugSigning
mkdir -p dist
cp app/build/outputs/apk/release/pesky-reminders-0.23.0.apk dist/pesky-reminders-0.23.0.apk
cp app/build/outputs/apk/release/pesky-reminders-0.23.0.apk dist/pesky-reminders.apk
shasum -a 256 app/build/outputs/apk/release/pesky-reminders-0.23.0.apk \
              dist/pesky-reminders-0.23.0.apk dist/pesky-reminders.apk
```

Expected: three identical hashes. Do **not** start a tunnel or serve anything publicly — staging in `dist/` is the whole job.

- [ ] **Step 7: Install the release APK and confirm the version**

The debug build passing is not evidence about the one being staged.

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
adb install -r dist/pesky-reminders-0.23.0.apk
adb shell dumpsys package com.wgorski.peskyreminders | grep -m1 versionName
adb shell am force-stop com.wgorski.peskyreminders
adb shell am start -n com.wgorski.peskyreminders/.MainActivity
sleep 2
adb exec-out screencap -p > /tmp/release-0.23.0.png
```

Expected: `versionName=0.23.0`, and a screenshot of the list rendering normally.

- [ ] **Step 8: Commit**

```bash
git add CLAUDE.md app/build.gradle.kts
git commit -m "$(cat <<'EOF'
chore: 0.23.0 — the month grid follows the locale's week start

Records that the grid and the bands now share one answer, and that
anything new reasoning about a week goes through leadingBlanks or
weekdayInitials rather than a third copy.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01VjkBkEhQiGaTYdCHwauHvJ
EOF
)"
```

---

## Not in scope

- **No instrumented (`androidTest`) run.** Nothing here touches the notification model, the scheduler or the store. `ReminderModelTest` also wipes the device's task list, which would cost more than it proves.
- **No `Settings.kt` change**, no 12/24h toggle — both were considered and dropped in the spec.
- **The `WEEKDAYS` labels stay English.** A Polish device gets a Monday-first grid with English letters; localising day names is separate work.
