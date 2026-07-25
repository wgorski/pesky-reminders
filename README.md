# Pesky Reminders

A proof-of-concept Android app that shows **persistent, un-swipeable reminders**.

You create a task, set when to be reminded, and at that time a notification fires
that you **cannot swipe away**. The only ways to clear it are the notification's own
two actions:

- **Snooze** — dismisses it now and brings it back in 5 minutes.
- **Done** — clears it for good.

## Does the notification model actually work?

Yes — and it's proven, not just claimed. See [`docs/verification/VERIFICATION.md`](docs/verification/VERIFICATION.md).

- Automated: JVM unit tests (2/2) + on-device instrumented tests (5/5). The key test
  fires the notification's *own* delete-intent — exactly what Android sends on a user
  swipe — and asserts the notification re-posts.
- Manual demo (screenshots in `docs/verification/`): the reminder fires, is swiped
  away, and **immediately reappears**; then **Done** clears it for good.

| Fired | Swiped → re-posted | Done → gone |
|-------|--------------------|-------------|
| ![fired](docs/verification/02-fired.png) | ![reposted](docs/verification/03-after-swipe-reposted.png) | ![gone](docs/verification/04-after-done-gone.png) |

## How it works

- **Scheduling:** `AlarmManager.setAlarmClock()` (exact, Doze-friendly) with the
  `USE_EXACT_ALARM` permission — no runtime prompt for a reminder app.
- **Un-dismissability:** the notification is `setOngoing(true)` **and** carries a
  `deleteIntent`. On Android 14+ the ongoing flag no longer blocks an individual
  swipe, so the delete-intent is the real guarantee: whenever the notification is
  dismissed, the OS fires the delete-intent and the app re-posts it. Snooze and Done
  clear it via `NotificationManager.cancel()`, which does **not** fire the
  delete-intent — so only they can actually make it go away.
- **No foreground service, no full-screen intent.**

All control flow runs through a single `BroadcastReceiver` (`ReminderReceiver`)
handling four actions: `FIRE`, `REPOST`, `SNOOZE`, `DONE`.

## Requirements

- JDK 17
- Android SDK with platform 35, build-tools 35, an emulator or a device (min SDK 26)
- The Gradle **wrapper** is included (`./gradlew`, pinned to 8.11.1) — don't use a
  system Gradle.

If `adb`/`emulator` aren't on your PATH, export the SDK location first (adjust to
your SDK path):

```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"   # or /opt/homebrew/share/android-commandlinetools
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
```

## Build & run

```bash
# Build and install the debug app on a running emulator/device
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.peskyreminders.poc/.MainActivity
```

On the screen: type a reminder, set "remind me in N seconds", tap **Schedule**, and
wait. When it fires, try to swipe it away — it comes back. Tap **Done** to clear it.

## Test

```bash
./gradlew :app:testDebugUnitTest          # JVM unit tests

# Instrumented tests need a running emulator/device.
# (connected-test tasks use the property form, not --tests)
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.peskyreminders.poc.ReminderModelTest
```

## Download the APK

A debug-signed **release** APK can be served locally on port 9999:

```bash
./gradlew :app:assembleRelease
mkdir -p dist && cp app/build/outputs/apk/release/app-release.apk dist/pesky-reminders.apk
cd dist && python3 -m http.server 9999 --bind 0.0.0.0
```

Then download from `http://localhost:9999/pesky-reminders.apk` (or
`http://<your-LAN-ip>:9999/pesky-reminders.apk` from a phone on the same Wi-Fi).
The release build is debug-signed for easy sideloading; a real release needs its
own keystore.

## Scope (POC)

Intentionally **not** included: a saved-reminders list / database, editing,
multiple simultaneous reminders, and surviving device reboot. This POC proves the
notification model; those are the obvious next steps. See `docs/` for the full
spec, plan, and verification.
