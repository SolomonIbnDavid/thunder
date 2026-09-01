package haven.pathfinding;

import auto.Bot;
import haven.Coord;
import haven.Coord2d;
import haven.FlowerMenu;
import haven.GameUI;
import haven.Gob;
import haven.Moving;
import haven.UI;
import haven.WItem;
import haven.Window;
import haven.nav.InteractionSpec;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Allowlisted interaction-goal scenarios. Missing fixtures fail closed.
 * Arrival at a stand pose is not interaction success.
 */
final class InteractScenario implements PfTestRunner.Scenario {
   enum Kind {
      FORAGE("interact_forageable", InteractionVerifier.TARGET_GONE, 1.5, 16.5, 1),
      TREE("interact_tree", InteractionVerifier.STATE_CHANGED, 2.0, 16.5, 1),
      BOULDER("interact_boulder", InteractionVerifier.STATE_CHANGED, 2.0, 16.5, 1),
      CUPBOARD("interact_cupboard", InteractionVerifier.WINDOW_OPENED, 0.5, 16.5, 0),
      DOOR("interact_door_gate", InteractionVerifier.STATE_CHANGED, 1.0, 35.0, 1),
      CROP("interact_field_crop", InteractionVerifier.TARGET_GONE, 1.5, 16.5, 1),
      NARROW("interact_narrow_interior", InteractionVerifier.WINDOW_OPENED, 0.5, 11.0, 0);

      final String name;
      final String expected;
      final double minDist;
      final double maxDist;
      final int clearance;

      Kind(String name, String expected, double minDist, double maxDist, int clearance) {
         this.name = name;
         this.expected = expected;
         this.minDist = minDist;
         this.maxDist = maxDist;
         this.clearance = clearance;
      }
   }

   private final Kind kind;

