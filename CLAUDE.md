# CLAUDE.md — Pesky Reminders

Guidance for Claude Code when working in this repo.

## What this is

An Android app (Kotlin + Jetpack Compose) for reminders you **cannot swipe away** —
only the notification's own **Snooze** and **Done** actions, or the action sheet a tap
on it opens, can clear it.

It started as a one-screen POC proving that notification model, and now carries the
full **"Pesky Reminders v2"** Claude Design UI on top of it: a task list banded by
when things are due (Overdue / Today / Tomorrow / This week / Next week / Later)
plus a collapsible Done section, and one bottom sheet that both adds
and edits — a name, a time picked either on scroll wheels or on a calendar, and a
repeat rule. **Tapping a task opens it for editing**; there is no long-press menu.

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

**Deterministic (JVM, ~9s, no device)** — `app/src/test/`, 171 tests. Robolectric hosts
the real composables, and every screen takes `nowMillis` as a parameter instead of
reading the clock, so each expected label is a fixed string. `TaskTimeTest` covers the
date maths; `TaskListScreenTest`, `AddTaskSheetTest` and `EditTaskSheetTest` drive
every control in the UI. Tests pin `TimeZone` to UTC so they pass on any machine.

- These run **automatically after every Edit/Write** to a `.kt`/`.kts`/manifest file,
  via the `PostToolUse` hook in `.claude/settings.json`
  (`.claude/hooks/verify-change.sh`). It runs in the background and only interrupts on
  failure.
- They also gate `git push` via `.githooks/pre-push`. Enable once per clone:
  `git config core.hooksPath .githooks`. Bypass with `git push --no-verify`.

**Device (emulator)** — `app/src/androidTest/`, 57 tests. `ReminderModelTest` is the
real proof of the notification model: it fires the notification's own delete-intent,
which is exactly what the OS sends on a swipe. Note it calls `TaskStore.clear()`, so
running it **wipes the task list on the device**.

Known limitation: Compose's synthetic pointer injection (`performClick()`) does not
reach into the task sheet's scrolling body under Robolectric — the taps land on
nothing, though they work fine on a device. `AddTaskSheetTest` and
`EditTaskSheetTest` therefore assert the control is displayed and then fire its click
action directly. That covers state and visibility but **not hit-test geometry**, which
is why the emulator pass below still matters.

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

## Publish every finished change (standing instruction)

Do not wait to be asked. Once a change is verified, the **last step of the workflow**
is to bump the version, build the release APK, and republish it over the tunnel. A
finished change I cannot install on my phone is not finished.

```bash
./gradlew :app:assembleRelease
cp app/build/outputs/apk/release/pesky-reminders-$V.apk dist/pesky-reminders-$V.apk
cp app/build/outputs/apk/release/pesky-reminders-$V.apk dist/pesky-reminders.apk
```

- Stage **both** names: the version-pinned file is immutable once its URL is out,
  `pesky-reminders.apk` is the "latest" pointer.
- Reuse the running `cloudflared` — a quick tunnel mints a **new hostname** on every
  restart, so leaving it alone keeps previously shared links alive. A 502 through the
  tunnel means the local server died, not the tunnel: restart
  `python3 -m http.server 9999` from `dist/`.
- Verify by downloading through the tunnel and hash-matching the staged artifact
  before reporting the URL. Install the **release** APK on the emulator and confirm
  `versionName` — the debug build passing is not evidence about the one being served.

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
  Task.kt               # Task + Repeat model, incl. the snooze anchor (slotMillis)
  TaskTime.kt           # PURE date maths, labels & DueGroup banding — unit-tested
  TaskStore.kt          # SharedPreferences-backed list, observable via mutableStateOf
  Settings.kt           # user prefs (nag on/off + interval), same lazy-hydrate pattern
  Reminders.kt          # facade where the store and the alarm/notification plumbing meet
  ReminderContract.kt   # constants, per-task notification ids and request codes
  ReminderScheduler.kt  # AlarmManager.setAlarmClock wrapper (schedule/cancel per task)
  ReminderReceiver.kt   # BroadcastReceiver: FIRE / REPOST / DONE / NAG
  BootReceiver.kt       # re-arms alarms after a reboot or an app update
  ReminderActivity.kt   # translucent host for the notification's action sheet
  SnoozeOptions.kt      # PURE snooze durations + labels — unit-tested
  ReminderNotifier.kt   # builds the ongoing, re-posting notification
  ui/
    Theme.kt            # PeskyColors tokens + Bricolage Grotesque / DM Sans families
    PeskyIcons.kt       # the design's icon set as stroked ImageVectors
    Common.kt           # PeskyType text styles, pressable/tap modifiers
    PeskySheet.kt       # shared bottom-sheet chrome (scrim, entrance, tap-swallow)
    PeskyApp.kt         # root: sheet + done-section state, the "now" ticker
    TaskListScreen.kt   # header, the DueGroup bands + Done section, FAB
    TaskSheet.kt        # add AND edit in one: name, repeat, save, the action rows
    TimePickers.kt      # the wheels and the month grid — shared by both paths
    SettingsSheet.kt    # nag on/off + interval
    ReminderSheet.kt    # notification only: Done, 15/30/1h/3h chips, 5 min–72 hr wheel
    ConfirmSheet.kt     # shared "are you sure?" chrome — every delete goes through it
    ClearDoneSheet.kt   # confirms CLEAR (the whole done list)
    DeleteTaskSheet.kt  # confirms deleting one task — the only exit for a repeater
    PeskyWheel.kt       # the shared scrolling picker column
