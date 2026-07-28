# Google Play listing — Pesky Reminders

Everything the Play Console asks for, written out so the console session is
copy-and-paste rather than composition. Assets live beside this file.

| Field | Value |
|-------|-------|
| App name (max 30) | `Pesky Reminders` (15) |
| Default language | English (United Kingdom) — the UI writes `08:00` and `Fri, 10:00` |
| App or game | App |
| Free or paid | Free |
| Category | Productivity |
| Tags | Reminders, To-do lists, Personal organiser |
| Contact email | wgorski-dev@googlegroups.com |
| Website | https://github.com/wgorski/pesky-reminders |
| Privacy policy | https://privacy-policy-wg.pages.dev/pesky/ |

Artifact to upload: `app/build/outputs/bundle/play/pesky-reminders-<version>.aab`
(produced by `./gradlew :app:stageReleaseBundle`).

---

## Short description

Max 80 characters. This is 66:

```
Reminders you cannot swipe away — they stay until you act on them.
```

## Full description

Max 4000 characters. This is about 2,500.

```
Pesky Reminders is for the things you keep not doing.

Every other reminder app puts a notification on your lock screen. You flick it
away without reading it, and the thing stays undone. Pesky doesn't let that
happen. Its notification cannot be swiped away, and comes straight back if you
try. The only ways out are the two it offers you: Snooze, or Done.

That is the whole idea, and everything else is built around it.

WHEN SOMETHING IS DUE

The notification arrives like any other — and then it stays. Swipe it and it
reappears in the same place with the same two actions. "Clear all" skips it.
Leave it sitting there and it buzzes again every few minutes until you deal with
it; the interval is yours to set, and you can switch the nagging off entirely.

Tap the notification and a panel floats over whatever you were doing, without
opening the app. Mark it Done, or snooze it — 15 minutes, 30, an hour, three — or
land it on a time of day instead: this evening at 20:00, tomorrow at 08:00.
There is also a dial for anything from 5 minutes to 72 hours. One tap commits.

YOUR LIST, BANDED BY WHEN THINGS ARE DUE

Overdue first, in red, then Today, Tomorrow, This week, Next week and Later.
Finished things collapse into their own section at the bottom. Tap a row to edit
it, or hold any row to edit it whatever band it is in. Tapping an overdue row
brings up the same Done-and-snooze panel the notification opens.

ADDING A PESTER

Name it, pick a time on scroll wheels or on a calendar, and choose whether it
repeats — once, daily, weekly or monthly. A repeating reminder rolls forward to
its next occurrence when you tick it off, rather than disappearing.

WHAT IT DOES NOT DO

No account. No sign-in. No ads. No network access at all — the app does not
request the internet permission, so nothing it knows about you can leave your
phone. Your reminders live in your device's own storage and nowhere else.
Nothing is collected, nothing is shared, nothing is measured.

A NOTE ON EXACT ALARMS

Pesky schedules exact alarms, because a reminder that fires whenever the system
feels like it is not a reminder. It uses the same alarm facility a clock app
does, so your 08:00 means 08:00.

Requires Android 8.0 or later.
```

---

## Contact details

`wgorski-dev@googlegroups.com` everywhere an address is asked for: the Console's
contact email, and the privacy policy's contact section.

Play requires a contact email in the Console and **shows it on the public store
listing**, so it has to be an address that can stay public indefinitely. A group
address is the right shape — it is not a personal mailbox, it survives a change of
job, and its subscriber list can change without the published address changing.

Deliberately *not* a work address. `@nextbank.ph` on a personal side project would
imply Nextbank published it, tie the app to an employer, and stop working the day
the job does.

Play does not require the policy itself to carry an email — it collects one
separately — but having one there costs nothing and is what readers expect.

## Graphics

| Asset | File | Spec |
|-------|------|------|
| App icon | `icon-512.png` | 512×512, 32-bit PNG, no alpha ✅ |
| Feature graphic | `feature-graphic-1024x500.png` | 1024×500, no alpha ✅ |
| Phone screenshots | `screenshots/01–04` | 1080×1920 (9:16), no alpha ✅ |

The icon renders the central 72dp of the adaptive icon's 108dp canvas — the region
a launcher actually shows — so the store icon matches the installed one rather
than looking zoomed out.

The screenshots are the 1080×2400 emulator captures scaled to 1920 tall and padded
sideways with `PeskyColors.Screen`. Play wants 16:9 or 9:16 and the AVD is 9:20;
padding keeps every list row, where cropping to 1920 would have cut one off. The
padding is invisible because it is the app's own background colour.

Four is above Play's minimum of two and meets the four-screenshot threshold for
store-listing feature eligibility.

---

## App content declarations

| Question | Answer |
|----------|--------|
| Ads | No ads |
| App access | All functionality available without special access |
| Content rating | Complete the IARC questionnaire; every content question is "No" → Everyone / PEGI 3 |
| Target audience | 13+ and above. Do **not** tick an under-13 group — that pulls the app into the Families policy programme, which brings requirements this app has no reason to meet |
| News app | No |
| COVID-19 contact tracing | No |
| Data safety | No data collected, no data shared — see below |
| Government app | No |
| Financial features | None |
| Health | No |

### Data safety

The app declares no `INTERNET` permission, so no data can leave the device. Play
counts data as *collected* only when it is transmitted off the device; reminders
held in `SharedPreferences` are not collection.

- Does your app collect or share any of the required user data types? — **No**
- Is all of the user data collected by your app encrypted in transit? — N/A
- Do you provide a way for users to request that their data is deleted? — N/A.
  Uninstalling the app removes everything it has stored.

### Permissions

