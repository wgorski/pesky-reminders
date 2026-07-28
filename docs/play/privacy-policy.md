# Privacy Policy — Pesky Reminders

**Last updated: 28 July 2026**

Pesky Reminders does not collect, transmit, or share any personal data. This
policy explains that in more detail, and what the app's permissions are actually
for.

## The short version

Everything you type into Pesky Reminders stays on your phone. The app has no
account system, no analytics, no advertising, and no ability to reach the
internet — it does not request the `INTERNET` permission, so the operating system
would refuse any attempt to make a network connection.

## What the app stores

The app stores, in its own private storage on your device:

- the name you gave each reminder
- the date and time it is due
- whether it repeats, and how often
- whether you have marked it done
- your preference for whether and how often an unattended reminder buzzes again

That is the whole list. This data is held in Android's app-private storage, which
other apps cannot read. It is never sent anywhere, because the app cannot send
anything anywhere.

## What the app does not do

- No personal or sensitive information is collected.
- No data is transmitted off your device.
- No data is shared with anyone, including the developer.
- No advertising, and no advertising identifiers are read.
- No analytics, crash reporting, or usage measurement.
- No third-party SDKs that collect data. The app's only dependencies are
  Google's own AndroidX and Jetpack Compose UI libraries.
- No account, sign-in, or profile.
- No location, contacts, camera, microphone, photos, or files are accessed.

## Permissions, and why each is needed

| Permission | What it is for |
|------------|----------------|
| **Notifications** (`POST_NOTIFICATIONS`) | A reminder *is* a notification. Without this the app cannot tell you anything. |
| **Alarms & reminders** (`USE_EXACT_ALARM`) | To fire a reminder at the exact minute you chose. Android may otherwise delay alarms to save battery, which would make a reminder set for 08:00 arrive late. |
| **Run at startup** (`RECEIVE_BOOT_COMPLETED`) | Android discards scheduled alarms when the device restarts or the app is updated. This lets the app re-arm your existing reminders afterwards, so they are not silently lost. |
| **Vibrate** (`VIBRATE`) | So a reminder can buzz. |

None of these permissions is used to gather information about you.

## Deleting your data

Uninstalling the app deletes everything it has stored. There is no server-side
copy to request the deletion of, because there is no server.

You can also delete individual reminders inside the app at any time, and clear the
whole completed list from the list screen.

## Children

The app is not directed at children under 13. It collects no data from anyone, of
any age.

## Changes to this policy

If the app ever changes in a way that affects this policy — for example if a
future version added an optional backup feature — this document will be updated
before that version is released, and the date at the top will change.

## Contact

Questions about this policy, or about the app, can be raised on the issue tracker:

- https://github.com/wgorski/pesky-reminders/issues

Play does not require a privacy policy to carry an email address — it collects a
contact address separately in the Console, and that is the one shown on the store
listing. Keeping one out of here means one fewer public address to maintain.

The app is open source, so every claim on this page can be checked against the
code. The absence of the `INTERNET` permission is visible in
`app/src/main/AndroidManifest.xml`.
