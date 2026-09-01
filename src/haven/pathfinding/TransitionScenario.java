package haven.pathfinding;

import auto.Bot;
import haven.Area;
import haven.Coord;
import haven.Coord2d;
import haven.GameUI;
import haven.Gob;
import haven.Moving;
import haven.UI;
import haven.nav.GraphEdge;
import haven.nav.GraphNode;
import haven.nav.MobilityProfile;
import haven.nav.NavGoal;
import haven.nav.NavOutcome;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/**
 * Phase 5 allowlisted transition / mobility / explore scenarios.
 * Missing fixtures fail closed. Click is not transition success.
 */
final class TransitionScenario implements PfTestRunner.Scenario {
   enum Kind {
      DOOR_GATE("transition_door_gate", GraphEdge.Kind.DOOR_GATE, false),
      CELLAR_STAIRS("transition_cellar_stairs", GraphEdge.Kind.CELLAR_STAIRS, false),
      CAVE("transition_cave", GraphEdge.Kind.CAVE, false),
      LADDER("transition_ladder", GraphEdge.Kind.LADDER, false),
      MINEHOLE("transition_minehole", GraphEdge.Kind.MINEHOLE, false),
      BOAT_BOARD("boat_board", GraphEdge.Kind.BOAT, false),
      BOAT_TRAVEL("boat_travel", GraphEdge.Kind.BOAT, false),
      BOAT_DISEMBARK("boat_disembark", GraphEdge.Kind.BOAT, true),
      VEHICLE_ENTER("vehicle_enter", GraphEdge.Kind.VEHICLE, false),
      VEHICLE_TRAVEL("vehicle_travel", GraphEdge.Kind.VEHICLE, false),
      VEHICLE_EXIT("vehicle_exit", GraphEdge.Kind.VEHICLE, true),
      HEARTH("hearth_travel", GraphEdge.Kind.HEARTH, false),
      EXPLORE("explore_frontier", GraphEdge.Kind.EXPLORE_FRONTIER, false);

      final String name;
      final GraphEdge.Kind edge;
      final boolean vehicleExit;

      Kind(String name, GraphEdge.Kind edge, boolean vehicleExit) {
         this.name = name;
         this.edge = edge;
         this.vehicleExit = vehicleExit;
      }
   }

   private final Kind kind;

   TransitionScenario(Kind kind) {
      this.kind = kind;
   }

   @Override
   public String name() {
      return this.kind.name;
   }

