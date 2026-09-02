# Bot automation: lessons learned

Notes from building `auto.MiningBot`, kept here so the next bot doesn't have to
rediscover the same things the hard way. This is **documentation only** — every
mechanism below still lives inline in `MiningBot`/`MiningMaterials`, exactly where
it was written and verified working; nothing was extracted into a separate API.
Read a bullet, then go look at the method it names.

## Opening a container: right-click, not itemact

`Gob.itemact()` sends `wdgmsg("itemact", ...)` — "use the item in hand on this
gob" — not "open this." It silently opens nothing, and neither does a left click.
The real open is a **right click** (button 3), and it has to be sent as a raw
`wdgmsg("click", ...)` directly rather than through `Gob.click()`/`MapView.click()`
— that path, for a plain click with `CFG.QUEUE_PATHS` on (the default), also calls
`pathQueue.start(mc)` as a side effect, restarting movement toward the container's
own (collision-blocked, unreachable) center the instant the click fires, undoing a
just-confirmed movement-settled state right before the server sees the interact.

See `MiningMaterials.openContainerWindow`.

## Two container UI shapes share one tag

A crate-shaped window has a real `Inventory` grid — shift-click semantics
(`wdgmsg("transfer", ...)`, confirmed against `WItem`'s real shift-click handler).
An in-game **Stockpile** structure instead opens a build-window-style menu with a
"Take" button and no item grid at all — click once per unit wanted. Both share the
exact same `GobTag.CONTAINER` tag, so code has to try the Inventory-grid path
first and fall back to hunting for a "Take" button, not assume one shape.

See `MiningMaterials.fetchFromZone` (branches on `findButton` vs `ExtInventory.inventory`).

If a container gob type isn't tagged `GobTag.CONTAINER` at all, add it to
`etc/containers.json5` — mere presence in that file grants the tag (see
`GobTag.java`'s `ContainerInfo.get(name).ifPresent(...)`); empty pose-index arrays
are fine if unknown, they only gate a cosmetic sub-tag nothing here depends on.
`gfx/terobjs/stockpile-metal` was missing from that file and had to be added —
found via a diagnostic that logged every gob's resid + tag set in a zone
(`MiningMaterials.logZoneContents`) rather than guessed at.

## The Stockpile "Take" button is nested — search recursively

A direct-children-only widget search missed it silently every time, despite the
window itself opening correctly — it was one level inside an `ISBox`. Found via a
recursive widget-tree dump (class names + Button labels) when the direct search
came up empty and guessing why would just have repeated the mistake.

See `MiningBot.findButton` (recursive) and `MiningBot.dumpWidgetTree`.

The "Take" click count is bounded with `long` arithmetic, not `int` — `need` can
legitimately be `Integer.MAX_VALUE` ("take everything available"), and plain `int`
math on that (`need - count + 5`) overflows negative and silently takes zero.

## Eating: flower-menu "Eat", not itemact(0)

`WItem.itemact(0)` — the obvious-looking shortcut, and what the pre-existing
`AutoEat` feature used for years — was confirmed live to do *nothing*: the energy
meter sat at the exact same value across 15+ consecutive calls. The real
mechanism a manual right-click drives is a **flower-menu selection**: right-click
the item, wait for its `FlowerMenu`, select "Eat" via `FlowerMenu.choose(Petal)`
directly — not `forceChoose(...)`, which only takes effect if set *before* the
widget's own `attach()` runs (see `BotUtil.selectFlower` for that pattern), which
isn't the case here since the menu already exists on screen by the time it's
found.

Food is eaten directly out of the container's own inventory this way — it's never
transferred to the player's inventory first.

See `MiningMaterials.eatViaFlowerMenu`. The same `itemact(0)` bug existed in
`haven/bot/AutoEat.java` and was fixed the same way, using
`Reactor.FLOWER.first().subscribe(m -> m.forceChoose("Eat")); item.rclick();`
instead (that file's `tick()` runs on the main game loop and can't block in a
poll loop, so it wires the choice up before the click rather than searching for
the menu after).

### FlowerMenu attaches above GameUI, not inside it

It grabs the mouse/keyboard globally (`ui.grabmouse`/`ui.grabkeys` in its own
`added()`). A widget search rooted at `gui` finds nothing even though the menu is
genuinely on screen — root the search at **`gui.ui.root`** instead.

See `MiningMaterials.eatViaFlowerMenu`'s use of `findNewWidget(gui.ui.root, ...)`.

## Walking to a gob needs a bigger arrival radius than walking to a tile

The default arrival radius (`tilesz * 0.6`) assumes an empty-tile target. A solid
gob's collision stops the player short of its exact center, so the tight default
can report `arrived=false` forever even though the character is visibly standing
right next to it (a barrel, a crate). Use `MapHelper.GOB_ARRIVE_RADIUS`
(`tilesz * 1.5`) — the 4-arg `MapHelper.walkTo(gui, target, timeoutMs,
arriveRadius)` overload — for anything with a hitbox.

## `Moving` must clear before an interact/placement fires

`walkTo`'s own arrival check is a local, distance-based guess that can fire while
the character is still mid-stride, not yet stopped server-side. An interact or
placement commit fired immediately after can be silently ignored by the server
while it's still (server-side) processing movement. `Moving` is a server-driven
GAttrib (set/cleared by the server's own movement messages), so waiting for it to
clear is a genuine "is the server itself satisfied" signal, not another
client-side guess — same idiom `thunder.MilkingAssist` already used.

See `MiningBot.waitForMovementSettled` (private, checks `player.getattr(Moving.class) == null`).

## Energy meter scale: the in-game tooltip is ×10000, not ×100

`IMeter.meter(0)` is a standard 0.0–1.0 fraction (confirmed against
`IMeter.checkStarvation`'s existing, already-trusted 0.20/0.25 thresholds). The
inflated tooltip percentage the player sees (e.g. "9056%") is that same fraction
× 10000 — a server-side display quirk, not a different stat. So "20%" starvation
on the character bar is fraction `0.20`, and "stop mining and eat at 2600%" is
`LOW_ENERGY_THRESHOLD = 0.26`.

See `MiningBot.LOW_ENERGY_THRESHOLD`.

## `Defer` thread starvation

`auto.Bot` runs an entire bot run as **one** `Defer.Future`
(`Bot.java`'s `task = Defer.later(this)`). `Defer.Worker.run()` only returns to
pick up new queued work once the *current* task's `run()` fully finishes — so a
bot's own long-running task can permanently occupy its `Defer` instance's one
worker thread for the run's whole lifetime, starving anything else deferred
through that same instance. The game's own texture finalization
(`TexL.prepare()`) shares that exact mechanism, which is how a placement could
stall indefinitely on `Finalizing texture in ...`: the bot's own thread was the
only thing that could free the worker the texture task needed, and it couldn't,
because it was the one blocked waiting.

`Defer.Future.ensureExtraWorker()` forces a genuinely extra worker into the
*specific* `Defer` instance a stall is blocked on, bypassing `Defer`'s own narrow
spawn heuristic (which only grows a pool when the queue was *already* non-empty
before a new item arrives — a single item landing on a pool fully occupied by one
long-running task never triggers it). If a future bot's own long-running
interaction loop stalls something else the same way, the same fix likely
applies: call `ensureExtraWorker()` on whatever `Defer.Future` (or its
`poolStats()`) the stall is blocked on.

See `haven/Defer.java` (`Future.ensureExtraWorker`, `Future.poolStats`) and
`haven/MapView.java` (`placingBlockerPoolStats`, `boostPlacementPriority`,
`ensurePlacementBlockerWorker`) for the placement-specific call sites.

## Diagnostic logging: write to a file, not just the console

The in-game console has no scrollback access, so a diagnostic session that isn't
also on disk is effectively unreadable after the fact once anything scrolls past.
`MiningBot` opens a plain-text log per run under `Debug.somedir("minebot-logs")`
(`openDiagLog`/`closeDiagLog`/`diag`), and includes a live stack-trace dump of
every `Defer.Worker` thread (`dumpWorkerThreadStacks`, matching `Defer.Worker`'s
own thread naming) — this is how the thread-starvation deadlock above was
root-caused directly instead of inferred from queue/pool counts.

Put diagnostic calls *before* any early return, not after — a trivially-satisfied
check (e.g. "already have enough, return true") that short-circuits before the
log call produces an empty log with no clue why, which happened once here.

See `MiningBot`'s private `diagLog`/`openDiagLog`/`closeDiagLog`/`diag`/
`dumpWorkerThreadStacks`.