app/src/main/res/
  font/                     # Bricolage Grotesque + DM Sans TTFs (from Google Fonts)
  drawable/                 # the bell mark: launcher foreground, monochrome, notification
  mipmap-anydpi-v26/        # adaptive icon (no PNG densities needed — minSdk is 26)
  values/                   # themes.xml (translucent reminder-sheet host), colors.xml (icon bg)
app/src/test/               # deterministic JVM suite (Robolectric-hosted Compose)
  ReminderContractTest.kt   #   scheduling arithmetic
  TaskTimeTest.kt           #   date maths and labels
  ui/TaskListScreenTest.kt  #   the date bands, toggles, FAB
  ui/AddTaskSheetTest.kt    #   every control in the add sheet
  ui/EditTaskSheetTest.kt   #   seeding, one-field edits, the action rows
  ui/SettingsSheetTest.kt   #   the nag switch and interval field
  ui/ReminderSheetTest.kt   #   Done, chips, wheel, one-tap commit
  ui/ClearDoneSheetTest.kt  #   the clear-done confirmation
  ui/DeleteTaskSheetTest.kt #   the delete-one-task confirmation
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
- **An adaptive icon only ever shows the central 72dp of its 108dp canvas**, so
  artwork drawn to the full canvas comes out looking zoomed in. The bell mark
  spans 47dp, about 65% of the visible area, matching the stock icons.
- **The notification icon is alpha-only.** Android discards its colour, so
  `ic_notification.xml` is a flat white silhouette. Its bell is drawn smaller
  than the launcher's so the motion arcs do not merge into it at 24dp, and the
  stroke is pre-divided by the group scale so it renders at ~1.7dp instead of a
  hairline.
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
  action goes through the same call. The corollary bit us: because it never lands in
  the done list, `clearDone` could never reach it, so a repeating task was impossible
  to get rid of. `Reminders.delete` is its only exit — don't remove it, and it is why
  the edit sheet offers Delete **only** on a repeater.
- **Ticking off a repeater before its slot has come does nothing — and says so.**
  Rolling it forward would throw away the occurrence you could still act on, so
  `Reminders.toggle` returns early. A refusal that looked identical to a broken
  control was worse than no control, so `toggle` returns a `ToggleOutcome` and
  `PeskyApp` raises a toast ("Not due until Tomorrow, 8:00 AM.") on `NOT_DUE_YET`.
  It states the fact and stops there — the earlier version explained the consequence
  too and read like an argument. The *rule* stays in `toggle`; the UI only reports it.
  Don't re-derive the condition at the call site — two copies will drift. This is the
  app's only toast, and it is system-styled rather than Pesky-styled.
- **A snooze moves one firing, not the whole cycle.** `Task.dueMillis` is when it
  fires; `Task.anchorMillis` holds the recurring slot it came from while a repeater is
  snoozed, and `Task.slotMillis` is the accessor everything should reason with.
  Snoozing a daily 9am reminder to 9:35 must leave tomorrow at 9am — counting the next
  occurrence from the snooze would drag the task later every single day. The first
  snooze wins; `toggle` clears the anchor as the cycle turns; an explicit edit clears
  it too, because the time you just picked *is* the new slot. Both the "is it due yet"
  test and the next-occurrence step read `slotMillis`, which is what keeps "snooze it,
  then finish it a minute later" working.
- **The list's sections come from `TaskTime.groupOf`, and the enum order IS the
  screen order.** `DueGroup` is declared chronologically and `TaskListScreen` lays
  the list out by walking `DueGroup.entries`, skipping empty bands — so adding a band
  means putting it in the right place in the enum, and nothing else. Three things to
  keep in mind: overdue wins over everything, so a task due at 09:00 leaves TODAY the
  moment it is late; TODAY and TOMORROW are tested *before* the weeks, which is what
  stops a Saturday's Sunday being filed under NEXT WEEK; and THIS WEEK is legitimately
  empty on the last day of a week, since everything left is today or tomorrow.
- **Week boundaries follow the locale, not our calendar grid.** `startOfWeek` asks
  `Calendar.getFirstDayOfWeek()` — Sunday in the US, Monday across most of Europe —
  because "this week" is a claim about the user's calendar. The month grid in the task
  sheet is still hardcoded Sunday-first, so **the two can disagree**; unify them if it
  ever shows. Tests that touch banding must pin `Locale` as well as `TimeZone`.
- **Keep `dueMillis` meaning "when it fires".** The anchor was added *beside* it
  rather than by turning `dueMillis` into the slot, precisely so every existing
  consumer — the list's sections and sort, the notification text, the scheduler —
  stayed correct without an audit. Don't invert that.
