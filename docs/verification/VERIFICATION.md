# Pesky Reminders POC — Verification Results

Date: 2026-07-25
Device: headless Android emulator `pesky` (API 35, google_apis, arm64-v8a)
Build: debug APK from branch `build/poc-notification-model`

## The claim being proven

A scheduled reminder appears as a notification the user **cannot swipe away** —
only its own **Snooze** (+5 min) and **Done** actions can clear it. Mechanism:
`setOngoing(true)` + a `deleteIntent` that re-posts the notification whenever it
is dismissed. (On Android 14+ the ongoing flag no longer blocks an individual
swipe; the delete-intent re-post is what defeats it.)

## Automated tests (authoritative, repeatable proof)

- JVM unit suite (`./gradlew :app:testDebugUnitTest`): **2/2 pass**
  (trigger-time + 5-minute snooze math).
- Instrumented suite on the emulator (`./gradlew :app:connectedDebugAndroidTest`):
  **5/5 pass**:
  - `fire_posts_ongoing_notification_with_two_actions` — ongoing flag set, Snooze + Done present.
  - `dismissing_notification_triggers_repost` — fires the notification's OWN
    delete-intent (exactly what the OS sends on a user swipe) and asserts it
    re-posts. This is the authoritative proof of un-dismissability.
  - `done_clears_it` — Done removes the notification.
  - `schedule_sets_an_exact_alarm_clock` — `setAlarmClock` schedules at the requested time.
  - `snooze_clears_and_reschedules_five_minutes_out` — Snooze clears now and reschedules ~5 min out.

## Manual demo (human-facing)

| Step | Screenshot | Result |
|------|-----------|--------|
| 1. Reminder-creation screen | `01-ui.png` | Text field, seconds field, Schedule button |
| 2. Alarm fires after 15s | `02-fired.png` | Ongoing "Pesky Reminder / Buy milk" with Snooze + Done; `dumpsys` shows `flags=ONGOING_EVENT category=reminder actions=2` |
| 3. Swipe the notification away | `03-after-swipe-reposted.png` | **Notification reappears.** Its internal `NotificationRecord` object id changed (`0x0ccc311d` → `0x0bb440f9`), proving it was genuinely dismissed and then re-posted via the delete-intent → receiver → `post()` chain — not merely un-swiped. |
| 4. Tap Done | `04-after-done-gone.png` | Notification gone for good; `dumpsys notification` returns none; no re-post (Done calls `cancel()`, which does not fire the delete-intent). |

## Conclusion

The notification model is demonstrably viable: a reminder can be made to survive
swipe-to-dismiss and only be cleared by its own Snooze/Done actions, with exact
scheduling via `AlarmManager` — no foreground service and no full-screen intent
required.
