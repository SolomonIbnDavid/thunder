# New-bot setup prompt (template)

`AGENTS.md` now points agents at the permanent playbook automatically. This
template remains useful when opening a focused new bot-building session: copy
the block below, fill the brackets, and paste it as the first message.

## Template

```
Build a bot for [TASK — what it does, e.g. "auto-milking a cattle roster"].

Trigger/loop: [what starts it, what it repeats on, what stops it]
Resources it consumes/needs: [e.g. water, specific tools, none]
Interactions involved: [containers? specific gobs/resids if known? placement?
  a UI menu/flowermenu? just walking+clicking?]
UI: [does it need a setup window with fields, or is a console command enough?]

Before writing anything:
1. Read AGENTS.md and docs/bot-development-playbook.md completely. Use its
   simplicity-first phases, evidence order, test gates, and live-run gates.
2. Read docs/bot-automation-api.md — mandatory gotchas for this client
   (container right-click vs itemact, FlowerMenu's real attach point, gob
   movement results, Defer thread starvation, diagnostic logging pattern).
3. If movement is involved, read docs/pathfinder-reliability.md and use only
   BotMovement.moveTo, moveToAny, approach, or followKnownRoute from bot code.
4. Pick the closest working reference by behavior. Use MiningBot and
   MiningMaterials for resupply/container-heavy work; ClearCutBot for a full
   area loop; CheeseTrayFiller's Env shape for a pure decision algorithm.
5. Skim src/auto/Bot.java, Equip.java, InvHelper.java, GobHelper.java,
   PositionHelper.java, and BotUtil.java for existing helpers before writing
   new ones. Read WItem/GItem/Inventory/FlowerMenu for the real client message
   instead of guessing click semantics.
6. If the bot needs a setup window, use src/thunder/clearcut/ClearCutSetupWnd.java
   as the layout pattern. For an action-menu entry, follow src/haven/Action.java,
   MenuGrid.makeLocal, and resources/src/local/paginae/add/. If it needs a
   map-zone selection, reuse
   src/thunder/mining/ZonePicker.java + MiningZoneStore.java as-is rather than
   building a new picker.

I will run the game. Build diagnostic file logging into the first version; use
my live observation plus the latest log/snapshot to identify the owning layer,
then make one narrow fix. Do not guess, broaden the architecture, or copy a
second pathfinder to fix an unexplained symptom.

Before implementation, give me the one-sentence loop, explicit non-goals, and
the first live acceptance run.
```

## Why each piece is there

- **docs/bot-automation-api.md first** — every rule in it was a multi-hour live
  debugging session the first time (wrong click type, wrong widget root,
  wrong eat mechanism, thread starvation). Skipping it means re-deriving the
  same bugs.
- **MiningBot as reference, not a library** — there's no extracted bot-building
  API in this codebase (deliberately — see that doc's intro). A new bot copies
  the *pattern* (diagnostic logging, cancellation via `Bot.checkCancelled()`,
  zone/resupply structure if relevant) from working code, not a shared base
  class.
- **`auto.CheeseTrayFiller`** is worth a look instead of MiningBot when the new
  bot is a tight decision algorithm rather than a full live-interaction loop —
  it separates the algorithm from the game via an `Env` interface, so the
  logic itself is unit-testable without the live game. Use that shape if the
  "what to do next" decision is the hard part; use MiningBot's shape if
  walking/container/placement sequencing is the hard part.
- **The user runs the game** — automated tests cannot reproduce every live
  interaction. Ship a diagnostic build, have the user run the smallest
  representative case, then read the resulting log or snapshot before making
  a narrow fix.
- **Plan before implementing** — matches how MiningBot itself was built:
  agree on the shape (what state it tracks, what triggers a resupply/retry,
  what the setup window exposes) before writing the interaction code.
