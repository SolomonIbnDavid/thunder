# Exact object placement integration

Thunder has one placement pipeline for logs, stockpiles, containers, and future
organizer bots. It deliberately keeps three different facts separate:

1. **Grounded movement geometry** says where the player may walk.
2. **Physical placement geometry** says whether the server can put an object at
   an exact coordinate and angle.
3. **Interaction approach geometry** says where the player can stand to use an
   object.

A lifted object contributes no movement collision. Its grounded collision is
used again only after the server accepts placement.

## Shared pipeline

| Responsibility | Entry point | Contract |
| --- | --- | --- |
| Resource facts | `ObjectSpatialProfiles.resolve(resname)` | Returns separate navigation and placement facts, their sources, gap confidence, player-overlap rule, and failure signal. |
| Live physical shape | `PlacementGeometry.relative(gob, angle)` | Reads placement geometry without navigation padding and rotates it to the requested angle. |
| Exact packing | `ObjectOrganizer.planExact(...)` | Packs exact polygons inside the selected `Area` with continuous world coordinates. The default is front-edge packing; long-object callers may request side-by-side rows. |
| Placement staging | `PlacementExecutor` | Uses `BotMovement` to reach an open point within confirmed placement range of a lifted object's anchor. Stockpile creation separately stages roughly one tile outside the selected area. |
| Live placement | `PlacementExecutor` | Arms the real ghost and sends its exact coordinate/angle in Shift fine-placement mode. |
| Ordinary approach | `BotMovement.approach(...)` | Chooses at most one stable face-center port per cardinal side and never targets the object's center. |

`ExactPlacementPlanner` is the pure geometry engine beneath
`ObjectOrganizer`. It rejects any candidate whose full polygon extends outside
the selected area or conflicts with an existing grounded object.

## Confirmed game rules represented in code

- A carried overhead object has no active movement hitbox.
- The placement rectangle is a strict boundary: the whole placed shape stays
  inside it.
- The target coordinate and angle are preserved through the live ghost.
- Placement is sent in Shift fine-placement mode, not regular tile-snap mode.
- Before a lifted-object commit, Thunder reaches a collision-free staging point
  0.75 tile from the chosen anchor. This remains inside the placement reach
  observed in the manual log-row recording, so the game's final automatic
  movement cannot become a long route through a placed row.
- An ordinary object may overlap the player during placement.
- A new stockpile may not overlap the player's hitbox.
- Ordinary invalid placement can fail silently, so the caller must verify that
  the carried object became grounded at the intended pose.
- Stockpile success is verified by observing a newly created stockpile. A game
  error message is useful telemetry but is not the only success/failure test.

## Current geometry confidence

Nurgling's `NHitBox` catalog is used for grounded navigation fallbacks.
Nurgling's `NModelBox`/Neg concept is used as the model for physical placement
geometry. Thunder does not assume those two shapes are interchangeable.

The ordinary tree-log placement box is confirmed as `20 × 4` world units, and
the manual parallel-log recording confirms a `0.125` world-unit gap. The same
gap is the provisional default for other object families. Stockpile sizes are
currently catalog-backed and explicitly marked provisional until each family
has a live placement example.

When a server-observed placement succeeds after a wider retry,
`ObjectSpatialProfiles.confirmPlacementGap(...)` updates that resource family
for the current client session. Planned-but-unverified placements must never be
recorded as confirmed.

## Planning an ordinary lifted object

```java
Gob player = gui.map.player();
double angle = requestedAngle;
ExactPlacementPlanner.Shape physical = PlacementGeometry.relative(carried, angle);
List<ExactPlacementPlanner.Shape> occupied = observeGroundedPlacementShapes();

ObjectOrganizer.ExactPlan plan = ObjectOrganizer.planExact(
    physical,
    selectedTiles,
    player.rc,
    12,
    angle,
    ObjectSpatialProfiles.resolve(carried.resid()).placementGap,
    occupied,
    "live-placement"
);

for (ObjectOrganizer.ExactPlacement p : plan.placements) {
    PlacementExecutor.Result sent = PlacementExecutor.commitLifted(
        gui, bot, carried, selectedTiles, p.anchor, p.angle,
        20_000L, 6_000L, NamedPlaceNavigator.NOOP
    );
    if (!sent.sent()) break;
    verifyGroundedAt(p.anchor, p.angle); // required: ordinary failure may be silent
}
```

The planner returns an **object anchor**, not a tile corner and not a stand
position. `p.shape` is the resulting world polygon. Callers should re-observe
grounded obstacles after each server-accepted placement before planning the next
object.

## Planning a stockpile

Stockpiles use the same `ObjectOrganizer.ExactPlan` and exact polygon planner,
but `StockpilePlacement` owns the itemact/create verification:

```java
ObjectOrganizer.ExactPlan plan = StockpilePlacement.planExact(
    gui, stockpileResource, selectedTiles, count);

for (ObjectOrganizer.ExactPlacement p : plan.placements) {
    StockpilePlacement.Result result = StockpilePlacement.createHeld(
        gui, bot, stockpileResource, selectedTiles, p,
        20_000L, 6_000L, listener);
    if (!result.success) break;
}
```

The former `LayoutPlanner`/`LayoutFootprint` organizer methods remain deprecated
for source compatibility. No live Thunder placement bot should call them.

## Migrated bots

| Bot or feature | New behavior |
| --- | --- |
| Clear Cut | Exact log polygons at one fixed `0.0` world angle, tightly packed side-by-side rows with the recorded `0.125` gap, collision-aware travel to an open staging point, fine coordinate/angle commit, observed-ground verification, and post-drop egress. |
| Log Cutter | Exact stockpile planning and stockpile-overlap enforcement. |
| Stockpile Organizer | Same exact stockpile plan/executor as Log Cutter. |
| Object Organizer API | Generic live-object or explicit-polygon exact planning. |
| Fish Spit Roaster | Stable `BotMovement.approach`; the direct center-offset fallback was removed. |
| Directional Forager | Stable `BotMovement.approach` normally; its separate moving-aggressive exclusion route remains active when danger exists. |

Cupboard Catalog keeps its specialized packed-cupboard rules. Mining Bot keeps
its construction-ghost workflow. Board Stockpile Worker has its own remote
worker protocol. Those systems are regression-tested around this change but
are not ordinary lift-and-place callers.

## Adding a new placement bot

Before adding a resource family, capture one real manual placement example and
answer only the facts the shared profile needs:

- What is the physical placed shape at the requested angle?
- What minimum server-accepted gap was observed?
- May the placed object overlap the player?
- What authoritative signal proves success or failure?

Do not infer these rules from visual sprite size or grounded navigation
collision. Add the observed fact to `ObjectSpatialProfiles`, then use
`ObjectOrganizer` and `PlacementExecutor` rather than adding bot-local placement
math.
