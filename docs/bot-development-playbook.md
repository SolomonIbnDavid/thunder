# Thunder bot development playbook

This is the starting document for building a new Thunder bot or repairing an
existing one. It records the process that worked during the Clear-Cut and
simple-movement work, including the mistakes that made earlier iterations much
harder than they needed to be.

The short version is:

> Build the smallest complete loop, use the client behavior that already
> exists, observe authoritative results, and add complexity only after a live
> run proves it is needed.

## 1. The ownership boundary

Keep these four concerns separate:

1. **Bot strategy** decides what work to do next, which object to use, when to
   resupply, and when the run is complete.
2. **Local movement** gets the character to a point or a legal interaction
   position. New bots use `haven.pathfinding.BotMovement`.
3. **Interaction execution** sends the same click, menu choice, transfer, or
   drop message that the working client UI sends, then verifies the server's
   result.
4. **Exact placement or route strategy** owns placement angles, packing order,
   water-route generation, and other task-specific geometry. It is not part of
   general movement.

Do not make the pathfinder choose jobs, invent water routes, decide packing
order, click flower-menu actions, or report a temporary staging point as task
success. Do not make the bot reproduce A*, hitbox expansion, waypoint timing,
or local collision avoidance.

For a completely unrelated new bot, copy the *shape* of a nearby working bot,
not its entire implementation. Nurgling and other clients are useful evidence
for resource names, interaction order, and architectural boundaries. They are
not libraries and should not be copied wholesale.

## 2. Define the smallest useful bot before coding

Write a short contract in plain language. It should answer all of these:

- What starts the bot?
- What is one complete successful cycle?
- What exact objects or inventory items are inputs and outputs?
- How are targets identified: gob tag, resource path, menu option, item name,
  selected area, or opened window?
- Which resources are required, and which are optional?
- What observable fact proves each action succeeded?
- What stops the bot normally?
- What failures stop it immediately?
- What is explicitly out of scope for the first version?

Use one sentence for the loop. For example:

> Find the nearest valid target in the selected area, approach it, perform one
> action, verify the target or inventory changed, and repeat until a settled
> survey finds no targets.

The first version should usually omit batching, carts, alternate storage,
automatic resupply, clever prioritization, and recovery beyond a small retry
limit. Those are separate increments after one target works end to end.

## 3. Protect the current work

Before editing:

```sh
git status --short --branch
git log -10 --oneline
```

- Identify the active task branch and all modified/untracked files.
- Treat existing changes as user work. Do not discard, reset, or fold them into
  the new bot accidentally.
- For a large experiment, make a local safety commit/branch when authorized.
  For a normal feature, work on a dedicated local branch or the already chosen
  task branch.
- Never stage runtime or generated material from `build/`, `bin/`, `play/`, or
  `dev-snapshots/`.
- Keep commits small and named for one behavior. Never push unless requested.

This mattered during the Pathfinder cleanup: a safety checkpoint made it
possible to simplify aggressively without risking the working bots.

## 4. Investigate before implementing

Use this order. It prevents most speculative fixes.

### 4.1 Read the permanent guidance

Always read:

- `docs/bot-development-playbook.md` — this process.
- `docs/bot-automation-api.md` — verified client/protocol traps.
- `docs/pathfinder-reliability.md` — the local movement contract, when the bot
  moves.

Read a feature-specific document when the task involves placement, replay,
resources, macros, or another documented subsystem.

### 4.2 Find the nearest working example

Search by behavior, not only by proposed class name:

```sh
rg -n "FlowerMenu|wdgmsg\(\"drop\"|wdgmsg\(\"transfer\"" src
rg -n "BotMovement\.(moveTo|moveToAny|approach|followKnownRoute)" src
rg -n "GobTag|resid\(|children\(WItem.class\)" src
rg -n "<visible menu text>|<resource fragment>|<item name>" src
```

Choose references by problem:

| Need | Start here |
|---|---|
| Cancellable threaded bot | `src/auto/Bot.java` |
| Full area/task loop | `src/thunder/clearcut/ClearCutBot.java` |
| Resupply and mixed container UIs | `src/auto/MiningBot.java`, `src/auto/MiningMaterials.java` |
| Pure decision algorithm | `src/auto/CheeseTrayFiller.java` and its `Env` boundary |
| Land point/object movement | `src/haven/pathfinding/BotMovement.java` |
| Multi-candidate/danger avoidance | `src/thunder/DirectionalForager.java` |
| Preselected boat route | `src/thunder/MusselBot.java` |
| Setup window and selected areas | `src/thunder/clearcut/ClearCutSetupWnd.java`, `src/thunder/mining/ZonePicker.java` |
| Equipment | `src/auto/Equip.java` |
| Personal inventories | `src/auto/InvHelper.java` |
| Placement | `src/haven/pathfinding/ExactPlacementPlanner.java`, `PlacementExecutor.java`, and `PlacementEgress.java` |
| Tight log packing | `src/test/java/thunder/clearcut/TightLogLayoutTest.java` |
| Action-menu registration | `src/haven/Action.java`, `src/haven/MenuGrid.java` |
| Local action resource | `resources/src/local/paginae/add/` |
| Gob tags and container metadata | `src/haven/GobTag.java`, `etc/containers.json5` |
| Raw item mouse behavior | `src/haven/WItem.java`, `src/haven/GItem.java`, `src/haven/Inventory.java` |
| Flower-menu behavior | `src/haven/FlowerMenu.java` |
| Map click protocol | `src/haven/MapView.java` |

