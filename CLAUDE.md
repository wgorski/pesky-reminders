# CLAUDE.md — Pesky Reminders

Guidance for Claude Code when working in this repo.

## What this is

An Android app (Kotlin + Jetpack Compose) for reminders you **cannot swipe away** —
only the notification's own **Snooze** (+5 min) and **Done** actions can clear it.

It started as a one-screen POC proving that notification model, and now carries the
full **"Pesky Reminders v2"** Claude Design UI on top of it: a task list with
Overdue / Up next / collapsible Done sections, and a "New pester" bottom sheet
that picks a time three ways (shortcut chips, scroll wheels, or a calendar) plus a
repeat rule.

Mechanism: `setOngoing(true)` + a `deleteIntent` that re-posts the notification
whenever it is dismissed. `AlarmManager.setAlarmClock()` schedules the fire.
No foreground service, no full-screen intent.

Design/plan/verification live in `docs/`. Package: `com.peskyreminders.poc`.

## Environment (IMPORTANT)

- The Android SDK is at `/opt/homebrew/share/android-commandlinetools`. It is NOT on
  the default PATH, and shell env does not persist between separate command runs.
  **Begin every command block that uses `adb`, `emulator`, or `sdkmanager` (and before
  `./gradlew`, so it finds the SDK) with:**
  ```bash
  export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
  export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
  ```
- Emulator AVD name is `pesky` (API 35, google_apis, arm64-v8a). Boot it headless:
  ```bash
  emulator -avd pesky -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect
  ```
  Then `adb wait-for-device`, poll `getprop sys.boot_completed` until `1`, and
  `adb shell wm dismiss-keyguard`.
- **If the emulator process gets killed mid-task, restart it automatically — do not
  ask first.** Own the emulator in the controlling session so it survives across
  sub-tasks.
- Use the Gradle **wrapper** (`./gradlew`, pinned to 8.11.1). Do NOT use the system
  `gradle` (9.5.1) — it cannot configure AGP 8.7.3.

## Versioning — semver, bump once per branch/session

The single source of truth is `versionName` in `app/build.gradle.kts`. Treat all the
changes in one branch or one Claude Code session as **one** change: bump the version
once for the whole unit of work, not once per file or per commit.

- Bump the **minor** component (`0.X.0`) for new features / behaviour changes.
- Bump the **patch** component (`0.0.X`) for bug fixes, refactors, or doc-only edits.
- If a session mixes both, the **minor** bump wins.
- Bump `versionCode` alongside it, or Android refuses to install over the last build.

Don't skip the bump, but don't bump repeatedly within the same session either.
Release APKs are auto-named `pesky-reminders-${versionName}.apk` by the
`applicationVariants` hook in `app/build.gradle.kts`, so the file in
`app/build/outputs/apk/release/` always reflects the current version. Debug builds
keep the stock `app-debug.apk` name.

To publish, use the `release` skill (`.claude/skills/release/`).

## Common commands

```bash
# Build debug APK
./gradlew :app:assembleDebug

# Install + launch on the emulator
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.peskyreminders.poc/.MainActivity

# The deterministic suite — JVM only, no device, ~6s. Run this constantly.
./gradlew :app:testDebugUnitTest

# Instrumented tests (emulator must be running).
# NOTE: connected-test tasks do NOT support --tests; use the property form:
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.peskyreminders.poc.ReminderModelTest
```

## Testing

Two tiers, and they cover different things.

**Deterministic (JVM, ~6s, no device)** — `app/src/test/`, 50 tests. Robolectric hosts
the real composables, and every screen takes `nowMillis` as a parameter instead of
reading the clock, so each expected label is a fixed string. `TaskTimeTest` covers the
date maths; `TaskListScreenTest` and `AddTaskSheetTest` drive every control in the UI.
Tests pin `TimeZone` to UTC so they pass on any machine.

- These run **automatically after every Edit/Write** to a `.kt`/`.kts`/manifest file,
  via the `PostToolUse` hook in `.claude/settings.json`
  (`.claude/hooks/verify-change.sh`). It runs in the background and only interrupts on
  failure.
- They also gate `git push` via `.githooks/pre-push`. Enable once per clone:
  `git config core.hooksPath .githooks`. Bypass with `git push --no-verify`.

**Device (emulator)** — `app/src/androidTest/`, 20 tests. `ReminderModelTest` is the
real proof of the notification model: it fires the notification's own delete-intent,
which is exactly what the OS sends on a swipe. Note it calls `TaskStore.clear()`, so
running it **wipes the task list on the device**.

Known limitation: Compose's synthetic pointer injection (`performClick()`) does not
reach into the add sheet's scrolling body under Robolectric — the taps land on
nothing, though they work fine on a device. `AddTaskSheetTest` therefore asserts the
control is displayed and then fires its click action directly. That covers state and
visibility but **not hit-test geometry**, which is why the emulator pass below still
matters.