   @Override
   public JSONObject execute(PfTestRunner.Run run, UI ui) throws Exception {
      GameUI gui = ui == null ? null : ui.gui;
      boolean inGame = gui != null && gui.map != null;
      boolean playerPresent = false;
      boolean idle = true;
      if (inGame) {
         synchronized (ui) {
            Gob me = gui.map.player();
            playerPresent = me != null && me.rc != null;
            idle = me == null || me.getattr(Moving.class) == null;
         }
      }
      List<JSONObject> checks = new ArrayList<JSONObject>(
         PfTestHarness.autoMovePreflightChecks(this.kind.name, inGame, playerPresent, true, idle, Bot.hasCurrent())
      );
      if (!inGame || !playerPresent) {
         return failClosed(checks, "NO_GAME", "not in game");
      }
      GraphNode source = WorldGraphAdapter.current(gui);
      if (source == null) {
         source = GraphNode.of(WorldGraphAdapter.worldId(gui), "unknown", "unknown", "unknown");
      }
      WorldGraph graph = new WorldGraph();
      graph.observe(source);
      Gob me;
      synchronized (ui) {
         me = gui.map.player();
      }
      NavGoal original = NavGoal.point(me.rc);
      MobilityProfile mob = WorldGraphAdapter.mobility(me);
      PathfinderLog.recordGraph(evidence(source, null, this.kind.edge, "", original, mob, mob, null, "", false));
      if (this.kind == Kind.EXPLORE) {
         return explore(run, ui, gui, checks, graph, source, original);
      }
      if (this.kind == Kind.BOAT_TRAVEL || this.kind == Kind.VEHICLE_TRAVEL) {
         return travel(checks, graph, source, original, me, mob);
      }
      if ((this.kind == Kind.BOAT_DISEMBARK || this.kind == Kind.VEHICLE_EXIT) && me.vehicleId() == 0L) {
         return failClosed(checks, "NO_FIXTURE", "not aboard");
      }
      PrototypePathfinder.Scene scene;
      synchronized (ui) {
         scene = PrototypePathfinder.observe(gui);
      }
      PrototypePathfinder.GobGeom gob = pick(scene);
      if (gob == null) {
         return failClosed(checks, "NO_FIXTURE", "no automatic fixture for " + this.kind.name);
      }
      checks.add(PfTestRunner.check("fixture", true, this.kind.name + " #" + gob.id));
      TransitionMachine machine = new TransitionMachine(original, source, Long.toString(gob.id), this.kind.edge, null, this.kind.vehicleExit);
      haven.nav.InteractionSpec spec = InteractionAdapter.fromGob(
         gob, haven.nav.InteractionSpec.ALL_SIDES, 1.0, 35.0, 1, null, InteractionVerifier.STATE_CHANGED
      );
      InteractionGoals.Result pose = InteractionGoals.select(scene.player, spec, scene.occupancy);
      PathfinderLog.recordOccupancy(scene.occupancy);
      machine.selectApproach(pose.ok() && pose.selected != null);
      if (!pose.ok() || pose.selected == null) {
         PathfinderLog.recordGraph(evidence(source, null, this.kind.edge, gob.resid, original, mob, mob, machine.state(), "approach_unavailable", false));
         return failClosed(checks, "NO_POSE", "no reachable interaction pose");
      }
      checks.add(PfTestRunner.check("pose_selected", true, "stand " + PfTestHarness.pt(pose.selected.world)));
      List<Coord2d> route = pose.plan.smoothedRoute;
      if (route.size() < 2) {
         route = new ArrayList<Coord2d>();
         route.add(scene.player);
         route.add(pose.selected.world);
      }
      Bot bot = Bot.execute(new Bot.BotAction[0]);
      WaypointWalker.Result walk = WaypointWalker.execute(
         WaypointWalker.liveEnv(gui), bot, route, 0, 60000L, WaypointWalker.Params.DEFAULT, NamedPlaceNavigator.NOOP, pose.plan.status, pose.selected.world, spec
      );
      boolean arrived = walk == WaypointWalker.Result.READY_TO_INTERACT;
      checks.add(PfTestRunner.check("arrival", arrived, arrived ? "server-confirmed pose" : "walk " + walk));
      if (!arrived) {
         machine.timeout();
         return failClosed(checks, "NO_ARRIVAL", "did not arrive at approach");
      }
      machine.arrived();
      synchronized (ui) {
         scene = PrototypePathfinder.observe(gui);
         me = gui.map.player();
      }
      PrototypePathfinder.GobGeom live = find(scene, gob.id);
      boolean still = InteractionVerifier.arrivedConfirmed(!scene.moving, scene.player, pose.selected.world, 2.475);
      machine.revalidate(live != null, live != null, still);
      if (machine.state().phase != TransitionMachine.Phase.INTERACT) {
         PathfinderLog.recordGraph(evidence(source, null, this.kind.edge, gob.resid, original, mob, mob, machine.state(), machine.state().reason, false));
         return failClosed(checks, machine.state().reason, "revalidation refused");
      }
      Gob target;
      synchronized (ui) {
         target = gui.map.glob.oc.getgob(live.id);
      }
      if (target == null) {
         return failClosed(checks, "NO_FIXTURE", "fixture gone at interact");
      }
      int sdt0 = live.gateState;
      long veh0 = me.vehicleId();
      machine.interact();
      target.rclick(0);
      checks.add(PfTestRunner.check("interaction_issued", true, "one interaction"));
      AuthWait wait = awaitAuth(run, ui, gui, gob.id, sdt0, veh0, source, machine.state().auth);
      GraphNode landing = wait.node;
      if (landing != null) {
         graph.observe(landing);
      }
      MobilityProfile after;
      synchronized (ui) {
         after = WorldGraphAdapter.mobility(gui.map.player());
      }
      machine.capture(landing, after, wait.ambiguous);
      if (machine.state().phase == TransitionMachine.Phase.SUCCEEDED && landing != null) {
         graph.learn(source, landing, this.kind.edge, Long.toString(gob.id));
      }
      boolean ok = machine.state().phase == TransitionMachine.Phase.SUCCEEDED;
      checks.add(PfTestRunner.check("transition", ok, ok ? "authoritative landing" : machine.state().reason));
      checks.add(PfTestRunner.check("original_goal", original.position != null && original.kind == NavGoal.Kind.POINT, "original POINT goal preserved"));
      PathfinderLog.recordGraph(
         evidence(source, landing, this.kind.edge, gob.resid, original, mob, after, machine.state(), machine.state().reason, ok)
      );
      JSONObject facts = facts(source, landing, original, machine, ok);
      return PfTestHarness.body(checks, ok ? "transition observed" : machine.state().reason, facts);
   }

