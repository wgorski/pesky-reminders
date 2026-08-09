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
repeat rule. **Tapping an overdue task opens the action panel** (Done + snooze);
tapping anything else opens it for editing, and **holding any active row** opens the
editor whatever its band.

Mechanism: `setOngoing(true)` + a `deleteIntent` that re-posts the notification
whenever it is dismissed. `AlarmManager.setAlarmClock()` schedules the fire.
No foreground service, no full-screen intent.

Design/plan/verification live in `docs/`. Package: `com.wgorski.peskyreminders`.

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
adb shell am start -n com.wgorski.peskyreminders/.MainActivity

# The deterministic suite — JVM only, no device, ~12s. Run this constantly.
./gradlew :app:testDebugUnitTest

# Instrumented tests (emulator must be running).
# NOTE: connected-test tasks do NOT support --tests; use the property form:
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.wgorski.peskyreminders.ReminderModelTest
```

## Testing

Two tiers, and they cover different things.

**Deterministic (JVM, ~12s, no device)** — `app/src/test/`, 239 tests. Robolectric hosts
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

**Device (emulator)** — `app/src/androidTest/`, 68 tests. `ReminderModelTest` is the
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
4. `adb shell am force-stop com.wgorski.peskyreminders && adb shell am start -n com.wgorski.peskyreminders/.MainActivity`.
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
is to bump the version, build the release APK, and stage it in `dist/`. A finished
change I cannot install on my phone is not finished.

```bash
# -PuseDebugSigning is REQUIRED here — see the two-channels gotcha below.
./gradlew :app:assembleRelease -PuseDebugSigning
cp app/build/outputs/apk/release/pesky-reminders-$V.apk dist/pesky-reminders-$V.apk
cp app/build/outputs/apk/release/pesky-reminders-$V.apk dist/pesky-reminders.apk
```

- Stage **both** names: the version-pinned file is immutable once it has been handed
  out, `pesky-reminders.apk` is the "latest" pointer.
- **Do not start a tunnel, and do not expose anything publicly.** Staging in `dist/`
  is the whole job. If I want the APK reachable from somewhere, I will ask — see the
  local-serving section below, which is on request only.
- Install the **release** APK on the emulator and confirm `versionName` before
  reporting it done; the debug build passing is not evidence about the one being
  staged. Hash the staged copies against the build output if anything moved them.

## The preview build — try a release candidate next to the real app

`assemblePreview` produces a **parallel-installable** dress rehearsal for the Play
build: `com.wgorski.peskyreminders.preview`, a green launcher icon, its own task
list. Use it to test on a real phone without risking the list you rely on.

```bash
# Build, stage in dist/preview/, and serve it on the LAN (port 9998).
.claude/skills/preview/serve-preview.sh
```

Everything about it is in the `preview` skill (`.claude/skills/preview/`). Four
facts worth having here:

- It is `initWith(release)`, **not** debug — the point is to exercise what ships,
  so it inherits release's config: not `debuggable`, no test manifest, release
  resource processing. **Minification is not part of that**, because release sets
  `isMinifyEnabled = false` — so a preview pass proves nothing about R8, and
  saying it does overstates what was verified. `initWith` is still the right shape:
  turn minification on for release and the preview picks it up for free, which is
  exactly when you would want a dress rehearsal.
- `applicationIdSuffix = ".preview"` is what makes it a separate app. The
  **namespace is untouched**, so `R`, `BuildConfig` and every
  `Intent(context, ReminderReceiver::class.java)` still resolve.
- It is **always debug-signed**, even when the upload key is configured. Signing a
  test build for Play buys nothing, and an upload-signed preview could not later
  be replaced by a debug-signed one without an uninstall.
- **Don't bump the version to cut a preview.** It must carry the version you are
  about to ship, or you are testing something else; `versionNameSuffix` is what
  distinguishes the artifact.

The green icon and label come from `app/src/preview/res` overriding exactly two
resources — `ic_launcher_background` and `app_name`. That is why the label is a
string resource rather than a literal in the manifest. The in-app accent stays
crimson deliberately: the preview should look and behave like the real build
everywhere except the launcher.

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
- **This is local only — host, LAN, emulator.** Do not put it behind a tunnel or any
  other public host. "Expose the apk locally" means exactly that; if I want a public
  URL I will say so explicitly.
- The release build is signed with the **debug** key (see `app/build.gradle.kts`)
  purely so the POC APK can be sideloaded. A real release needs its own keystore.
- If port 9999 is already in use, the existing server is probably still running —
  just re-report the URLs (rebuild + re-copy the APK first if the code changed).

## Google Play

Everything the console asks for is written out in `docs/play/LISTING.md` —
descriptions, the declarations, the Data safety answers, and the
`USE_EXACT_ALARM` justification. `docs/play/privacy-policy.{md,html}` is the
policy; the HTML is self-contained so it can go on any host. Store graphics
(512 icon, 1024×500 feature, four 9:16 screenshots) sit beside them.

### Writing the "What's new" notes

Release notes in `docs/play/release-notes.md` say **what is in the release**, in
the app's own voice, and nothing else. Two rules, both of which I have had to
ask for:

- **Never mention which track it is going to, or what stage of testing it is
  at.** "Second release", "goes to closed testing", a versionCode — that is
  Console state, not news. It is also stale the moment the release is promoted,
  and these notes carry over to production verbatim.
- **Never list what was left out.** No known bugs, no "not yet included", no
  deferred features, no note about which internal work was omitted as invisible.
  It reads as an apology and it advertises gaps nobody asked about.

What is left in: the user-visible changes, described as changes to the app rather
than to the code. Keep the character count line above each block — the field caps
at 500 per language and the same text runs ~10% longer in Polish.

```bash
# The Play artifact. Signed with the upload key, version-named.
./gradlew :app:stageReleaseBundle
# -> app/build/outputs/bundle/play/pesky-reminders-$V.aab
```

The upload key lives at `~/.pesky-keys/pesky-upload.jks`, **outside the repo** so
it cannot be committed; `keystore.properties` (gitignored) points at it. Signing
is opt-in: with no properties file the release build falls back to the debug key,
so a fresh clone still produces a sideloadable APK.

Two standing facts about the Play path:

- **targetSdk must stay ≥ 36.** Play requires it for new apps and updates from
  31 Aug 2026. AGP 8.7.3 predates API 36 and warns about it; the warning is
  acknowledged via `android.suppressUnsupportedCompileSdk` in `gradle.properties`
  rather than silenced blindly — the app compiles no API 36 symbols. Upgrading
  AGP is the real fix and should happen away from a release.
- **The account is a personal one created after 13 Nov 2023**, so production
  access needs a closed test: 12 opted-in testers on real devices for 14
  continuous days, then a review of up to a week.

## Project layout

```
app/src/main/java/com/wgorski/peskyreminders/
  MainActivity.kt       # edge-to-edge host; hydrates the store, asks for POST_NOTIFICATIONS
  Task.kt               # Task + Repeat model, incl. the snooze anchor (slotMillis)
  TaskTime.kt           # PURE date maths, labels & DueGroup banding — unit-tested
  TaskStore.kt          # SharedPreferences-backed list, observable via mutableStateOf
  Settings.kt           # user prefs (nag on/off + interval), same lazy-hydrate pattern
  Reminders.kt          # facade where the store and the alarm/notification plumbing meet
  ReminderContract.kt   # constants, per-task notification ids and request codes
  ReminderScheduler.kt  # AlarmManager.setAlarmClock wrapper (schedule/cancel per task)
  ReminderReceiver.kt   # BroadcastReceiver: FIRE / REPOST / DONE / SNOOZE / NAG
  BootReceiver.kt       # re-arms alarms after a reboot or an app update
  ReminderActivity.kt   # standalone translucent host for the action sheet (own task)
  SnoozeOptions.kt      # PURE snooze durations + labels — unit-tested
  ActionToast.kt        # every snooze/done toast string, in one place — unit-tested
  ReminderNotifier.kt   # builds the ongoing, re-posting notification
  ui/
    Theme.kt            # PeskyColors tokens + Bricolage Grotesque / DM Sans families
    PeskyIcons.kt       # the design's icon set as stroked ImageVectors
    Common.kt           # PeskyType text styles, pressable / tap (both drop keyboard focus)
    PeskySheet.kt       # shared sheet chrome (scrim, entrance, tap-swallow, drag-to-dismiss)
    PeskyApp.kt         # root: sheet + done-section state, the "now" ticker
    TaskListScreen.kt   # header, the DueGroup bands + Done section, FAB, tap/hold routing
    TaskSheet.kt        # add AND edit in one: name, repeat, save, the action rows
    TimePickers.kt      # the wheels and the month grid — shared by both paths
    SettingsSheet.kt    # nag on/off + interval
    ReminderSheet.kt    # Done, 15/30/1h/3h + time-of-day chips, 5 min–72 hr wheel
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
  ui/PeskySheetTest.kt      #   the drag rule (pure) + what the drag must not break
  ui/ClearDoneSheetTest.kt  #   the clear-done confirmation
  ui/DeleteTaskSheetTest.kt #   the delete-one-task confirmation
  SettingsTest.kt           #   interval clamping
  SnoozeOptionsTest.kt      #   snooze durations and labels
  ActionToastTest.kt        #   every sentence a snooze or done can produce
  ActionToastShowTest.kt    #   that it reaches the screen, and reads the task after