## Required workflow — verify every change on the emulator

The JVM suite is fast and catches logic and wiring regressions, but it does not prove
the UI renders or that touches land. After **every** change to Kotlin, manifest,
Gradle, or resource files:

1. `./gradlew :app:testDebugUnitTest` — prefer adding a case here over driving the
   emulator; it is ~100× faster and never flakes.
2. `./gradlew :app:assembleDebug` — must succeed.
3. `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
4. `adb shell am force-stop com.peskyreminders.poc && adb shell am start -n com.peskyreminders.poc/.MainActivity`.
5. `adb exec-out screencap -p > /tmp/<name>.png` and actually look at it.
6. For anything touching the notification model, run
   `./gradlew :app:connectedDebugAndroidTest` — `ReminderModelTest` is the real proof.

Drive the UI with `adb shell input tap <x> <y>` / `input swipe` / `input text`, and
re-screenshot after each step. Note that `screencap` is at full device resolution
(1080×2400 on the `pesky` AVD) — scale tap coordinates accordingly if you measured
them on a downscaled image. Keep `adb` calls in straight-line scripts; a shell
function called in a loop has exhausted the process table and killed the emulator.

Do not declare a change "done" until a screenshot reflects it. A clean compile is not
sufficient — Compose errors (bad modifier order, taps falling through to the wrong
layer) build fine, pass the JVM suite, and only show up on a device.

## When I ask you to "expose the apk locally"

Run these steps (produces a debug-signed, installable **release** APK and serves it
over HTTP on **port 9999**):

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

# 1. Build the release APK (debug-signed via the release buildType, so it installs)
./gradlew :app:assembleRelease

# 2. Stage it in the serving dir (gitignored). The artifact is version-named.
mkdir -p dist
cp app/build/outputs/apk/release/pesky-reminders-*.apk dist/pesky-reminders.apk

# 3. Serve on port 9999 (run in the background)
cd dist && python3 -m http.server 9999 --bind 0.0.0.0
```

Then report the download URLs:
- Host machine:            `http://localhost:9999/pesky-reminders.apk`
- Phone on the same Wi-Fi: `http://<HOST_LAN_IP>:9999/pesky-reminders.apk`
  (find the IP with `ipconfig getifaddr en0`)
- Android emulator:        `http://10.0.2.2:9999/pesky-reminders.apk`

Notes:
- The release build is signed with the **debug** key (see `app/build.gradle.kts`)
  purely so the POC APK can be sideloaded. A real release needs its own keystore.
- If port 9999 is already in use, the existing server is probably still running —
  just re-report the URLs (rebuild + re-copy the APK first if the code changed).

## Project layout

```
app/src/main/java/com/peskyreminders/poc/
  MainActivity.kt       # edge-to-edge host; hydrates the store, asks for POST_NOTIFICATIONS
  Task.kt               # Task + Repeat model
  TaskTime.kt           # PURE date maths & labels (no Android deps) — unit-tested
  TaskStore.kt          # SharedPreferences-backed list, observable via mutableStateOf
  Settings.kt           # user prefs (nag on/off + interval), same lazy-hydrate pattern
  Reminders.kt          # facade where the store and the alarm/notification plumbing meet
  ReminderContract.kt   # constants, per-task notification ids and request codes
  ReminderScheduler.kt  # AlarmManager.setAlarmClock wrapper (schedule/cancel per task)
  ReminderReceiver.kt   # BroadcastReceiver: FIRE / REPOST / DONE / NAG
  BootReceiver.kt       # re-arms alarms after a reboot or an app update
  SnoozeActivity.kt     # translucent host for the snooze picker
  SnoozeOptions.kt      # PURE snooze durations + labels — unit-tested
  ReminderNotifier.kt   # builds the ongoing, re-posting notification
  ui/
    Theme.kt            # PeskyColors tokens + Bricolage Grotesque / DM Sans families
    PeskyIcons.kt       # the design's icon set as stroked ImageVectors
    Common.kt           # PeskyType text styles, pressable/tap modifiers
    PeskySheet.kt       # shared bottom-sheet chrome (scrim, entrance, tap-swallow)
    PeskyApp.kt         # root: sheet + done-section state, the "now" ticker
    TaskListScreen.kt   # header, Overdue / Up next / Done sections, FAB
    AddTaskSheet.kt     # "New pester" sheet: chips, wheels, calendar, repeat, save
    SettingsSheet.kt    # nag on/off + interval
    SnoozeSheet.kt      # "Snooze until": presets + quarter-hour wheel
    PeskyWheel.kt       # the shared scrolling picker column
app/src/main/res/font/      # Bricolage Grotesque + DM Sans TTFs (from Google Fonts)
app/src/test/               # deterministic JVM suite (Robolectric-hosted Compose)
  ReminderContractTest.kt   #   scheduling arithmetic
  TaskTimeTest.kt           #   date maths and labels
  ui/TaskListScreenTest.kt  #   list sections, toggles, FAB
  ui/AddTaskSheetTest.kt    #   every control in the add sheet
  ui/SettingsSheetTest.kt   #   the nag switch and interval field
  ui/SnoozeSheetTest.kt     #   presets, wheel, readout, commit
  SettingsTest.kt           #   interval clamping
  SnoozeOptionsTest.kt      #   snooze durations and labels
app/src/androidTest/...     # instrumented tests (ReminderModelTest) — the real proof
docs/                       # spec, plan, verification (with screenshots)
.claude/hooks/              # verify-change.sh — post-edit test run
.claude/skills/release/     # cut a GitHub release with the APK attached
.githooks/pre-push          # blocks a push when the JVM suite is red
```

