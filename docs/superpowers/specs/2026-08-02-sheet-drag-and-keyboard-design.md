# Sheet drag-to-dismiss, and the keyboard on the new-pester sheet

2026-08-02 · shipped in 0.18.0

Three changes asked for together. Two are about the keyboard on one sheet; the
third belongs to every sheet, so it lands in the shared chrome.

## 1. The grabber is grabbable

`PeskySheet` gains a vertical drag. The sheet follows the finger down, the scrim
fades as it goes, and letting go either finishes the exit or springs it home.

**What drags.** The grabber *and* the title row, as one unit. The grabber alone
is 38×4dp — findable by eye, hopeless as a thumb target. It deliberately stops
short of the body: that scrolls, and one gesture cannot mean both without
nested-scroll arbitration, which buys nothing here. The close button keeps its
own tap, because a drag only claims the gesture once touch slop is exceeded.

**The rule.** Past 35% of the sheet's own height, or flung down faster than
1000dp/s, and it goes; otherwise it springs back. Height is measured
(`onSizeChanged`) rather than assumed, because the four sheets are four
different heights. Upward drags clamp at zero — there is nowhere above home.

On dismissal the sheet animates off the bottom edge *before* `onDismiss` fires,
so it leaves rather than blinks out. Nothing else changed: the scrim tap, the
close button and Back all still dismiss instantly, as they did.

**Where the drag lives, and why it is fussy.** The `draggable` sits on a `Box`
that *parents* its own tap-swallow layer. The first attempt put it on a `Column`
beside the sheet-wide swallow, and that was wrong: hit testing stops at the
topmost sibling that registers, so the draggable shadowed the swallow beneath
it, and because `draggable` does not consume a tap that never becomes a drag,
the tap carried on to the scrim. A tap on the title bar closed the sheet. As a
parent, children are still hit and the swallow does its job.

It could not be fixed by putting a `clickable` on the chrome itself: that sets
`mergeDescendants` and collapses the title and close button into one semantics
node — the trap CLAUDE.md already records.

## 2. The new-pester name field takes focus

A reversal. `NameField` used to refuse focus everywhere, on the reasoning that
the keyboard covers the time pickers before the user has decided whether they
want to type.

That reasoning still holds for **editing** — usually a trip to change the time —
so editing is unchanged. It does not hold for **adding**: the name is the one
thing the sheet cannot supply a default for, and it is the only thing Save waits
on. So `autoFocus` is `existing == null`, and nothing else.

The cost is real but small, and was measured rather than assumed: at font scale
1.3 with the keyboard up, the body scrolls from the first frame, the pinned
footer survives, and the focused field is on screen. Screenshot in the
verification pass.

## 3. Capitalization, and tapping away the keyboard

`KeyboardCapitalization.Sentences` on the name field. A hint to the IME — it
opens in shift state and does not fight someone who means to type lowercase.
It cannot be unit-tested (capitalization is not exposed in Compose semantics),
so it is screenshot-verified.

"Tap elsewhere and the keyboard goes away" is one line in `pressable` and one in
`tap`, via a shared `dismissingKeyboard` wrapper. Every tappable thing in the
app is one of those two, so the rule is true everywhere at once — the wheels,
the tabs, the calendar cells, the chips, Save, the list rows, and the sheets'
tap-swallow layer, which is a `tap {}` onto nothing. Any narrower placement
would be a second copy of the rule to forget.

The one caller that notices is the settings interval, which clamps on focus
loss. That is the wanted behaviour and was already covered.

## Testing

The drag gesture is **not** asserted through Compose's pointer injection.
Robolectric misroutes drags inside these sheets: a `performTouchInput` swipe on
the grabber leaks through to the scrim and dismisses, so such a test passes or
fails for reasons unrelated to the code. This was chased with a throwaway probe
that printed the dismissing stack — the isolated equivalent behaves correctly
under Robolectric, and the real gesture behaves correctly on a device, so it is
the harness.

What is tested instead:

- `shouldDismiss` and `dragFraction` are pure functions, tested exactly —
  threshold, the strictly-past boundary, flings up and down, and the unmeasured
  first frame.
- Structural pins that the drag could silently break: the close button still
  resolves as its own node inside the draggable area, the title is not merged
  away, the scrim still dismisses.
- `assertIsFocused` on the add sheet, `assertIsNotFocused` on the edit sheet —
  the two halves of the focus rule, so neither can drift into the other.
- Tapping a chip releases focus *and* keeps the typed name.

The gesture itself was driven by hand on the emulator: short drag springs back,
long drag dismisses, mid-drag tracks the finger and visibly fades the scrim, on
both the task sheet and the action panel.

## Not done

- The body still does not drag. Adding it means nested-scroll arbitration.
- Back and the close button still dismiss instantly rather than animating out.
  The machinery now exists; it was left alone to keep the change to what was
  asked for.