app/src/androidTest/...     # instrumented tests (ReminderModelTest) — the real proof
docs/                       # spec, plan, verification (with screenshots)
docs/play/                  # Play listing copy, privacy policy, store graphics
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

- **There are two release channels and they need different signatures.** Play
  rejects a debug-signed upload, so `assembleRelease`/`bundleRelease` use the
  upload key. But any APK meant for **sideloading** must stay **debug**-signed, or it
  cannot install over the copy already on the phone — Android refuses a signature
  change outright (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`), and the uninstall it
  forces takes the task list with it, `allowBackup` included. Hence
  `-PuseDebugSigning`, which forces the release build back onto the debug key.
  **Use it for every APK staged in `dist/`.** Verified both ways on the emulator: the
  debug build refuses to install over the upload-signed one, and 0.15.0 → 0.16.0
  upgrades in place with the list intact. The corollary is a one-off cost at Play
  launch: everyone running a sideloaded build has to uninstall first, and should
  be told so in the release notes before it happens.
- **`TaskTime` must stay pure.** It takes `nowMillis` as a parameter instead of
  reading the clock, which is what makes it unit-testable. Don't add a `Context`.
- **Calendar arithmetic, not fixed millisecond offsets.** The original design script
  used `+ 864e5` for "a day"; that breaks across DST. Use `Calendar.add`.
- **Press feedback is a scale, not a ripple.** The design has no ripples — use
  `Modifier.pressable(scale = …)` from `ui/Common.kt`, and put it **before**
  `.clip()`/`.background()` in the chain, or only the content scales and the
  background stays put.
- **Ticking a task off has a beat, and the commit waits for it.** The ring fills
  into the mint disc, the tick pops in, and the check settles — `TICK_FILL` +
  `TICK_HOLD`, ~120ms, in `TaskListScreen.kt` — and only then does
  `Reminders.toggle` run and the row leave. It is meant to be almost instant but
  seen, so **don't lengthen the hold to make the check readable**: `FADE_OUT`
  already keeps the row drawn, check and all, for the whole of its exit, so the
  check is on screen roughly twice as long as the wait in front of it. The first
  version of this paid for those frames twice, at 280ms, and felt sluggish. Five
  things hold it together:
  - **The check is drawn before the store changes**, because the store change is
    what removes the row. So the tap animates before anyone knows whether the tick
    will be honoured — which is why `onToggleTask` returns a `ToggleOutcome`. Only
    `COMPLETED` keeps the check — every other outcome either leaves the row on
    screen or takes it away, and neither has earned one. Don't predict this by
    re-deriving the not-due-yet rule in the UI — it lives in `Reminders.toggle`,
    same as for the toast.
  - **The pending ticks are timed above the `LazyColumn`, not inside the row.** A
    lazy list disposes an item's subcomposition the moment it scrolls out of
    view, which would cancel a `remember`ed flag's coroutine mid-`delay` and
    throw the completion away — "tick the top row, then fling down the list" is
    an ordinary gesture. `TaskListScreen` holds a `mutableStateListOf<Int>` of
    pending ids and times the commit from there; `TaskRow` only reads whether its
    id is in that list and asks to add it. It is `remember`, not
    `rememberSaveable`, on purpose: restoring a pending tick across process death
    would commit a tap made in a previous process, so a rotation inside the beat
    still loses it — a deliberate trade, not a bug to fix later.
  - **A second tap inside the beat is swallowed, not disabled.** A disabled
    `clickable` consumes nothing, so the tap would carry on up the hit path to
    the row's own `combinedClickable` and raise the editor or the action panel.
    The repeat tap is a no-op because adding the id is itself guarded —
    `if (task.id !in ticked) ticked.add(task.id)` at the `TaskListScreen` call
    site — not because the circle stops responding.
  - **`animateFloatAsState` initialises at its target**, which is the whole reason
    a done row is still instant and a row arriving in the Done section does not
    replay the beat it just performed.
  - **Un-ticking has no beat, deliberately.** Removing a check is an undo, and a
    quarter second in front of an undo is only latency. A test pins the asymmetry
    so it does not get "fixed".
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
- **A pointer-input modifier shadows the swallow layer behind it.** Hit testing stops
  at the topmost sibling that registers, and `draggable` does **not** consume a tap
  that never becomes a drag — so the tap carries on past it to the scrim. The sheet's
  drag handle hit exactly this: as a `Column` *beside* the swallow it shadowed it, and
  a tap on the title bar closed the sheet. The fix is that the `draggable` sits on a
  `Box` that **parents** its own swallow layer; children are still hit, siblings are
  not. Anything else gaining a gesture modifier inside a sheet needs the same shape.
- **The sheet drag cannot be tested through Compose's pointer injection.** Robolectric
  misroutes drags inside these sheets — a `performTouchInput` swipe on the grabber
  leaks to the scrim and dismisses, so the test passes or fails for reasons unrelated
  to the code (verified with a throwaway probe: the isolated equivalent is correct
  under Robolectric, and the real gesture is correct on a device). So the letting-go
  rule is pure — `shouldDismiss`/`dragFraction` in `PeskySheet.kt` — and tested
  exactly, while the gesture that feeds it is an emulator check. Don't "restore" a
  swipe test; it will lie in whichever direction it happens to land.
- **Kotlin nests block comments.** A `/*` inside a KDoc (e.g. writing a path like
  `assets/icons/*.svg`) opens a nested comment and swallows the rest of the file.
- **The name field auto-focuses when adding, and only when adding.** It used to
  refuse focus everywhere — the keyboard covers the time pickers before the user has
  decided whether they want to type. That still holds for an **edit**, which is
  usually a trip to change the time, so `NameField` takes `autoFocus = existing ==
  null` and the edit path is untouched. Adding is the case where the name is the one
  thing the sheet cannot default and the only thing Save waits on. Both halves are
  pinned — `assertIsFocused` in `AddTaskSheetTest`, `assertIsNotFocused` in
  `EditTaskSheetTest` — so neither can quietly become the other. Checked at font
  scale 1.3 with the keyboard up: the body scrolls, the footer stays pinned, the
  focused field is on screen.
- **Tapping anything puts the keyboard away, and that lives in `pressable`/`tap`.**
  Both wrap their click in `dismissingKeyboard`, so the rule is true everywhere at
  once — wheels, tabs, calendar cells, chips, Save, list rows, and the sheets'
  tap-swallow layer, which is a `tap {}` onto nothing. A `BasicTextField` is neither
  of those, so tapping the field itself never routes through it. Don't add a second
  copy anywhere. The one caller that notices is the settings interval, which clamps
  on focus loss — wanted, and already tested.
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
  a toast says so ("Not due until Tomorrow, 8:00 AM.") on `NOT_DUE_YET`.
  It states the fact and stops there — the earlier version explained the consequence
  too and read like an argument. The *rule* stays in `toggle`; the UI only reports it.
  Don't re-derive the condition at the call site — two copies will drift.
- **Every snooze and every done reports through `ActionToast`, and every string it can
  say lives there.** Four surfaces perform those two actions — the list's check circle,
  the action panel from an overdue row, the same panel from the notification, and the
  notification's own Done button — and one shared object is what stops them wording the
  same event differently. `Reminders` says *what happened* (`ToggleOutcome`,
  `SnoozeOutcome`); `ActionToast` decides *how it reads* and returns null for the cases
  that stay silent. All toasts are system-styled rather than Pesky-styled.
  Four things about it that are load-bearing:
  - **`ALREADY_PAST` must never read "snoozed until".** The sheet can sit open across
    the rung it is offering, and `snoozeUntil` then leaves the task overdue and
    pestering. It says "8:00 PM has passed — still due." — the one case where claiming
    a move would contradict what the app actually did.
  - **`ToggleOutcome` distinguishes `COMPLETED`/`REOPENED`/`ADVANCED` rather than
    letting the caller infer it.** A done one-off can be edited into a repeater (the
    editor opens from a done row and `update` carries `done` through), so
    `done && repeats` is reachable and un-ticking it takes the reopen branch while
    looking exactly like a roll-forward.
  - **Landing times use `formatCompact`, the refusal keeps `formatFull`.** Compact drops
    the day when it is today, which is what makes it "Snoozed until 3:45 PM." instead of
    "…until Today, 3:45 PM."; the refusal names a slot on a named day, where the day is
    never redundant. Both come from `TaskTime`, so no toast can invent a format.
  - **`show` cancels the previous toast and hops to the main looper.** Ticking five rows
    off would otherwise replay a ten-second queue. `Toast` throws outright off the main
    thread, and although every production caller is on it, the instrumented tests drive
    `ReminderReceiver` from the instrumentation thread and crashed — so it hops rather
    than leaving that trap for the next caller. It builds with `applicationContext`, both
    so the statically-held `Toast` cannot retain an Activity and so the toast outlives
    `ReminderActivity.finishAndRemoveTask()`.
  `ACTION_FIRE`/`REPOST`/`NAG` stay silent: the app talking to itself is not a user
  action to confirm.
- **How loudly a post announces itself is `ReminderNotifier.Alert`, and there are three
  levels because there are three different events.** `FULL` (the reminder arriving)
  sounds and buzzes; `BUZZ_ONLY` (the nag) buzzes; `SILENT` (a re-post after a swipe, an
  edit that leaves the task overdue, a snooze onto a time already gone) does neither.
  A swipe answering back with a chime and a buzz read as the app arguing with you.
  Three things hold it together:
  - **The sound is always Android's; the buzz depends on which event it is.** `FULL`
    lets the **channel** vibrate, because the system plays that as part of posting the
    notification and nothing about this process's lifetime can truncate it — the
    app-driven waveform could be, and that is the likeliest reason an arriving reminder
    was sometimes not felt with the screen off. `BUZZ_ONLY` must still self-buzz: a nag
    only *updates* a notification already showing and `setOnlyAlertOnce` stops the
    channel re-alerting. Nothing races, because the only self-buzz left lands on the
    quiet channel, which has vibration off. The trade: a channel vibration is
    suppressed in full silent mode, where the app-driven one survived via
    `USAGE_ALARM`; the nag still survives it.
  - **Changing how a channel alerts means a new channel id.** Its settings are frozen
    at creation, so `_v2` → `_v3` when the channel took over the arrival buzz.
    `LEGACY_CHANNEL_IDS` is the migration and `ensureChannel` deletes every older id.
    Getting this wrong fails *silently* — existing installs just never pick the change
    up — so a test asserts the old ids are gone.
  - **Which is why the sound level *is* the channel.** `QUIET_CHANNEL_ID` is
    `CHANNEL_ID` with `setSound(null, null)`, and `Alert.channelId` picks between them.
    Everything after a reminder's first appearance is silent by construction rather
    than by relying on `setOnlyAlertOnce`, which only suppresses re-alerts of a
    notification *still on screen* — and a swipe removes it first, so the re-post is a
    **fresh** post that flag never sees.
  - **Do not "simplify" this to `NotificationCompat.setSilent(true)`.** It silences by
    moving the notification into a group keyed `silent`, which drops it out of the app's
    stack in the shade — measured in `dumpsys notification` as `groupKey=silent` beside
    the normal `ranker_group`/`AUTOGROUP_SUMMARY`, so a swiped reminder visibly jumps
    out of the group. The second channel costs one extra row in the system notification
    settings and nothing else. Four instrumented tests pin the routing.
  - **Don't turn `setOnlyAlertOnce` off to make the nag repeat.** The repeat is ours;
    that flag is what keeps Android from chiming on every interval.
- **The notification follows the device clock, and there is a test pinning it.** It used
  to pass `is24HourFormat` **negated**, so a 24-hour device read "Is due Today, 5:17 PM"
  directly above a row saying "Was due Today, 17:17". Nothing caught it because the tests
  only asserted the "Is due …" prefix.
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
  That bug has already happened once. The sheet is reachable only by tapping the
  notification's **body** — its Snooze action commits to 15 minutes without opening
  anything — because the task list picks absolute times instead.
- **The reminder sheet has no confirm step.** Every chip and every wheel row
  commits on the tap, which is why nothing in it holds a selection: the chips
  have no chosen state and the wheel is passed `selectedIndex = -1`. It is also
  why there is no "back at …" footer — with no held choice there is nothing to
  preview, so each wheel row states the time it lands on instead. Adding a
  highlight back would promise a confirm step that does not exist.
- **On a wheel row the clock time leads and the duration is the aside** — "17:50
  (5 min)", not "5 min (17:50)". The row's whole job is to answer *when does it come
  back*, and the duration is only how that time was arrived at; with no footer to
  preview it, the answer should not be the parenthesised half. `PeskyWheel` styles
  `label` as the primary text and `aside` dimmer, so the swap is entirely in which
  string `ReminderSheet` passes to which — and four tests in `ReminderSheetTest` pin
  the pairing, including the row that has to name a day ("Tomorrow 8:20 PM (30h)").
- **The time chips commit an absolute millis; the duration chips commit a count of
  minutes.** Two callbacks on purpose. Converting a time to minutes-from-now at
  composition time drifts by however long the user takes to tap, and
  `ReminderActivity` snapshots its clock once at `setContent` and never refreshes —
  a sheet left open five minutes would land "Tomorrow 08:00" at 08:05, which is the
  same class of bug as the snooze-from-due-time one below. `Reminders.snoozeUntil`
  stores the target verbatim. Its past-target branch is not dead code: the sheet can
  sit open across the very rung it is offering, and arming `setAlarmClock` in the
  past fires it at once, so it cancels the alarm and leaves the notification live —
  "pester me now", the line `create` takes.
- **The ladder generates from morning/afternoon/evening but never says so.** Once a
  chip reads `13:00`, "afternoon" adds nothing, and four chips only get ~81dp each —
  "Afternoon" at 15sp very nearly fills that alone. Both chip labels come from
  `TaskTime`, so they cannot disagree with the wheel rows below about how a time is
  written, 24-hour included. Verified unclipped at font scale 1.3.
- **Both chip rows share one "Snooze" label.** They are one choice offered two ways —
  how long from now, or what time to land on. "Snooze for" cannot cover the second
  row (*snooze for 20:00* is wrong), which is what forces the neutral single label
  rather than a matched pair.
- **The reminder sheet has two hosts and exactly one implementation.**
  `ReminderActivity` raises it from the notification; `PeskyApp` raises it when an
  **overdue** row is tapped. Same composable, no host parameter — two variants
  would drift at the first fix to either one, which is the same reasoning that
  collapsed add and edit into one `TaskSheet`.
- **Tap means different things by band, and that is deliberate.** An overdue row
  opens the action panel, anything else opens the editor, and *holding* any active
  row opens the editor whatever its band. Holding is what keeps a repeater's only
  exit reachable: Delete lives in the edit sheet, and an overdue repeater — a daily
  9am you have ignored — would otherwise never get there. The rule lives in
  `TaskRow`, which already receives `overdue` for its styling, so there is no
  second copy of the condition.
- **Only overdue rows may raise the panel.** Snooze durations count from the
  clock, so on a task due *tomorrow* a 30-minute snooze would drag it **earlier** —
  the trap the snooze bullet above describes. On something already late every
  duration moves it later, which is what makes the panel safe there and nowhere
  else. Don't widen it to TODAY without solving that first.
- **`ReminderActivity` needs `taskAffinity=""`, and it is load-bearing.** The
  launch carries `FLAG_ACTIVITY_NEW_TASK`; with the default affinity (the package
  name) Android reuses the app's *existing* task and brings it to the front, so
  tapping the notification hauled `MainActivity` up behind the translucent sheet
  and finishing left the user sitting in the app instead of back where they were.
  Verified with `dumpsys activity activities`: before, `ReminderActivity` joined
  MainActivity's task (`sz=2`, opaque); after, it is the root of its own (`sz=1`,
  translucent) and MainActivity's task stays `visible=false`. Exits go through
  `close()` → `finishAndRemoveTask()`, because plain `finish()` leaves that task
  behind.
- **A notification action that shows UI must be an activity PendingIntent.**
  Android 12+ blocks notification trampolines, so the route that opens the sheet
  cannot go through `ReminderReceiver` — the notification's **body tap** targets
  `ReminderActivity` directly. Both *actions* are broadcasts, because neither shows
  anything: Done finishes the task, Snooze 15 min moves it, and each posts a toast.
  Instrumented tests assert the shape of all three.
- **The notification's Snooze button commits, and says how long.** It is
  `"Snooze " + SnoozeOptions.label(SnoozeOptions.QUICK_MINUTES)` → "Snooze 15 min",
  built rather than written out so it cannot drift from the chip offering the same
  duration. It used to open the sheet and could only say "Snooze", which made the
  common answer — not now, a few minutes — cost a tap to be asked a question. Four
  things about it:
  - **15 minutes is `QUICK_MINUTES`, a constant of its own, not `PRESETS.first()`.**
    Deriving it would let a reorder of the chips silently change what the button
    does; a test pins the two together instead, so the reorder fails loudly.
  - **It is the shortest chip on purpose.** The action fires without confirmation,
    so it has to be the answer that is hardest to regret.
  - **`Reminders.snooze` already did the whole job** — cancels the notification and
    the nag chain, keeps a repeater's `anchorMillis` so the cycle does not drag, and
    re-arms the alarm. The receiver branch adds no logic, only the toast, and by
    construction can only see `MOVED` or `MISSING`, never `ALREADY_PAST`.
  - **The body tap is now the sheet's only door from the notification**, so it is
    load-bearing where it used to be redundant. It also needed its own request code
    — `SLOT_OPEN` — the moment the action stopped sharing its PendingIntent.
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
- **Hold-to-edit is undiscoverable.** Nothing on screen advertises it, and it is
  the only way to reach the editor — and therefore Delete — for an overdue task.
  It is documented in the README's tour and nowhere in the UI. An affordance, or
  an Edit row in the action panel, would fix it; both were weighed and dropped in
  `docs/superpowers/specs/2026-07-27-overdue-tap-and-standalone-panel-design.md`.
- **The section-header tap targets are smaller than 48dp.** Both the Done toggle
  and CLEAR are ~18dp tall, because that is the height the design gives the header
  row. Growing either one alone makes the header jump when it expands, and growing
  both changes the section spacing throughout.