## Android behavior to remember

On Android 14+ (API 34+) the ongoing flag no longer blocks an *individual* swipe
(it still blocks "clear all" and swipe-while-locked). The delete-intent re-post is
what actually defeats a swipe. `NotificationManager.cancel()` (used by Snooze/Done)
does NOT fire the delete-intent, so those clear the notification without re-posting.

## Conventions & gotchas

- **`TaskTime` must stay pure.** It takes `nowMillis` as a parameter instead of
  reading the clock, which is what makes it unit-testable. Don't add a `Context`.
- **Calendar arithmetic, not fixed millisecond offsets.** The original design script
  used `+ 864e5` for "a day"; that breaks across DST. Use `Calendar.add`.
- **Press feedback is a scale, not a ripple.** The design has no ripples — use
  `Modifier.pressable(scale = …)` from `ui/Common.kt`, and put it **before**
  `.clip()`/`.background()` in the chain, or only the content scales and the
  background stays put.
- **The accent is the only saturated colour, and it does four jobs**: FAB/button
  fill, label-on-fill, accent-as-text on the background, and selected borders.
  Changing it means re-checking all four — a deep red that looks right on paper
  can drop the FAB below the 3:1 UI-component floor and make it vanish. Text on
  the accent is `Text` (cream), not `Screen`; near-black on crimson reads muddy.
- **Never swallow taps with a `clickable` ancestor.** The sheet needs to eat taps on
  its empty space so they don't reach the scrim behind and close it. Doing that with
  `Modifier.clickable` on the sheet's own Column sets `mergeDescendants`, collapsing
  the entire sheet into one semantics node — a single giant "button" to a screen
  reader, and unreachable to a UI test. The working shape is a
  `Box(Modifier.matchParentSize().tap {})` *sibling behind* the content.
- **Kotlin nests block comments.** A `/*` inside a KDoc (e.g. writing a path like
  `assets/icons/*.svg`) opens a nested comment and swallows the rest of the file.
- **The name field does not auto-focus** — a deliberate deviation from the design's
  `autoFocus`. On a phone that threw the keyboard up over the time pickers before the
  user had decided whether they wanted to type at all. Don't "fix" it back.
- **Never lose a typed value on focus alone.** Hiding the keyboard does NOT clear
  Compose focus, so `onFocusChanged` never fires and the value is silently dropped.
  The settings interval commits on each keystroke once it parses in range, and
  clamps on Done/dismiss. Same trap applies to any future field.
- **A repeating task is never "done".** Ticking it rolls it forward to the next
  occurrence; only `Repeat.ONCE` tasks flip to done. This is design behaviour, and
  `Reminders.toggle` is the single place it is implemented — the notification's Done
  action goes through the same call.
- **Never schedule an alarm in the past** — `setAlarmClock` fires it immediately.
  `Reminders.toggle` cancels instead when the new due time has already passed.
- **A notification action that shows UI must be an activity PendingIntent.**
  Android 12+ blocks notification trampolines, so Snooze cannot go through
  `ReminderReceiver` — it opens `SnoozeActivity` directly. Done stays a broadcast
  because it shows nothing. There is an instrumented test asserting exactly this.
- **Task ids start at 1.** They double as notification ids and as the base for
  PendingIntent request codes (`taskId * 8 + slot`), so `0` is reserved for the
  alarm's show-intent.
- **Alarms die on reboot AND on app update.** `BootReceiver` re-arms them from
  `TaskStore` on both `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED`. Anything whose
  due time passed while the device was off is posted immediately rather than
  skipped — see `Reminders.restoreAll`. The instrumented tests drive the receiver
  directly, which does not exercise the manifest registration; a real
  `adb reboot` + `adb shell dumpsys alarm | grep peskyreminders` is the end-to-end
  check.

## Known gaps

- **No delete or edit.** The design has neither, so neither was built.
