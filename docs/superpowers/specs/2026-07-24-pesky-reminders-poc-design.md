# Pesky Reminders — POC Design

**Date:** 2026-07-24
**Status:** Approved for implementation

## Goal

Prove one technical claim: a scheduled Android reminder can appear as a notification
the user **cannot swipe away**. The only ways to clear it are the notification's own
**Snooze** (reschedule +5 min) and **Done** (dismiss permanently) actions.

Everything else about the eventual app (reminder list, editing, sync, styling) is
deliberately out of scope. This is a proof of concept for the notification model only.

## Non-goals (explicitly out of scope for the POC)

- Persisted list of reminders / a database (Room).
- Editing or deleting scheduled reminders from a UI.
- Surviving device reboot (`BOOT_COMPLETED` re-scheduling).
- More than one reminder scheduled at a time. The POC uses a single fixed
  notification id and a single alarm request code; scheduling again replaces the
  previous one. This is sufficient to prove the model.
- Visual/brand design polish.

## Stack & project shape

- **Language:** Kotlin.
- **UI:** Jetpack Compose, a single screen.
- **Build:** single-module Gradle project (`app/`), Android Gradle Plugin + Kotlin.
- **minSdk 26** — notification channels are mandatory from API 26, which is the
  baseline behaviour we build on.
- **targetSdk 35** (Android 15).
- **Dependencies:** AndroidX core, Compose (BOM), Material 3. No third-party libraries.

## Components

### 1. `MainActivity` (Compose)
The single screen:
- A text field for the reminder text (default e.g. "Buy milk").
- A control to pick the fire time. For the POC this is a simple **offset picker**
  ("remind me in N seconds / minutes"), chosen over a time-of-day picker because it
  makes scheduling fast and deterministic to verify on the emulator.
- A **Schedule** button that calls `ReminderScheduler.schedule(...)`.
- On first launch (API 33+) requests the `POST_NOTIFICATIONS` runtime permission.
- Shows simple inline status text ("Scheduled for HH:MM:SS", or a permission hint).

### 2. `ReminderScheduler`
Thin wrapper over `AlarmManager`.
- Schedules with `setAlarmClock()` — the most Doze-reliable alarm type and the
  semantically correct one for a user-facing alarm/reminder.
- Relies on the `USE_EXACT_ALARM` manifest permission, which is auto-granted to
  apps whose function is alarms/reminders (no runtime prompt), so exact firing
  works without the `SCHEDULE_EXACT_ALARM` user grant flow.
- The `PendingIntent` targets `ReminderReceiver` with action `FIRE` and carries
  the reminder text + notification id as extras.

### 3. `ReminderReceiver` (`BroadcastReceiver`)
The hub. Dispatches on intent action:
- **`ACTION_FIRE`** (from AlarmManager): calls `ReminderNotifier.post(...)`.
- **`ACTION_SNOOZE`**: cancels the current notification and reschedules the alarm
  for now + 5 minutes (the real product snooze interval).
- **`ACTION_DONE`**: cancels the notification. It does not come back.
- **`ACTION_REPOST`** (fired by the notification's delete-intent): immediately
  re-posts the notification. On Android 14+ an individual swipe *is* allowed by
  the OS but triggers this delete-intent, so the notification reappears at once —
  this re-post is what actually defeats a swipe.

All handlers are short and synchronous (post/cancel/schedule only), so no
`goAsync()` or foreground service is required.

### 4. `ReminderNotifier`
Builds and posts the notification:
- High-importance notification channel (heads-up capable).
- `setOngoing(true)` — blocks "clear all" and swipe-while-locked. Note: on
  Android 14+ (API 34+) the ongoing flag no longer blocks an *individual* swipe;
  the delete-intent re-post (below) is what covers that case.
- Two action buttons: **Snooze** (→ `ACTION_SNOOZE`) and **Done** (→ `ACTION_DONE`),
  each a `PendingIntent` (broadcast) to `ReminderReceiver`.
- `setDeleteIntent(...)` → `ACTION_REPOST`. When the notification is dismissed
  (swipe, clear-all, or a listener), the OS fires this delete-intent and we
  immediately re-post. The `setOngoing` + `setDeleteIntent` pairing is the
  mechanism the POC is proving — only Snooze/Done (which call `cancel()`, and
  `cancel()` does **not** fire the delete-intent) can actually remove it.
- `setAutoCancel(false)` so tapping the body does not dismiss it.

## Data flow

```
Schedule button
  → ReminderScheduler.schedule(text, whenMillis)
     → AlarmManager.setAlarmClock(...)   [PendingIntent: ReminderReceiver ACTION_FIRE]
        → (at fire time) ReminderReceiver(ACTION_FIRE)
           → ReminderNotifier.post(text)      [ongoing + deleteIntent=ACTION_REPOST]
              ├─ user swipes / clear-all → ACTION_REPOST → post() again   (cannot kill it)
              ├─ Snooze → cancel + schedule(now + 5 min) → fires again later
              └─ Done   → cancel                                          (gone for good)
```

Reminder text and notification id travel through `Intent` extras. No persistence
layer is needed for the POC.

## Permissions (manifest)

- `POST_NOTIFICATIONS` — runtime-requested on API 33+.
- `USE_EXACT_ALARM` — for exact alarm firing without a user prompt (reminder app).

No `FOREGROUND_SERVICE*` permissions and no full-screen-intent permission are
needed, because we deliberately avoided the foreground-service and full-screen
approaches.

## Verification plan

Environment: Android SDK + a headless emulator installed locally (command-line
tools via Homebrew, an API 34/35 system image), driven with `adb`.

Steps, with a screenshot at each:
1. Build and install the APK on the emulator.
2. Launch the app; grant `POST_NOTIFICATIONS`.
3. Schedule a reminder ~15 seconds out; confirm the notification fires.
4. **Swipe it away / "clear all" → confirm it immediately reappears** (the core claim).
5. Tap **Snooze**; confirm it clears now and re-fires after the snooze interval.
   (For fast verification the snooze re-fire is confirmed via a shortened interval
   or by inspecting the rescheduled alarm; the shipped product value stays 5 min.)
6. Tap **Done**; confirm it clears and does **not** reappear.

Success = steps 4 and 6 both hold: cannot be dismissed except via Done, and Snooze
reschedules correctly.

The repeatable, scriptable proof of step 4 is an instrumented test that fires the
posted notification's **own delete-intent** (exactly what the OS sends on a user
swipe) and asserts the notification reappears — this exercises the real
delete-intent → receiver → re-post chain rather than a stand-in. The adb swipe in
the manual demo is the human-facing version of the same thing.

## Risks / notes

- **Android 14+ (API 34+) changed ongoing-notification behavior:** the ongoing
  flag no longer blocks an individual swipe (it still blocks "clear all" and
  swipe-while-locked). The POC therefore does not rely on the flag to stop a
  swipe — it relies on the delete-intent re-post. The manual swipe demo must be
  run on an **unlocked** screen, since a locked screen would block the swipe and
  mask the re-post behavior.
- On Android 14+ some OEMs are aggressive about backgrounded broadcast receivers.
  The `ACTION_REPOST` re-post happens synchronously inside `onReceive`, so it is
  not affected by background-start limits the way launching an activity/service
  would be.
- If a future version needs the reminder to survive reboot or app-kill, that is a
  `BOOT_COMPLETED` receiver + persistence addition — noted as a next step, not part
  of this POC.
