# How loudly the notification announces itself

2026-08-02 · shipped in 0.19.0

Three different events were wearing the same notification and getting the same
full alert. A **swipe** — the gesture you make to get rid of something — answered
back with a chime and a buzz, which reads as the app arguing with you.

## The three levels

`ReminderNotifier.Alert`:

| | sound | buzz | when |
|---|---|---|---|
| `FULL` | yes | yes | the reminder arriving. The moment the app exists for. |
| `BUZZ_ONLY` | no | yes | the nag from Settings. It repeats on an interval; a chime every few minutes is what makes people uninstall. |
| `SILENT` | no | no | the same reminder put back after a swipe, refreshed after an edit that left it overdue, or after a snooze onto a time already gone. |

Callers: `Reminders.notify` is `FULL` and only the alarm firing takes it;
`Reminders.repost` is `SILENT` and has three callers (the delete-intent, the
edit-refresh, the past-snooze refresh); `Reminders.nag` is `BUZZ_ONLY`.

## Why the mechanism is asymmetric

**The buzz is ours. The sound is Android's.** The app drives the vibrator
directly and both channels have `enableVibration(false)` — a pre-existing
decision, because a channel vibration racing ours got ours cut short
(`cancelled_superseded` in `dumpsys vibrator_manager`). So suppressing a buzz is
just not making the call.

The sound cannot be overridden per notification on API 26+. It belongs to the
channel. `setOnlyAlertOnce(true)` suppresses a re-alert of a notification **still
on screen**, which is what already kept the nag quiet — but a swipe *removes* the
notification before the delete-intent runs, so the re-post is a **fresh** post
that the flag never sees. That was the whole bug.

So the sound level *is* the channel: `QUIET_CHANNEL_ID` is `CHANNEL_ID` with
`setSound(null, null)` and identical importance, and `Alert.channelId` picks
between them. A side benefit: everything after a reminder's first appearance is
silent by construction, so a nag that somehow posted fresh still would not chime.

## The road not taken

`NotificationCompat.setSilent(true)` is one line and does work — it was
implemented and measured. It silences by moving the notification into a group
keyed `silent`, and `dumpsys notification` showed the consequence plainly:

```
id=1  channel=pesky_reminders_v2  groupKey=silent          <- the re-post
id=2  channel=pesky_reminders_v2  groupKey=(none)          <- a normal post
id=…  AUTOGROUP_SUMMARY           groupKey=ranker_group
```

A swiped reminder drops out of the app's own stack in the shade. With the two
channels instead, the same check reads:

```
id=1  channel=pesky_reminders_quiet  groupKey=(none)
id=2  channel=pesky_reminders_v2     groupKey=(none)
id=…  AUTOGROUP_SUMMARY              groupKey=ranker_group
```

and the shade still collapses both under "Pesky Reminders 2". The cost is one
extra row in the system notification settings, named "Repeat buzz and re-posts".

A third option — taking ownership of the sound as well, the way the app already
owns the buzz — was weighed and dropped: it would move the reminder onto the
alarm stream (louder, surviving silent mode) and would ignore a custom sound set
on the channel.

## Testing

Four instrumented tests in `ReminderModelTest`, because the channel a post lands
on *is* whether it sounds:

- the reminder arriving lands on the channel with a sound;
- a swipe puts it back on the quiet one — driven through the notification's own
  `deleteIntent`, the only path that proves the real wiring;
- a nag repeats on the quiet one;
- both channels exist, the quiet one has no sound, the loud one still does.

## Who plays the buzz

Originally the app played every buzz itself. That made the arrival buzz **not
atomic with the post**: a separate call issued after `notify()`, whose waveform
can be truncated when the CPU suspends again as `onReceive` returns. That is the
likeliest reason an arriving reminder was sometimes not felt with the screen off.

So the buzz is now split by who plays it:

- `FULL` → **the channel** vibrates. The system plays it as part of posting the
  notification, so nothing about this process's lifetime can cut it short.
  `post()` no longer self-buzzes for it.
- `BUZZ_ONLY` → **the app** vibrates, as before. It has no choice: a nag only
  updates a notification already on screen, and `setOnlyAlertOnce` — which is
  what keeps the nag from chiming — stops the channel re-alerting. Cancel-then-post
  would re-alert but makes the notification flicker and jump the shade.
- `SILENT` → neither, and the quiet channel has vibration disabled.

Nothing races any more, which is what the old "we are the single source of
vibration" comment was protecting against: the only self-buzz left lands on the
quiet channel, which does not vibrate.

**This required a third channel generation.** A channel's alerting is frozen at
creation, so `_v2` → `_v3`; `LEGACY_CHANNEL_IDS` is the migration and
`ensureChannel` deletes every older id. Getting this wrong is silent — existing
installs simply never pick the change up — so there is a test asserting the old
ids are gone.

**The trade.** A channel vibration follows notification rules and is suppressed in
full silent mode, where the app-driven one survived by declaring `USAGE_ALARM`.
The nag's buzz still survives it. Reliability on arrival was judged worth more
than ringing through silent mode; if that proves wrong, the alternative is to keep
`FULL` app-driven and hold a short wakelock (or `goAsync()`) across the waveform
so it cannot be truncated.