- **Adding and editing are one sheet.** `TaskSheet` takes a nullable `existing` task;
  `AddTaskSheet` and `EditTaskSheet` are thin wrappers over it. Two copies of a
  two-way time picker would drift apart at the first fix to either one. The draft
  state is `rememberSaveable(existing?.id)` — keyed on the task, so it resets rather
  than leaks if the sheet is reused.
- **`Reminders.update` must not cancel a notification it is leaving overdue.** The
  three branches are not symmetrical: moved into the future cancels the notification
  and arms an alarm; still in the past cancels only the *alarm* and **re-posts** a
  notification that is already showing, so it picks up the new name and keeps
  nagging; done cancels all three. The naive version cancelled unconditionally,
  which meant opening an overdue task and pressing Save with nothing changed
  silently cleared a reminder the user is not allowed to dismiss — the whole premise
  of the app, defeated by a no-op. There is an instrumented test for both halves.
- **The editor's Delete is not part of the draft.** Name/time/repeat wait for Save;
  Delete acts immediately and drops unsaved edits. There is deliberately no
  mark-as-done row — the list's check circle does that in one tap, and offering it
  twice invited the question of whether it saved the draft on the way past.
- **The task sheet only just fits, so watch its height.** `PeskySheet` caps itself at
  **95%** of the screen and the body scrolls past that. With a repeater's Delete row
  the content came to ~723dp against a 766dp ceiling on a 440dpi 2340px phone — a 5%
  margin that any font scale over 1.2 ate, and the few dp of scroll that resulted read
  as broken rather than as a scroller: the first field label slides under the header
  and a strip of dead space opens above the footer. The margin now comes from the 95%
  cap plus `PeskyWheel`'s 148dp height (one number, shared with the reminder sheet).
  **Anything new in that body has to pay for itself** — check it at 440dpi with font
  scale 1.3, which is the case that broke.
- **The notification is always present tense.** "Is due Today, 09:00", late or not; it
  is on screen *because* the thing still wants doing. The list rows keep "Was due …",
  where being late is the fact worth stating. Instrumented tests pin both.
- **The sheet always has a time chosen.** `dueMillis` is non-nullable in `TaskSheet`:
  an edit starts on the task's own time, a new pester on `TaskTime.defaultDue` (about
  an hour out, on the hour; tomorrow 08:00 once the clock reads 21:00). That is what
  lets the pickers always show a selection and leaves the name as the only thing Save
  waits for. There is no "nothing picked yet" state left to render.
- **Never schedule an alarm in the past** — `setAlarmClock` fires it immediately.
  `Reminders.toggle` and `Reminders.update` cancel instead when the new due time has
  already passed. Note `Reminders.create` does *not*: a new task with a past time is
  taken as "pester me now".
- **Snooze counts from the clock, never from the task's due time.** `ReminderSheet`
  deliberately has no "start from" parameter: the preview and `Reminders.snooze` must
  read the same clock, or the readout promises a time the button does not deliver.
  That bug has already happened once. It is reachable only from the notification —
  its Snooze action, or a tap on its body — because the task list picks absolute
  times instead.
- **The reminder sheet has no confirm step.** Every chip and every wheel row
  commits on the tap, which is why nothing in it holds a selection: the chips
  have no chosen state and the wheel is passed `selectedIndex = -1`. It is also
  why there is no "back at …" footer — with no held choice there is nothing to
  preview, so each wheel row states the time it lands on instead. Adding a
  highlight back would promise a confirm step that does not exist.
- **A notification action that shows UI must be an activity PendingIntent.**
  Android 12+ blocks notification trampolines, so Snooze cannot go through
  `ReminderReceiver` — it opens `ReminderActivity` directly. Done stays a broadcast
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

- **A one-off cannot be deleted directly.** The edit sheet offers Delete only on a
  repeater, so getting rid of an unwanted one-off means ticking it off and then
  clearing the done list — two steps and a trip through a section you may have
  collapsed.
- **The wheels cannot always point at the task.** The DAY column spans a fortnight, so
  anything further out — or already past — shows nothing selected there; the MIN column
  is quarter-hours, so a snoozed task sitting at :07 shows nothing selected either. The
  footer readout always states the real due time and the calendar opens on the task's
  own month, so nothing is misreported; the wheel just cannot point at it.
- **No undo.** Neither `delete` nor `clearDone` keeps a tombstone, which is why both
  go through `ConfirmSheet`. Any further destructive action should confirm the same
  way, or add real undo and drop the sheets — with one deliberate exception: the
  reminder sheet's snooze chips and wheel rows commit the instant they're tapped,
  no confirm step at all. The choice *is* the whole interaction there, and a
  confirm button was judged a second tap that added nothing (see
  `docs/superpowers/specs/2026-07-27-notification-action-sheet-design.md`) — don't
  "fix" that by reflex.
- **The section-header tap targets are smaller than 48dp.** Both the Done toggle
  and CLEAR are ~18dp tall, because that is the height the design gives the header
  row. Growing either one alone makes the header jump when it expands, and growing
  both changes the section spacing throughout.
