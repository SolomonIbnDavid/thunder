# Local bot movement

Thunder deliberately has one small movement boundary:

- Bots own task selection, inventory work, interactions, and the destination.
- `BotMovement` owns local travel and legal object approach.
- Placement owns exact lift/drop geometry and is not part of movement.
- Pearler owns saved water-route strategy; movement only executes its chosen points.

Safety baseline: branch `safety/pre-pathfinder-simplification-2026-09-15`,
commit `3f701fd80`, with 188 navigation-core tests and 1,631 Thunder tests
passing before the simplification work began.

`BotMovement` exposes `moveTo`, `moveToAny`, `approach`, and
`followKnownRoute`. Its only modes are `LAND`, `BOAT_ROUTE`, `BOAT_LOCAL`, and
`BOAT_APPROACH`. Callers receive a typed result and cancellation remains
`InterruptedException`.

## Planning and execution

`MovementScene` is the live geometry adapter. It captures terrain and exact
oriented Gob hitboxes, then delegates route calculation to the pure-Java
`LocalPlanner` in `HavenNavigationCore`.

`ConfirmedRouteRunner` executes a route conservatively:

1. Click one collision-checked corner.
2. Wait for an authoritative movement stop.
3. Confirm the character stopped within that corner's tolerance.
4. Retry a corner no more than twice and never click the next corner while the
   character is moving.

`BotMovement` allows at most two complete replans. A clipped path or staging
leg is only progress toward the original destination; it is never success.
Blocked candidates are skipped rather than snapped to substitute positions,
because only arrival at one of the caller's original candidates is success.

## Object approach

Grounded objects use the same exact polygons shown by the client's hitbox
overlay. The approach flow observes the object, stages outside an occupied
start when necessary, observes again, creates legal interaction poses around
the real footprint, plans to all poses in one search, executes sequentially,
and revalidates both the object and final pose. The nearest blocked side may
therefore lose to a reachable side. Among legal reachable poses, the shortest
actual route wins; clearance is a legality check and only breaks equal-route
ties rather than making the bot orbit to a farther side.

## Movement profiles

`MovementProfile` contains only local terrain and body facts. Land, cart, and
boat clearance stay distinct. Unknown water/shore behavior fails closed in
boat modes.

## Diagnostics

Path to Nearby, local scenarios, occupancy/geometry dumps, NavReplay, and the
debug overlay remain available. World graphs, named-place navigation,
transition state machines, receding-horizon exploration, streaming movement,
and Critical Routes were experimental and have been removed.

Automated tests verify route sequencing, confirmed-stop arrival, retry/replan
limits, clipped-route failure, overlap escape, target invalidation, hitbox-aware
approach, multi-candidate travel, and distinct movement profiles. Live bot
acceptance still requires three clean representative runs per migration phase.
