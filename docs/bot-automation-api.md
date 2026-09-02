# Bot automation: rules

Load this before writing a new `auto.*` bot. Each rule = trap + fix + code
pointer. Nothing here is a reusable class — go read the named method, copy the
pattern.

- **Open a container**: right-click (button 3) raw `wdgmsg("click", ...)`.
  NOT `Gob.itemact()` (= "use held item on gob," opens nothing). NOT
  `Gob.click()`/`MapView.click()` (button-1 path also calls `pathQueue.start`,
  re-triggers movement, breaks a just-settled state). → `MiningMaterials.openContainerWindow`

- **Container UIs differ**: crate = `Inventory` grid, shift-click
  (`wdgmsg("transfer",...)`). Stockpile = "Take" button, no grid, click once
  per unit. Same `GobTag.CONTAINER` tag for both — try grid, fall back to
  button. → `MiningMaterials.fetchFromZone`
  - Missing tag on a container gob? Add resid to `etc/containers.json5`
    (presence = tag, empty pose arrays OK). Diagnose via
    `MiningMaterials.logZoneContents` (resid + tags per gob), don't guess.

- **Nested buttons**: widget search must be recursive, not direct-children-only
  (Stockpile's "Take" was one level inside an `ISBox`). →
  `MiningBot.findButton` (recursive), `MiningBot.dumpWidgetTree` (structure
  dump when a search comes up empty)

- **"Take" click cap**: use `long` math. `need` can be `Integer.MAX_VALUE`;
  `int` math (`need - count + 5`) overflows negative → takes 0 silently.

- **Eating**: `WItem.itemact(0)` does nothing (verified: energy frozen across
  15+ calls) — same dead bug was in `haven/bot/AutoEat.java` for years. Real
  path: right-click item → wait for `FlowerMenu` → `menu.choose(Petal)` by
  name. Eat straight from the container inventory, never transfer to
  player inventory first. → `MiningMaterials.eatViaFlowerMenu`
  - `forceChoose(...)` only works if set *before* `attach()` — useless once
    the menu's already on screen (that's when you're finding it). Use
    `choose(Petal)` directly instead.
  - `FlowerMenu` attaches above `GameUI`, grabs mouse/keys globally. Search
    from **`gui.ui.root`**, not `gui` — searching `gui` finds nothing.
  - Main-loop code that can't block-and-search (e.g. `AutoEat.tick()`):
    subscribe first, click second —
    `Reactor.FLOWER.first().subscribe(m -> m.forceChoose("Eat")); item.rclick();`

- **Walking to a gob** (not a tile): default arrival radius (`tilesz*0.6`) is
  tile-sized; a gob's collision keeps you further out, so `arrived` never
  fires. Use `MapHelper.GOB_ARRIVE_RADIUS` (`tilesz*1.5`) via the 4-arg
  `walkTo(gui, target, timeoutMs, arriveRadius)`.

- **Before any interact/placement after walking**: wait for `Moving` GAttrib
  to clear (`player.getattr(Moving.class) == null`) — server-authoritative,
  not `walkTo`'s local distance guess. Firing immediately after "arrived" can
  be silently dropped server-side mid-stride. → `MiningBot.waitForMovementSettled`
  (same idiom as `thunder.MilkingAssist`)

- **Energy scale**: `IMeter.meter(0)` is 0.0–1.0. The tooltip % is that value
  × 10000 (display quirk, not a different stat) — "20%" on the bar = `0.20`.
  "Eat at 2600%" = `0.26`. → `MiningBot.LOW_ENERGY_THRESHOLD`

- **Long-running bot task starves the engine**: `auto.Bot` runs a whole bot
  as one `Defer.Future`; that instance's worker doesn't free up until the
  task fully returns, which can starve unrelated work sharing the same
  `Defer` instance (e.g. `TexL.prepare()` texture finalization → placement
  hangs on "Finalizing texture in ..."). Fix: `Defer.Future.ensureExtraWorker()`
  on the specific stalled instance (`Defer`'s own auto-grow only triggers on
  a queue that was already non-empty, so one item landing on a fully-occupied
  pool never grows it). → `haven/Defer.java`; call sites in
  `haven/MapView.java` (`placingBlockerPoolStats`, `boostPlacementPriority`,
  `ensurePlacementBlockerWorker`)

- **Diagnostics**: log to a file, not just console (no scrollback in-game).
  Put the log call *before* any early-return — a trivially-satisfied check
  that returns early before logging produces an empty file with no clue why.
  → `MiningBot`'s `openDiagLog`/`diag`/`dumpWorkerThreadStacks` (also dumps
  every `Defer.Worker` thread's live stack — how the starvation bug above was
  root-caused instead of inferred)
