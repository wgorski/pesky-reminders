# CLAUDE.md — Pesky Reminders

Guidance for Claude Code when working in this repo.

## What this is

A proof-of-concept Android app (Kotlin + Jetpack Compose) proving one thing: a
scheduled reminder can appear as a notification the user **cannot swipe away** —
only its own **Snooze** (+5 min) and **Done** actions can clear it.

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

## Common commands

```bash
# Build debug APK
./gradlew :app:assembleDebug

# Install + launch on the emulator
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.peskyreminders.poc/.MainActivity

# JVM unit tests
./gradlew :app:testDebugUnitTest

# Instrumented tests (emulator must be running).
# NOTE: connected-test tasks do NOT support --tests; use the property form:
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.peskyreminders.poc.ReminderModelTest
```

## When I ask you to "expose the apk locally"

Run these steps (produces a debug-signed, installable **release** APK and serves it
over HTTP on **port 9999**):

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

# 1. Build the release APK (debug-signed via the release buildType, so it installs)
./gradlew :app:assembleRelease

# 2. Stage it in the serving dir (gitignored)
mkdir -p dist
cp app/build/outputs/apk/release/app-release.apk dist/pesky-reminders.apk

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
  MainActivity.kt       # Compose screen: text + seconds + Schedule; requests POST_NOTIFICATIONS
  ReminderContract.kt   # constants + pure trigger-time / snooze math
  ReminderScheduler.kt  # AlarmManager.setAlarmClock wrapper
  ReminderReceiver.kt   # BroadcastReceiver: FIRE / REPOST / SNOOZE / DONE
  ReminderNotifier.kt   # builds the ongoing, re-posting notification
app/src/test/...            # JVM unit tests (ReminderContractTest)
app/src/androidTest/...     # instrumented tests (ReminderModelTest) — the real proof
docs/                       # spec, plan, verification (with screenshots)
```

## Android behavior to remember

On Android 14+ (API 34+) the ongoing flag no longer blocks an *individual* swipe
(it still blocks "clear all" and swipe-while-locked). The delete-intent re-post is
what actually defeats a swipe. `NotificationManager.cancel()` (used by Snooze/Done)
does NOT fire the delete-intent, so those clear the notification without re-posting.