   InteractScenario(Kind kind) {
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
         JSONObject facts = new JSONObject().put("refusal", "NO_GAME").put("selected", false).put("kind", this.kind.name());
         return PfTestHarness.body(checks, "not in game", facts);
      }
      PrototypePathfinder.Scene scene;
      synchronized (ui) {
         scene = PrototypePathfinder.observe(gui);
      }
      PrototypePathfinder.GobGeom gob = pick(scene);
      if (gob == null) {
         return failClosed(checks, "NO_FIXTURE", "no automatic fixture for " + this.kind.name);
      }
      InteractionSpec spec = InteractionAdapter.fromGob(
         gob, InteractionSpec.ALL_SIDES, this.kind.minDist, this.kind.maxDist, this.kind.clearance, null, this.kind.expected
      );
      OccupancyGrid occ = scene.occupancy;
      InteractionGoals.Result pose = InteractionGoals.select(scene.player, spec, occ);
      PathfinderLog.recordOccupancy(occ);
      PathfinderLog.recordInteraction(evidence(pose, spec, false, "", "", "", ""));
      if (!pose.ok() || pose.selected == null) {
         return failClosed(checks, "NO_POSE", "no reachable interaction pose");
      }
      if (this.kind == Kind.NARROW && !narrowAccess(pose)) {
         return failClosed(checks, "NO_FIXTURE", "cupboard is not narrow-access");
      }
      checks.add(PfTestRunner.check("fixture", true, this.kind.name + " target #" + gob.id));
      checks.add(PfTestRunner.check("pose_selected", true, "stand " + PfTestHarness.pt(pose.selected.world)));
      Bot bot = Bot.execute(new Bot.BotAction[0]);
      String observed = "";
      String retry = "";
      String outcome = "INTERACTION_FAILED";
      boolean arrived = false;
      boolean interacted = false;
      boolean confirmed = false;
      for (int attempt = 0; InteractionVerifier.retryAllowed(attempt); attempt++) {
         if (run.cancelled) {
            throw new PfTestRunner.Cancelled();
         }
         if (attempt > 0) {
            retry = "revalidate_replan";
            synchronized (ui) {
               scene = PrototypePathfinder.observe(gui);
            }
            gob = find(scene, Long.parseLong(spec.targetId));
            if (gob == null) {
               outcome = "TARGET_GONE";
               break;
            }
            spec = InteractionAdapter.fromGob(
               gob, InteractionSpec.ALL_SIDES, this.kind.minDist, this.kind.maxDist, this.kind.clearance, null, this.kind.expected
            );
            occ = scene.occupancy;
            pose = InteractionGoals.select(scene.player, spec, occ);
            PathfinderLog.recordInteraction(evidence(pose, spec, arrived, "", observed, retry, outcome));
            if (!pose.ok()) {
               outcome = "NO_POSE";
               break;
            }
         }
         List<Coord2d> route = pose.plan.smoothedRoute;
         if (route.size() < 2) {
            route = new ArrayList<Coord2d>();
            route.add(scene.player);
            route.add(pose.selected.world);
         }
         WaypointWalker.Result walk = WaypointWalker.execute(
            WaypointWalker.liveEnv(gui),
            bot,
            route,
            attempt,
            60000L,
            WaypointWalker.Params.DEFAULT,
            NamedPlaceNavigator.NOOP,
            pose.plan.status,
            pose.selected.world,
            spec
         );
         arrived = walk == WaypointWalker.Result.READY_TO_INTERACT;
         checks.add(PfTestRunner.check("arrival", arrived, arrived ? "server-confirmed pose" : "walk " + walk));
         if (!arrived) {
            outcome = "NO_ARRIVAL";
            continue;
         }
         synchronized (ui) {
            scene = PrototypePathfinder.observe(gui);
         }
         PrototypePathfinder.GobGeom live = find(scene, Long.parseLong(spec.targetId));
         Coord2d pos = scene.player;
         boolean stillIdle = !scene.moving;
         if (!InteractionVerifier.mayInteract(InteractionVerifier.arrivedConfirmed(stillIdle, pos, pose.selected.world, 2.475))) {
            retry = "not_idle_at_pose";
            outcome = "NO_ARRIVAL";
            continue;
         }
         if (live == null) {
            outcome = "TARGET_GONE";
            break;
         }
         InteractionSpec fresh = InteractionAdapter.fromGob(
            live, spec.allowedSides, spec.minDist, spec.maxDist, spec.requiredClearance, spec.facing, spec.expectedResult
         );
         if (!InteractionVerifier.poseStillValid(pos, pose.selected.world, 2.475, spec, fresh.origin, fresh.half, true)) {
            retry = "footprint_changed";
            continue;
         }
         int invBefore = inventoryCount(gui);
         Set<Integer> windowsBefore = windowIds(gui);
         int gateBefore = live.gateState;
         Gob target;
         synchronized (ui) {
            target = gui.map.glob.oc.getgob(live.id);
         }
         if (target == null) {
            outcome = "TARGET_GONE";
            break;
         }
         interacted = true;
         target.rclick(0);
         long t0 = System.currentTimeMillis();
         while (!InteractionVerifier.timedOut(System.currentTimeMillis() - t0, InteractionVerifier.WAIT_MS)) {
            bot.checkCancelled();
            Thread.sleep(50L);
            if (checkConfirmed(gui, spec.expectedResult, live.id, invBefore, windowsBefore, gateBefore)) {
               confirmed = true;
               observed = spec.expectedResult;
               break;
            }
         }
         PathfinderLog.recordInteraction(evidence(pose, spec, true, "INTERACT", observed, retry, confirmed ? "CONFIRMED" : "TIMEOUT"));
         if (confirmed) {
            outcome = "CONFIRMED";
            break;
         }
         retry = "timeout";
         outcome = "INTERACTION_TIMEOUT";
      }
      checks.add(PfTestRunner.check("no_click_before_arrival", !interacted || arrived, arrived ? "clicked after arrival" : "no click"));
      checks.add(PfTestRunner.check("interaction_confirmed", confirmed, confirmed ? observed : outcome));
      JSONObject facts = new JSONObject()
         .put("kind", this.kind.name())
         .put("selected", true)
         .put("target_id", spec.targetId)
         .put("pose", PfTestHarness.pt(pose.selected.world))
         .put("expected", this.kind.expected)
         .put("observed", observed)
         .put("retry", retry)
         .put("arrived", arrived)
         .put("interacted", interacted)
         .put("status", outcome);
      PathfinderLog.recordInteraction(evidence(pose, spec, arrived, arrived ? "INTERACT" : "", observed, retry, outcome));
      return PfTestHarness.body(checks, confirmed ? "interaction confirmed" : outcome, facts);
   }

   private JSONObject failClosed(List<JSONObject> checks, String refusal, String why) {
      checks.add(PfTestRunner.check("fixture", false, why));
      JSONObject facts = new JSONObject().put("refusal", refusal).put("selected", false).put("kind", this.kind.name());
      return PfTestHarness.body(checks, why, facts);
   }

   private PrototypePathfinder.GobGeom pick(PrototypePathfinder.Scene scene) {
      if (scene == null || scene.gobs == null) {
         return null;
      }
      PrototypePathfinder.GobGeom best = null;
      for (int i = 0; i < scene.gobs.size(); i++) {
         PrototypePathfinder.GobGeom g = scene.gobs.get(i);
         if (!matches(g, scene)) {
            continue;
         }
         if (best == null || g.gobDist < best.gobDist) {
            best = g;
         }
      }
      return best;
   }

   private boolean matches(PrototypePathfinder.GobGeom g, PrototypePathfinder.Scene scene) {
      if (g == null) {
         return false;
      }
      switch (this.kind) {
         case FORAGE:
            return isForage(g.resid);
         case TREE:
            return isTree(g.resid);
         case BOULDER:
            return g.boulder || TransitionApproachSelector.isBoulderResid(g.resid);
         case CUPBOARD:
            return g.cupboard;
         case DOOR:
            return g.doorGate || TransitionApproachSelector.isDoorGateResid(g.resid);
         case CROP:
            return isCrop(g.resid);
         case NARROW:
            return g.cupboard && looksNarrow(scene.occupancy, g);
         default:
            return false;
      }
   }

   static boolean isForage(String resid) {
      String name = PrototypePathfinder.baseResid(resid);
      return name != null && (name.contains("/herbs/") || name.contains("/forage"));
   }

   static boolean isTree(String resid) {
      String name = PrototypePathfinder.baseResid(resid);
      return name != null && (name.contains("/trees/") || name.contains("/bushes/"));
   }

   static boolean isCrop(String resid) {
      String name = PrototypePathfinder.baseResid(resid);
      return name != null && (name.contains("/plants/") || name.contains("/field"));
   }

   static boolean looksNarrow(OccupancyGrid occ, PrototypePathfinder.GobGeom g) {
      if (occ == null || g == null || g.rc == null) {
         return false;
      }
      Coord c = occ.cellOf(g.rc);
      if (c == null) {
         return false;
      }
      int open = 0;
      int[] dx = new int[]{0, 1, 0, -1};
      int[] dy = new int[]{-1, 0, 1, 0};
      for (int i = 0; i < 4; i++) {
         if (occ.at(c.x + dx[i], c.y + dy[i]) != OccupancyGrid.SOLID) {
            open++;
         }
      }
      return open <= 2;
   }

   static boolean narrowAccess(InteractionGoals.Result r) {
      Set<Integer> sides = new HashSet<Integer>();
      for (int i = 0; i < r.considered.size(); i++) {
         InteractionGoals.Candidate c = r.considered.get(i);
         if (c.reject == null || "unreachable".equals(c.reject)) {
            sides.add(Integer.valueOf(c.side));
         }
      }
      return sides.size() <= 2;
   }

   static PrototypePathfinder.GobGeom find(PrototypePathfinder.Scene scene, long id) {
      if (scene == null || scene.gobs == null) {
         return null;
      }
      for (int i = 0; i < scene.gobs.size(); i++) {
         PrototypePathfinder.GobGeom g = scene.gobs.get(i);
         if (g != null && g.id == id) {
            return g;
         }
      }
      return null;
   }

   static int inventoryCount(GameUI gui) {
      if (gui == null || gui.maininv == null) {
         return 0;
      }
      int n = 0;
      for (WItem ignored : gui.maininv.children(WItem.class)) {
         n++;
      }
      return n;
   }

   static Set<Integer> windowIds(GameUI gui) {
      Set<Integer> ids = new HashSet<Integer>();
      if (gui == null) {
         return ids;
      }
      for (Window w : gui.children(Window.class)) {
         if (w != null && !w.disposed()) {
            ids.add(Integer.valueOf(w.wdgid()));
         }
      }
      return ids;
   }

   static boolean checkConfirmed(GameUI gui, String expected, long gobId, int invBefore, Set<Integer> windowsBefore, int gateBefore) {
      boolean present = gui != null && gui.map != null && gui.map.glob.oc.getgob(gobId) != null;
      boolean inv = inventoryCount(gui) != invBefore;
      boolean window = false;
      boolean flower = false;
      int gate = gateBefore;
      if (gui != null) {
         for (Window w : gui.children(Window.class)) {
            if (w != null && !w.disposed() && (w.gobId() == gobId || !windowsBefore.contains(Integer.valueOf(w.wdgid())))) {
               window = true;
               break;
            }
         }
         for (FlowerMenu ignored : gui.children(FlowerMenu.class)) {
            flower = true;
            break;
         }
         Gob g = gui.map.glob.oc.getgob(gobId);
         if (g != null) {
            gate = g.sdt();
         }
      }
      boolean state = flower || gate != gateBefore;
      return InteractionVerifier.confirmed(expected, present, inv, window, state);
   }

   static JSONObject evidence(
      InteractionGoals.Result r,
      InteractionSpec spec,
      boolean arrived,
      String decision,
      String observed,
      String retry,
      String outcome
   ) {
      JSONObject o = new JSONObject();
      if (spec != null) {
         o.put("target_id", spec.targetId);
         o.put("footprint", new JSONArray().put(spec.origin.x).put(spec.origin.y).put(spec.half.x).put(spec.half.y));
         o.put("min_dist", spec.minDist);
         o.put("max_dist", spec.maxDist);
         o.put("allowed_sides", spec.allowedSides);
         o.put("required_clearance", spec.requiredClearance);
         o.put("expected_result", spec.expectedResult);
         if (spec.facing != null) {
            o.put("facing", spec.facing.doubleValue());
         }
      }
      if (r != null && r.selected != null) {
         o.put("selected", new JSONArray().put(r.selected.world.x).put(r.selected.world.y));
         o.put("selected_side", InteractionSpec.sideName(r.selected.side));
      }
      JSONArray cands = new JSONArray();
      if (r != null) {
         int n = Math.min(48, r.considered.size());
         for (int i = 0; i < n; i++) {
            InteractionGoals.Candidate c = r.considered.get(i);
            JSONObject cj = new JSONObject()
               .put("x", c.cell.x)
               .put("y", c.cell.y)
               .put("side", InteractionSpec.sideName(c.side))
               .put("dist", c.dist)
               .put("clearance", c.clearance);
            if (c.reject != null) {
               cj.put("reject", c.reject);
            }
            cands.put(cj);
         }
      }
      o.put("candidates", cands);
      o.put("arrival", arrived);
      o.put("decision", decision);
      o.put("observed_result", observed);
      o.put("retry", retry);
      o.put("outcome", outcome);
      return o;
   }
}
