# Miner Bot V3

Miner Bot V3 is a separate Xtended-menu automation. The existing `:minebot`
command and MiningBot implementation remain V2.

## Straight mode

Start within sight of an existing mine support, select a cardinal direction,
and press Start. V3 anchors the tunnel one tile left of the support and repeats
this cycle:

1. Select the next eleven centerline tiles with Thunder's Mine area action.
2. Let the native action mine the selection and clear ordinary debris. If a
   boulder gob blocks the first unopened tile, V3 equips the pickaxe, chips
   that active-line boulder to exhaustion, and leaves its rock output at the
   frontier for the existing column-stone collector.
3. If the action stops, walk to the furthest opened tile and redraw only the
   remainder of the original eleven-tile leg.
4. Mine one tile to the current heading's right, obtain 30 building stones and
   one Bronze/Wrought Iron bar, and build a Stone Column there.
5. Return to the centerline and continue in the same heading.

Three redraws with no terrain or movement progress stop the run. A server
message containing `too hard` invokes the bounded detour search described
below. Other unexplained failures stop instead of being treated as hardness.

## Too-hard detours

V3 tries eight candidates in a fixed order. From one prior support anchor it
tries right 11, left 11, right 22, and left 22 tiles; it then repeats those
four candidates from two prior support anchors. Every sideways eleven-tile leg
receives a column one tile right relative to that leg's heading. A candidate is
accepted only after V3 completes a new eleven-tile leg in the original heading.

## Supplies

The setup window can remember storage, water, and food areas for the current
login session. Closing the window does not erase them; a new session does.
Areas are recorded in saved-map coordinates and must be on the same cave-map
segment as the mining frontier.

V3 pauses and returns through known saved cave floor when carried water is
empty, energy is below 2,500%, 30 column stones are unavailable at placement,
or carried Bronze/Wrought Iron reaches zero. The configured bar count is a
refill batch: V3 loads 10 by default, spends them down through successive
columns, and returns for another batch only when none remain. During a supply
circuit it fills every drink vessel, eats to 8,000% when the low-energy trigger
fired, restores 30 stones and any exhausted bar batch, consolidates only
eligible bars, and returns to the exact recorded frontier. Before using storage
for stone, it searches backward along the route V3 has mined and returns to the
frontier with loose building stones.

Supply areas are optional at startup. A missing area produces a warning; the
run stops with a specific message only if that supply later becomes necessary.
Cross-level routing through ladders and mineholes is not supported.
Saved-map routes are executed as four-tile cave-mode legs. A failed local leg
first tries nearer route waypoints; if none can be reached, only the exact
failed staging tile is excluded. V3 never expands one failure into a 3x3
barrier that could seal a narrow mine tunnel, and it never marks the requested
destination itself blocked.

## Safety and diagnostics

Miner Bot V3 refuses to replace another active Bot task. The mining threat
watchdog stops and flees for V3 just as it does for V2. Per-run diagnostics are
written under `bin/minerbot-v3-logs/` through Thunder's debug-directory helper.

## Deferred modes

The standing/fan pattern and map-grid coverage miner are intentionally deferred.
Straight mode establishes the shared line-mining, placement, route-memory, and
resupply behavior those later strategies will call.