   private JSONObject travel(List<JSONObject> checks, WorldGraph graph, GraphNode source, NavGoal original, Gob me, MobilityProfile mob) {
      boolean aboard = me.vehicleId() != 0L;
      if (!aboard) {
         return failClosed(checks, "NO_FIXTURE", "not aboard");
      }
      boolean waterOk = this.kind.edge != GraphEdge.Kind.BOAT || TerrainPolicy.waterTravelLegal(mob);
      boolean terrainOk = this.kind.edge != GraphEdge.Kind.VEHICLE || mob.cart || mob.land;
      checks.add(PfTestRunner.check("aboard", true, "vehicleId=" + me.vehicleId()));
      checks.add(PfTestRunner.check("mobility", waterOk && terrainOk, waterOk ? "policy matches vehicle" : "water forbidden"));
      checks.add(PfTestRunner.check("original_goal", original.kind == NavGoal.Kind.POINT, "original goal preserved"));
      PathfinderLog.recordGraph(evidence(source, source, this.kind.edge, "", original, mob, mob, null, waterOk ? "" : "mobility_unavailable", waterOk));
      JSONObject facts = new JSONObject()
         .put("refusal", waterOk ? JSONObject.NULL : "mobility_unavailable")
         .put("kind", this.kind.name())
         .put("reached", false)
         .put("source", source.toString());
      return PfTestHarness.body(checks, waterOk ? "travel policy confirmed" : "mobility unavailable", facts);
   }

   private JSONObject explore(
      PfTestRunner.Run run, UI ui, GameUI gui, List<JSONObject> checks, WorldGraph graph, GraphNode source, NavGoal original
   ) throws Exception {
      NamedPlaceNavigator.Location loc = NamedPlaceNavigator.liveState(gui).current();
      if (loc == null || gui.mapfile == null || gui.mapfile.file == null) {
         return failClosed(checks, "NO_FIXTURE", "no map segment for frontier");
      }
      Area bounds = NavigationTestSpotScenario.selectBounds(loc.tile);
      MapFileTileSource tiles = MapFileTileSource.of(gui.mapfile.file, loc.seg, bounds);
      Coord start = loc.tile.sub(bounds.ul);
      Coord hint = Coord.of(Math.max(0, tiles.width() - 2), start.y);
      ExploreFrontier.Result pick = ExploreFrontier.select(tiles, start, hint, 4096);
      if (!pick.picked()) {
         return failClosed(checks, pick.outcome == NavOutcome.BUDGET_EXHAUSTED ? "BUDGET_EXHAUSTED" : "NO_FIXTURE", pick.reason);
      }
      checks.add(PfTestRunner.check("frontier", true, "cell " + pick.cell));
      checks.add(PfTestRunner.check("original_goal", original.kind == NavGoal.Kind.POINT, "original goal preserved"));
      PathfinderLog.recordGraph(evidence(source, source, GraphEdge.Kind.EXPLORE_FRONTIER, "", original, MobilityProfile.land(), MobilityProfile.land(), null, pick.reason, true));
      JSONObject facts = new JSONObject()
         .put("kind", this.kind.name())
         .put("frontier", pick.cell.x + "," + pick.cell.y)
         .put("reason", pick.reason)
         .put("reached", false)
         .put("source", source.toString());
      return PfTestHarness.body(checks, "safe frontier selected", facts);
   }

   private PrototypePathfinder.GobGeom pick(PrototypePathfinder.Scene scene) {
      PrototypePathfinder.GobGeom found = null;
      for (PrototypePathfinder.GobGeom g : scene.gobs) {
         if (!matches(g)) {
            continue;
         }
         if (found != null) {
            return null;
         }
         found = g;
      }
      return found;
   }

   private boolean matches(PrototypePathfinder.GobGeom g) {
      GraphEdge.Kind k = TransitionAdapter.kind(g.resid);
      if (this.kind == Kind.DOOR_GATE) {
         return k == GraphEdge.Kind.DOOR_GATE;
      }
      if (this.kind == Kind.CELLAR_STAIRS) {
         return k == GraphEdge.Kind.CELLAR_STAIRS;
      }
      if (this.kind == Kind.CAVE) {
         return k == GraphEdge.Kind.CAVE;
      }
      if (this.kind == Kind.LADDER) {
         return k == GraphEdge.Kind.LADDER;
      }
      if (this.kind == Kind.MINEHOLE) {
         return k == GraphEdge.Kind.MINEHOLE;
      }
      if (this.kind == Kind.BOAT_BOARD || this.kind == Kind.BOAT_DISEMBARK) {
         return k == GraphEdge.Kind.BOAT;
      }
      if (this.kind == Kind.VEHICLE_ENTER || this.kind == Kind.VEHICLE_EXIT) {
         return k == GraphEdge.Kind.VEHICLE;
      }
      if (this.kind == Kind.HEARTH) {
         return k == GraphEdge.Kind.HEARTH;
      }
      return false;
   }