### 4.3 Verify names and messages; do not guess

Display names, resource paths, gob tags, and server messages are different
things. Establish which one the code actually receives.

- Inspect recent files in `bin/logs/`, `dev-snapshots/`, and
  `play/proto-recordings/`.
- Use gob inspection/snapshots to capture the real `resid`.
- Read the UI event handler to learn the real message. For example,
  `WItem.checkXfer` proves that Ctrl-left-click sends a GItem `"drop"`
  message; a synthetic take-and-map-drop sequence is not equivalent.
- Account for temporary drawable suffixes such as `[3]` only after confirming
  they occur.
- Test representative positive and negative resource names.

The Clear-Cut boulder bug came from accepting only
`gfx/terobjs/boulder`. Real chip-able boulders were
`gfx/terobjs/bumlings/<material><stage>`. Because classification returned
`null`, the bot never queued them and its completion scan also ignored them.
This looked like skipped work but was not a movement problem.

### 4.4 Use outside clients narrowly

When a local pattern is unclear, inspect a reference client such as Nurgling
if it is present locally. Ask one focused question: what resource family does
it match, which side does it approach, which message does it send, or what
state does it wait for?

Bring back the verified fact, not the whole design. Nurgling taught us that
bushes can use a simple nearby-cell fallback and boulders are called
`bumlings`; copying its full pathfinder would have reintroduced a second
movement architecture.

## 5. Choose the smallest code shape

Not every bot needs every class. Add only what the contract requires.

Common layout:

- `ThingBot.java` — the live state machine and game adapters.
- `ThingRules.java` — pure classification, ordering, thresholds, and
  completion rules.
- `ThingConfig.java` — immutable settings plus validation, only if there are
  settings.
- `ThingSetupWnd.java` — only if the player must configure the run.
- `ThingZoneStore.java` — only if map areas must survive while the setup
  window is open.
- `src/test/java/...` — tests for every pure rule and regression.

Prefer a small `Env` interface when the hard part is deciding what to do next.
Keep live widgets and protocol calls behind that interface and unit-test the
algorithm. Prefer an `auto.Bot` worker when movement, placement, or server
round trips require blocking waits. Prefer a widget `tick()` state machine for
short UI-only click sequences that do not need blocking movement.

Do not introduce a framework because one bot might someday need it. Extract a
shared helper only after the same verified behavior exists in multiple callers
and the ownership boundary is clear.

## 6. Build one vertical slice

The first implementation milestone is one target, not every requested feature:

1. Validate the minimum required setup.
2. Discover one valid target.
3. Resolve it again immediately before acting; gobs unload and instances go
   stale.
4. Move with `BotMovement` if necessary.
5. Send the real client interaction.
6. Wait for a server-authoritative effect.
7. Report success or a specific typed failure.
8. Stop cleanly and restore any temporary state.

Only after that passes live should the bot loop over many targets. Then add,
one at a time: exhaustive survey, inventory handling, batching, resupply,
special placement, and uncommon recovery.

### Movement rules

New bot code uses only these bot-facing operations:

- `BotMovement.moveTo(point, mode)` — one local point.
- `BotMovement.moveToAny(candidates, avoidance, mode)` — choose among valid
  candidates, including cave positions or hazard avoidance.
- `BotMovement.approach(gob, mode)` — reach a legal interaction position
  around the gob's footprint.
- `BotMovement.followKnownRoute(points, mode)` — execute a route chosen by
  the bot's route strategy.

Check the returned status. Success means the character authoritatively stopped
at the original goal or a legal interaction pose. `NO_ROUTE`, `BLOCKED`,
`TARGET_GONE`, `TARGET_MOVED`, `GEOMETRY_UNAVAILABLE`, and `TIMEOUT` are
different failures and should remain distinguishable in logs and user errors.
Cancellation remains `InterruptedException`; call `bot.checkCancelled()` in
every long loop and wait.

Do not call Pathfinder internals, stream movement clicks, or send another
corner while the player is still moving. Do not treat a clipped local route or
observation staging point as arrival.

