# Fishing Helper

Casting-rod observer, search guide, and persistent bite heat map. Thunder binds
each server bite list to the water point clicked for that cast, remembers the
fish/tackle/percentages, and paints the sampled node in the world.

Toggle: local pagina `paginae/add/fishing_helper` (Adventure extras), keybind
`OPEN_FISHING_HELPER` (no default chord), or `GameUI.toggleFishingHelper()`.

## Files

- `src/thunder/FishingBiteList.java` — fail-closed parser for `"This is bait"`.
- `src/thunder/FishingKit.java` — hand-slot pole / line / hook / lure + gear mean.
- `src/thunder/FishingTackle.java` — species → line/hook/lure lookup (Sevenless dump).
- `src/thunder/FishingMoon.java` — full-moon stale check for peak flags.
- `src/thunder/FishingAdvice.java` — trophy vs food pick + advice strings.
- `src/thunder/FishingHelper.java` — session state, bait-window watcher, label tint.
- `src/thunder/FishingHelperWnd.java` — helper window (lock, kit, advice, peaks).
- `src/me/ender/WindowDetector.java` — `ON_PACK` recognize caption `"This is bait"`.

## Bait window

The list is a generic server `Window` (`@RName("wnd")`) captioned **This is bait**
(`Window.caption()` is English `title`). Children are stock `Button` + `Label`.

No live proto dump is in-tree. Layout is locked to Kami `FishingBot.returnFishWindow`
(commit `1dd288232`): skip until the first `Button`, then each row is that button
followed by labels. Percents in order are **bite** (left), **land** (right), optional
combined. Green is `Label.col` (`uimsg("col")`). Parse `Label.original`, not
translated `texts`.

If any started row lacks a name and two percents, the parse **fails closed** and
the overlay does not tint.

Tint only (no `Button.action` wrap, no auto-click):

- land ≠ 100% → orange land label
- trophy lock match → gold name label

## Modes

- **Food** (default): pick the highest-bite row with land 100% (walk if bite < 50%).
- **Trophy**: lock one species. Green autofish row is ignored unless it is the lock.

Advice (never walks or unequips): empty kit / swap tackle + table hint / not on
this list / walk the node / depleted / ok.

## Kit

Hands (`Equipory` left/right). Pole contents via nested `WItem`s or
`ItemInfo.Contents`. Missing line, hook, or lure/bait → `GameUI` BAD message
when the bait window is open (and again if a lure is lost mid-list).

Gear mean `(pole × line × hook × lure)^(1/4)` is displayed, not used to recast.

## Peaks

Session-best bite per species, listed in the helper. Right-click (or **Pin here**)
plants a `MapWnd2` marker `FISH <species> <bite>% <moon hh:mm>`. After the next
**Full Moon** (`FishingMoon.staleAfterFullMoon`) the row shows STALE. Same-tile
bite drop is depletion, not “walk more.”

## Node search and heat map

Every newly opened bite list records all displayed species with the clicked
water coordinate, player coordinate, line/hook/lure identity, moon/time, bite,
and landing chance. Observations are stored in `fishing-casting-samples.json`
under the normal client configuration directory.

The overlay shows raw samples as numbered colored dots (blue low, yellow
middle, green high), a larger green best-observed point, a lightly interpolated
surface around sufficiently dense samples, and a cyan `NEXT` point. The next
point comes from a locally weighted least-squares gradient. Sparse or duplicate
coverage falls back to the least-tested compass direction around the best point.

The helper window reports sample count, best bite, and confidence. **Same
tackle** prevents unlike observations from being mixed. **Include stale** is
off by default, so observations crossed by a full moon do not guide the fit.
**Clear samples** removes the persistent observations.

`Select suggested` is the only assisted action: it presses the currently
recommended server row. It never walks, casts, or changes tackle.

## Out of scope

Auto-walk, auto-recast, auto-swap, bait pole, nets, Fisher’s Request,
cooking/FEP, Kami `FishingBot`, MapWnd topbar button.
