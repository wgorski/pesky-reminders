# Informacje o wersji / Release notes

The Play Console field is **"What's new in this release"** (pl: *Informacje o
wersji*), set per release on each track. **Max 500 characters per language.**

These notes are shown to closed-test testers first, and carry over when the
release is promoted to production — they can be edited at either stage. For a
first release, "First release." alone is a wasted field: this is the text that
appears under *What's new* on the listing, so it is worth saying what the app is.

Keep the app's own voice: plain statements, no exclamation marks, no "Enjoy!".

---

## 0.16.0 — en-GB (default listing language)

332 of 500 characters.

```
First release.

A reminder you cannot swipe away. It stays on screen until you either snooze it or mark it done, and comes straight back if you dismiss it.

- Tasks banded by when they are due, overdue first
- Snooze by duration, or onto a time of day
- Repeats once, daily, weekly or monthly
- No account, no ads, no network access
```

## 0.16.0 — pl-PL (only if you localise the listing)

363 of 500 characters. Polish runs ~10% longer than English here, which is worth
remembering if a future release note is near the cap in English.

```
Pierwsza wersja.

Przypomnienie, którego nie zamkniesz przesunięciem. Zostaje na ekranie, dopóki go nie odłożysz albo nie odhaczysz — a jeśli je zamkniesz, wraca.

- Zadania pogrupowane wg terminu, zaległe na górze
- Odłóż o wybrany czas albo na konkretną godzinę
- Powtarzanie: raz, dziennie, tygodniowo, miesięcznie
- Bez konta, bez reklam, bez dostępu do sieci
```

**Before adding Polish:** the app's interface is English-only — there are no
localised string resources, and every label in the UI is hardcoded English. A
Polish listing that leads to an English app reads as abandoned localisation, and
it invites 1-star "nie ma polskiego" reviews. Either ship the listing in English
only, or localise the app first. English-only is the honest choice today.

---

## 0.19.0 — en-GB

439 of 500 characters.

```
Sheets close with a flick now — take hold of the bar at the top of any sheet and throw it downwards.

Adding a pester opens with the keyboard ready and the first letter capitalised. Tap anywhere else and it gets out of the way.

Swiping a reminder away no longer answers back with a sound and a buzz; it just comes back, which was always the point. The repeat buzz has lost its chime too — the sound belongs to the reminder arriving, once.
```

---

## 0.20.0 — en-GB

351 of 500 characters.

```
The notification's Snooze button now says what it does, and does it in one tap: Snooze 15 min clears the reminder, pushes it a quarter of an hour and tells you where it landed.

Every other snooze is still a tap on the notification itself. Those rows now lead with the time they land on — 17:50 (5 min) — because the time is the thing you are picking.
```

---

## Format for later releases

Describe what changed for the user, not what changed in the code. The list rows
and the notification wording are user-visible; a refactor is not.

Good:

```
Snooze now offers a time of day as well as a duration — this evening at 20:00,
tomorrow at 08:00 — instead of only counting minutes forward.
```

Bad:

```
Refactored ReminderSheet, added Reminders.snoozeUntil, bumped AGP.
```

If a release contains nothing a user would notice, say so plainly ("Internal
fixes, no visible changes.") rather than inventing a feature.

## Uploading several languages at once

In the Console UI each language is a tab. Via the API or Gradle Play Publisher the
notes are tagged:

```
<en-GB>
First release.
...
</en-GB>
<pl-PL>
Pierwsza wersja.
...
</pl-PL>
```
