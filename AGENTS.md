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

## Local branch and play-client workflow

Keep the user's three roles separate:

- `experimental` contains work that may eventually be released or proposed
  upstream. Do not add private/personal-only features to it.
- `personal` contains the user's private client customizations. Do not push,
  release, or include those changes in an upstream pull request unless the user
  explicitly changes that policy.
- `play-client` is a local-only integration branch whose purpose is to merge
  both `experimental` and `personal`. Do not develop unique features on it,
  merge it back into either source branch, push it, or release from it.

The normal `/home/greg/Documents/Thunder/bin` play client must be built from
the merged `play-client` tree, never from `experimental` or `personal` alone.
When either source branch advances, refresh `play-client` by merging both
branches, preserving the newer shared/bot implementation from `experimental`
and the private features from `personal`; then run `ant res-jar test`, `ant
bin`, and `git diff --check` before deploying the generated client jars to the
normal Thunder `bin`. Preserve dirty work in every worktree, and never use a
play-client refresh as a reason to commit, discard, or relocate unrelated
uncommitted changes.