### Interaction rules

- Mirror the real UI handler's message and button.
- Wait for authoritative movement stop before an interaction. Successful
  `BotMovement` already includes this guarantee.
- Subscribe or snapshot UI state before the click that creates it.
- Search `gui.ui.root` for `FlowerMenu`, not only `GameUI` descendants.
- Select the exact visible option and handle its absence explicitly.
- Verify the outcome by re-reading the target, inventory, progress meter,
  container state, or another server-fed value. A sent click is not success.
- Cap every retry loop and include target identity in the failure.

### Inventory rules

- Understand whether the visible item is a top-level item, a stack wrapper, or
  a child inside `GItem.contents`.
- Perform an action on the same widget the player would click. Ctrl-clicking a
  visible stack drops the stack; recursively clicking its children drops
  individual units.
- Expect item info to throw `Loading` briefly. Poll the authoritative widget
  state rather than sleeping a fixed long interval.
- Use identity when tracking newly created widgets, but use amount/state deltas
  when a server update can merge into an existing stack.
- Preserve unrelated inventory items and fail if the cursor unexpectedly
  holds something the bot does not own.

### Placement rules

Placement is task-specific. Keep its destination, ordering, angle, footprint,
and egress logic out of `BotMovement`.

- Separate the intended anchor from the server-observed final position and
  angle.
- Use real placement footprints, protocol quantization, and recorded results.
- Plan how the character reaches the placement side and how it exits afterward.
- Preserve an aisle or switch sides when a row fills.
- A hard-coded angle is good when orientation is a deliberate invariant. It is
  not a substitute for measuring footprint and spacing.

## 7. Design failure behavior before recovery

Each phase should have a small, explicit contract:

| Phase | Success proof | Typical terminal failures |
|---|---|---|
| Preflight | Required UI, tool, meters, and areas exist | Missing required setup/tool |
| Discover | Target has a verified tag/resid and lies in scope | Settled empty scan |
| Move | Typed movement success and stopped player | No route, timeout, vanished target |
| Interact | Correct menu/action accepted | Menu absent, option absent |
| Verify | Gob/inventory/progress/state changed | No effect after capped attempts |
| Finish | Repeated settled scans show no supported work | Targets remain after capped passes |

Optional resources must have plain behavior. Clear-Cut's final contract is a
good example: a food/water source area is optional; without one, the bot keeps
working until energy is below the threshold or carried water is empty, then
fails with that exact reason. Optional must not mean silently ignored.

Recovery should be local and bounded. Examples: retry the same interaction up
to three times, re-resolve a gob after travel, or try a collision-checked ring
of bush approach points if exact geometry is too weak. Do not respond to one
failure by adding a second general pathfinder or a global state machine.

## 8. Add diagnostics before the first live run

The developer usually cannot reproduce the game state. The first live build
must therefore produce enough evidence to diagnose itself.

At minimum log:

- run start, configuration, and selected areas;
- phase name;
- target id, resource path, and position;
- movement operation, mode, selected goal, typed result, and detail;
- interaction/menu option sent;
- relevant before/after state;
- retry number and limit;
- intended and observed placement coordinates/angles, when applicable;
- final success/failure reason.

Write the log before an early return. Use one stable prefix per subsystem, for
example `MOVE`, `CHIP`, `ROCK-DROP`, or `PLACE`. Log to a file under
`bin/logs/`; in-game chat is useful status, not durable diagnostics.

For scene-dependent geometry or placement, add a snapshot/replay hook early.
See `docs/dev-iteration-toolkit.md`. Retro recordings were decisive for tight
log packing because they showed the server's actual accepted positions and
quantization instead of what the planner intended.

## 9. Testing gates

### Automated gate

Put classification, thresholds, ordering, packing math, and completion rules
in pure code and test them headlessly. Every observed bug should gain the
smallest regression test that could have caught it.

Before handing off:

```sh
git diff --check
ant test
ant bin
git status --short --branch
```

`ant test` runs the navigation-core suite and Thunder's suite. Do not hard-code
an old test count as the definition of success; report the count printed by the
current run. `ant bin` builds the runnable client. Automated tests prove logic
and compilation, not live server behavior.

### Live gate

Grow the live test in controlled stages:

1. One target in open terrain.
2. Several targets, including a blocked or crowded side.
3. One representative edge condition: resource runs out, target vanishes,
   inventory stack merges, area boundary, or route bend.

For a mature bot migration, require three clean representative runs before
moving to the next subsystem. A clean run means the intended task completed,
the bot stopped correctly, no unrelated items or objects were changed, and the
diagnostic log contains no unexplained retry/fallback loop.

When a run fails, record the exact time, screenshot, visible status, and log.
Test one behavior change at a time.

## 10. The effective fix loop

Use this sequence for every reported bug:

1. Restate the visible symptom plainly.
2. Find the last relevant log/snapshot by modification time.
3. Locate the exact phase that made the wrong decision.
4. Decide which layer owns the failure: discovery, classification, movement,
   interaction, inventory, placement, or completion.
5. Compare the code with the real client event path or a known working bot.
6. Form one concrete explanation that accounts for the observation.
7. Make the smallest change in the owning layer.
8. Add a focused regression test or diagnostic line.
9. Run the automated gate and rebuild.
10. Re-run the smallest live scenario that previously failed.
11. Commit that verified behavior separately.

Do not begin with a broad rewrite. A visible “couldn't reach” can be an
unrecognized resource, missing geometry, stale gob, wrong interaction range,
or actual routing failure. Evidence decides which one.

### Fast symptom-to-source map

| Symptom | Check first |
|---|---|
| Object was never attempted | Area containment, tag/resid classifier, loading suffix, settled survey |
| Bot chose a far side | Candidate legality and actual route-cost ordering in movement |
| Bot reached object but no menu | Stopped state, button/message, target id, `FlowerMenu` root |
| Menu action ran but nothing changed | Exact option text, progress start, server-fed before/after state |
| Item remains or drops slowly | Stack wrapper vs children, actual `WItem` Ctrl-click message, fixed sleeps |
| Placement overlaps or leaves large gaps | Real footprint, angle, server quantization, observed final pose |
| Next placement path crosses prior object | Placement order, approach side, preserved aisle, egress |
| Bot says complete with objects left | Classifier coverage, stale survey, insufficient observation sweep |
| Movement works for one bot but not another | Caller mode, candidate set, avoidance, carried/boat body profile |

## 11. Lessons from the Clear-Cut round

These are the durable lessons, not one-off patches:

- **A simple boundary beats a universal pathfinder.** Bots choose work;
  `BotMovement` performs local motion. World routing, streaming controllers,
  bot strategy, and placement did not belong in one system.
- **Movement success must be authoritative.** Reaching a clipped route,
  sending a click, or getting near a target is not arrival. The character must
  be stopped at the original goal or a legal interaction pose.
- **Choose among legal goals by real route cost.** Euclidean proximity alone
  can choose the wrong side; excessive clearance weighting can make the bot
  orbit to a farther side.
- **Placement and movement fail differently.** Once carrying/approach was
  stable, log packing could be diagnosed from intended versus observed drop
  geometry without reopening movement architecture.
- **Recorded behavior beats guessed constants.** Retro logs revealed the
  accepted tight spacing and protocol drift. The final layout used that
  evidence rather than an increasingly elaborate estimate.
- **Keep an exit lane.** A valid drop can still strand the character behind
  the newly placed object. Placement needs explicit egress and side switching.
- **Fallbacks should be narrow.** Bushes with weak geometry received a bounded
  ring of collision-checked approach candidates. That solved the real gap
  without weakening all object approach.
- **Classify the resources the game actually uses.** `bumlings` versus
  `boulder` caused objects to be invisible to both work selection and
  completion. Positive and negative classifier tests are cheap and valuable.
- **Copy real UI semantics.** The fast rock fix came from reading
  `WItem.checkXfer`: Ctrl-click means a GItem `"drop"` message. Acting on the
  visible stack wrapper dropped the whole stack; walking its children was both
  slower and semantically different.
- **Poll useful state, not arbitrary delays.** Watching inventory while the
  chip action ran removed seconds of dead time and reacted immediately when
  the stack appeared.
- **Detailed typed errors save live cycles.** “Could not reach” is too broad.
  Include the movement status/detail, target id/resid, and phase.
- **Optional setup needs explicit runtime limits.** Let the bot work without a
  resupply area, but stop plainly at the agreed energy/water boundary.
- **One issue per commit makes live development reversible.** Classification,
  bush fallback, candidate selection, packing, and fast rock dropping were
  isolated changes that could be inspected or reverted independently.

## 12. Definition of done

A new bot is ready for ordinary use when:

- its scope and non-goals are documented;
- its happy path and terminal failures are explicit;
- it uses `BotMovement` rather than movement internals;
- it mirrors verified client messages for interactions;
- its pure rules and every fixed regression have automated tests;
- `git diff --check`, `ant test`, and `ant bin` pass;
- diagnostics identify every live phase and terminal failure;
- three representative live runs are clean;
- generated/runtime files are not committed;
- the feature is committed in reviewable increments and nothing is pushed
  without permission.

When pointing a future agent at this process, use:

> Read `AGENTS.md`, `docs/bot-development-playbook.md`, and
> `docs/bot-automation-api.md` completely before changing the bot. If it moves,
> also read `docs/pathfinder-reliability.md`. Start with the smallest complete
> live loop and preserve the ownership boundaries in the playbook.