| Permission | Why | Console treatment |
|-----------|-----|-------------------|
| `POST_NOTIFICATIONS` | The reminder *is* a notification | Runtime permission, no declaration |
| `USE_EXACT_ALARM` | Fire at the minute the user chose (API 33+) | **Restricted — needs the declaration below** |
| `SCHEDULE_EXACT_ALARM` | Same job on API 31–32, where `USE_EXACT_ALARM` does not exist. Capped `maxSdkVersion="32"` | No declaration — the cap is the documented back-compat pattern |
| `RECEIVE_BOOT_COMPLETED` | Re-arm alarms after a reboot or app update | No declaration |
| `VIBRATE` | The notification buzzes | No declaration |

`SCHEDULE_EXACT_ALARM` is not a second bet on the restricted permission — it is
the fix for a real gap. `minSdk` is 26 and the app targets 36, so on Android 12
and 12L the platform does not know `USE_EXACT_ALARM` while still demanding one of
the "Alarms & reminders" permissions, and `setAlarmClock` threw there. The cap
means it never applies on API 33+, verified on device: an API 35 install shows
only `USE_EXACT_ALARM` granted. `ManifestPermissionsTest` pins both halves.

---

## The USE_EXACT_ALARM declaration

This is the one submission risk. `USE_EXACT_ALARM` is granted at install without
asking the user, so Play restricts it to apps whose *core* function is alarms,
timers or calendar events, and asks you to justify it. A reminders app is a
reasonable fit but not a certainty — the permission's own documentation names
alarm clocks and calendars specifically.

Declaration text:

```
Pesky Reminders is a reminder and alarm app. Delivering an alert at a
user-specified moment is not a secondary feature — it is the app's only
function. The user names a task, picks the exact date and minute it is due,
and the app's entire purpose is to alert them at that minute.

The app uses AlarmManager.setAlarmClock() for every reminder, the same API a
clock application uses, and the scheduled time is always one the user chose
explicitly in the UI. There is no background polling, no network activity (the
app does not declare the INTERNET permission) and no other use of the alarm.

An inexact alarm would defeat the feature: Android may defer inexact alarms by
minutes or longer to batch wakeups, so a reminder set for 08:00 could arrive at
08:20 or later. For a "take your medication at 08:00" or "leave for the train
at 07:40" reminder, a late alert is the same as no alert.

The app also honours the user's control over this: reminder nagging can be
switched off entirely in Settings, and every reminder offers one-tap Snooze and
Done actions.
```

**If it is refused**, the fallback is to remove the `maxSdkVersion="32"` cap so
`SCHEDULE_EXACT_ALARM` applies on every version, and add what a revocable
permission requires: an `AlarmManager.canScheduleExactAlarms()` check before every
schedule, an intent to `ACTION_REQUEST_SCHEDULE_EXACT_ALARM` so the user can grant
it in Settings, and a receiver for
`ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` to re-arm alarms the system
cancels on revocation. Note the permission is already declared, so the manifest
half is done — but the runtime half is real code the app does not have today, and
`ManifestPermissionsTest` would need its cap assertion changed. A deliberate
choice to bet on the
declaration rather than build both paths up front.

---

## Privacy policy

**Live at https://privacy-policy-wg.pages.dev/pesky/** — Cloudflare Pages, project
`privacy-policy-wg`. `privacy-policy.md` is the source of truth for wording;
`privacy-policy.html` is the deployed page, self-contained so it needs no build
step.

To update: edit the HTML, then re-deploy the same project name for the same URL.

```bash
STAGE=$(mktemp -d) && mkdir -p "$STAGE/pesky"
cp docs/play/privacy-policy.html "$STAGE/pesky/index.html"
npx --yes wrangler pages deploy "$STAGE" \
  --project-name=privacy-policy-wg --branch=main --commit-dirty=true
```

Stage into a temp folder rather than deploying `docs/play/` directly — that
directory also holds the listing copy and store graphics, none of which belong on
a public URL.

The root (`/`) deliberately 404s; only `/pesky/` exists, leaving room for other
apps' policies under the same domain later. Note the `cloudflared` quick tunnel
used for APK sideloading would **not** have worked here — it mints a new hostname
on every restart, and a dead privacy-policy URL is grounds for removal.

---

## Production access (personal account, created after 13 Nov 2023)

This account type cannot publish straight to production. The path:

1. Create a **closed testing** track and upload the AAB.
2. Recruit **at least 12 testers** who opt in — accept the invite and install the
   app under the Google account the invite went to. Real devices and real
   accounts; emulators and duplicate accounts do not count.
3. Keep them opted in for **14 continuous days**. They need not open the app
   daily, and uninstalling does not reset opt-in — but dropping below 12 restarts
   the clock.
4. Apply for production access. Google says review is usually ≤7 days.

Budget three to four weeks from first upload. The application asks about the app,
the testing process, and production readiness — answer the testing questions from
what actually happened, after the test.

Two things worth writing down while the test runs, because the application asks
for them: what testers said, and what changed as a result.

---

## Before the first upload

- [ ] `./gradlew :app:testDebugUnitTest` green
- [ ] `./gradlew :app:stageReleaseBundle` — the AAB, signed with the upload key
- [ ] Confirm the signer is **not** the debug key:
      `apksigner verify --print-certs app/build/outputs/apk/release/pesky-reminders-<v>.apk`
- [ ] `versionCode` higher than anything already uploaded
- [ ] Privacy policy live at a stable URL
- [ ] Back up `~/.pesky-keys/pesky-upload.jks` and its password somewhere durable

### One thing to tell existing sideload users

Every APK shared over the tunnel so far is signed with the **debug** key. A Play
build is signed with the upload key, so it cannot upgrade those installs in
place — Android refuses a signature change. Those users must uninstall first,
which takes their task list with it (`allowBackup` does not survive a signature
change either). Worth saying so in the last GitHub release before the Play launch.
