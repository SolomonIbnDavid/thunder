# Thunder repository instructions

For any new bot or change to an existing bot, read
`docs/bot-development-playbook.md` before editing code. Also read
`docs/bot-automation-api.md`; if movement is involved, read
`docs/pathfinder-reliability.md`.

The central ownership rule is:

- The bot decides what work to do and which target or destination to use.
- `haven.pathfinding.BotMovement` owns local travel and object approach.
- Exact object placement remains a separate placement concern.
- A specialized route owner, such as the River Musseler, chooses its route;
  movement only executes that route.

Keep the first implementation to one end-to-end happy path. Add resupply,
batching, alternate strategies, and recovery only after the simple path works
live. Prefer existing helpers and verified client messages over copied systems
or guessed protocol behavior.

Preserve unrelated work in a dirty tree. Do not commit generated/runtime data
from `build/`, `bin/`, `play/`, or `dev-snapshots/`. Run `ant test`, `ant bin`,
and `git diff --check` before handing off a bot change. Do not push branches or
commits unless the user explicitly requests it.