   private static PrototypePathfinder.GobGeom find(PrototypePathfinder.Scene scene, long id) {
      for (PrototypePathfinder.GobGeom g : scene.gobs) {
         if (g.id == id) {
            return g;
         }
      }
      return null;
   }

   private AuthWait awaitAuth(
      PfTestRunner.Run run, UI ui, GameUI gui, long sourceId, int sdt0, long veh0, GraphNode source, TransitionMachine.Auth auth
   ) throws InterruptedException {
      long deadline = System.currentTimeMillis() + (auth == TransitionMachine.Auth.STATE ? 8000L : 30000L);
      GraphNode last = source;
      int hits = 0;
      for (; System.currentTimeMillis() < deadline; Thread.sleep(100L)) {
         if (run.cancelled) {
            return new AuthWait(null, false);
         }
         GraphNode now;
         int sdt = sdt0;
         long veh = veh0;
         boolean present = false;
         synchronized (ui) {
            Gob me = gui.map.player();
            veh = me == null ? 0L : me.vehicleId();
            now = WorldGraphAdapter.current(gui);
            PrototypePathfinder.Scene scene = PrototypePathfinder.observe(gui);
            PrototypePathfinder.GobGeom g = find(scene, sourceId);
            present = g != null;
            if (g != null) {
               sdt = g.gateState;
            }
         }
         if (now == null) {
            continue;
         }
         last = now;
         if (auth == TransitionMachine.Auth.STATE && TransitionAdapter.doorStateChanged(sdt0, sdt)) {
            return new AuthWait(now, false);
         }
         if (auth == TransitionMachine.Auth.TOPOLOGY && (TransitionAdapter.topologyChanged(source, now) || !present)) {
            return new AuthWait(now, false);
         }
         if (auth == TransitionMachine.Auth.VEHICLE_ENTER && TransitionAdapter.boarded(veh0, veh)) {
            return new AuthWait(now, false);
         }
         if (auth == TransitionMachine.Auth.VEHICLE_EXIT && TransitionAdapter.disembarked(veh0, veh)) {
            return new AuthWait(now, false);
         }
         hits++;
      }
      if (auth == TransitionMachine.Auth.TOPOLOGY) {
         return new AuthWait(last.equals(source) ? null : last, false);
      }
      return new AuthWait(null, hits > 1 && false);
   }

   private static JSONObject failClosed(List<JSONObject> checks, String refusal, String note) {
      JSONObject facts = new JSONObject().put("refusal", refusal).put("selected", false).put("reached", false);
      return PfTestHarness.body(checks, note, facts);
   }

   private JSONObject facts(GraphNode source, GraphNode landing, NavGoal original, TransitionMachine machine, boolean ok) {
      JSONObject f = new JSONObject();
      f.put("kind", this.kind.name());
      f.put("source", source == null ? JSONObject.NULL : source.toString());
      f.put("landing", landing == null ? JSONObject.NULL : landing.toString());
      f.put("reason", machine.state().reason);
      f.put("phase", machine.state().phase.name());
      f.put("reached", false);
      f.put("original_goal", original.position == null ? JSONObject.NULL : PfTestHarness.pt(original.position));
      f.put("ok", ok);
      return f;
   }

   static JSONObject evidence(
      GraphNode source,
      GraphNode landing,
      GraphEdge.Kind kind,
      String fixture,
      NavGoal original,
      MobilityProfile before,
      MobilityProfile after,
      TransitionMachine.State st,
      String reason,
      boolean learned
   ) {
      JSONObject o = new JSONObject();
      o.put("source", source == null ? JSONObject.NULL : source.toString());
      o.put("landing", landing == null ? JSONObject.NULL : landing.toString());
      o.put("transition_type", kind == null ? JSONObject.NULL : kind.name());
      o.put("fixture", fixture == null ? "" : fixture);
      o.put("reason", reason == null ? "" : reason);
      o.put("learned", learned);
      o.put("reached", false);
      if (original != null) {
         o.put("original_goal_kind", original.kind.name());
      }
      if (before != null) {
         o.put("mobility_before", before.boat ? "boat" : before.cart ? "cart" : before.swim ? "swim" : "land");
      }
      if (after != null) {
         o.put("mobility_after", after.boat ? "boat" : after.cart ? "cart" : after.swim ? "swim" : "land");
      }
      if (st != null) {
         o.put("phase", st.phase.name());
         o.put("retries", st.retries);
      }
      return o;
   }

   private static final class AuthWait {
      final GraphNode node;
      final boolean ambiguous;

      AuthWait(GraphNode node, boolean ambiguous) {
         this.node = node;
         this.ambiguous = ambiguous;
      }
   }
}
