package haven.pathfinding;

import auto.Bot;
import auto.Bot.BotAction;
import haven.Area;
import haven.Coord;
import haven.Coord2d;
import haven.GameUI;
import haven.Gob;
import haven.HackThread;
import haven.MapFile;
import haven.Moving;
import haven.UI;
import haven.Utils;
import haven.NamedPlaceResolver.Place;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

public final class PfTestRunner {
   private static final int KEEP_RESULTS = 32;
   private static final long IDLE_WAIT_MS = 3000L;
   private static final long IDLE_POLL_MS = 100L;
   private static final Object LOCK = new Object();
   private static volatile PfTestRunner.Run current;
   private static final Deque<JSONObject> results = new ArrayDeque<>();
   private static final AtomicInteger seq = new AtomicInteger();
   private static final Map<String, PfTestRunner.Scenario> SCENARIOS = new LinkedHashMap<>();

   private PfTestRunner() {
   }

   public static List<String> knownScenarios() {
      synchronized (LOCK) {
         return Collections.unmodifiableList(new ArrayList<>(SCENARIOS.keySet()));
      }
   }

   public static String validateScenario(String name) {
      if (name != null && !name.trim().isEmpty()) {
         return !SCENARIOS.containsKey(name.trim()) ? "unknown scenario \"" + name.trim() + "\" (known: " + String.join(", ", SCENARIOS.keySet()) + ")" : null;
      } else {
         return "missing scenario name";
      }
   }

   public static JSONObject check(String name, boolean passed, String detail) {
      JSONObject c = new JSONObject();
      c.put("name", name);
      c.put("status", passed ? "pass" : "fail");
      c.put("detail", detail == null ? "" : detail);
      return c;
   }

   public static JSONObject skip(String name, String detail) {
      JSONObject c = new JSONObject();
      c.put("name", name);
      c.put("status", "skip");
      c.put("detail", detail == null ? "" : detail);
      return c;
   }

   public static String verdictOf(List<JSONObject> checks) {
      if (checks != null) {
         for (JSONObject c : checks) {
            if ("fail".equals(c.optString("status"))) {
               return "FAIL";
            }
         }
      }

      return "PASS";
   }

   public static boolean beginRun(PfTestRunner.Run r) {
      synchronized (LOCK) {
         if (current != null) {
            return false;
         } else {
            current = r;
            return true;
         }
      }
   }

   public static PfTestRunner.Run currentRun() {
      synchronized (LOCK) {
         return current;
      }
   }

   public static void finishRun(PfTestRunner.Run r) {
      synchronized (LOCK) {
         if (current == r) {
            current = null;
         }
      }
   }

   public static PfTestRunner.Run cancelRun() {
      synchronized (LOCK) {
         PfTestRunner.Run r = current;
         if (r != null) {
            r.cancelled = true;
         }

         return r;
      }
   }

   public static JSONObject start(String scenario, UI ui) {
      PfTestRunner.Run run = new PfTestRunner.Run(scenario);
      if (!beginRun(run)) {
         return new JSONObject().put("ok", false).put("conflict", true).put("error", "a run is already in progress (run_id=" + currentRun().id + ")");
      } else {
         Thread th = new HackThread(() -> execute(run, ui), "pf-test-run");
         th.setDaemon(true);
         th.start();
         return new JSONObject().put("ok", true).put("run_id", run.id).put("scenario", run.scenario).put("status", "running").put("started_ms", run.startedMs);
      }
   }

   public static JSONObject result(String runId) {
      if (runId == null) {
         return null;
      } else {
         synchronized (LOCK) {
            PfTestRunner.Run r = current;
            if (r != null && r.id.equals(runId)) {
               return new JSONObject().put("ok", true).put("run_id", r.id).put("scenario", r.scenario).put("status", "running").put("started_ms", r.startedMs);
            } else {
               for (JSONObject o : results) {
                  if (runId.equals(o.optString("run_id"))) {
                     return o;
                  }
               }

               return null;
            }
         }
      }
   }

   static void rememberResult(JSONObject o) {
      synchronized (LOCK) {
         results.addLast(o);

         while (results.size() > 32) {
            results.removeFirst();
         }
      }
   }

   private static void execute(PfTestRunner.Run run, UI ui) {
      JSONObject result = new JSONObject();
      result.put("ok", true);
      result.put("run_id", run.id);
      result.put("scenario", run.scenario);
      result.put("started_ms", run.startedMs);

      try {
         PfTestRunner.Scenario sc = SCENARIOS.get(run.scenario);
         JSONObject body = sc.execute(run, ui);
         long now = System.currentTimeMillis();
         result.put("status", "completed");
         result.put("finished_ms", now);
         result.put("duration_ms", now - run.startedMs);
         result.put("verdict", body.getString("verdict"));
         result.put("checks", body.getJSONArray("checks"));
         if (body.has("scene")) {
            result.put("scene", body.get("scene"));
         }

         if (body.has("facts")) {
            result.put("facts", body.get("facts"));
         }

         if (body.has("note")) {
            result.put("note", body.getString("note"));
         }
      } catch (PfTestRunner.Cancelled var11) {
         long nowx = System.currentTimeMillis();
         result.put("status", "cancelled");
         result.put("finished_ms", nowx);
         result.put("duration_ms", nowx - run.startedMs);
         if (var11.body != null) {
            if (var11.body.has("verdict")) {
               result.put("verdict", var11.body.getString("verdict"));
            }

            if (var11.body.has("checks")) {
               result.put("checks", var11.body.get("checks"));
            }

            if (var11.body.has("facts")) {
               result.put("facts", var11.body.get("facts"));
            }
         }

         result.put("note", "cancelled by POST /pf/cancel");
      } catch (Exception var12) {
         long nowx = System.currentTimeMillis();
         result.put("status", "error");
         result.put("finished_ms", nowx);
         result.put("duration_ms", nowx - run.startedMs);
         result.put("error", var12.getMessage() == null ? var12.toString() : var12.getMessage());
      } finally {
         finishRun(run);
         rememberResult(result);
         write(run, result);
      }
   }

   private static void write(PfTestRunner.Run run, JSONObject result) {
      write(run, result, Utils.path(System.getProperty("user.dir", ".")));
   }

   static void write(PfTestRunner.Run run, JSONObject result, Path root) {
      try {
         Path dir = root.resolve("dev-snapshots").resolve("pf").resolve("tests").resolve(run.scenario);
         Files.createDirectories(dir);
         Path file = dir.resolve(run.id + ".jsonl");
         result.put("artifact", file.toString());
         JSONObject header = new JSONObject()
            .put("type", "header")
            .put("feature", "pf-test")
            .put("scenario", run.scenario)
            .put("run_id", run.id)
            .put("generated_at_ms", System.currentTimeMillis());
         Writer w = Files.newBufferedWriter(file, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

         try {
            w.write(header.toString());
            w.write(10);
            w.write(result.toString());
            w.write(10);
         } catch (Throwable var10) {
            if (w != null) {
               try {
                  w.close();
               } catch (Throwable var9) {
                  var10.addSuppressed(var9);
               }
            }

            throw var10;
         }

         if (w != null) {
            w.close();
         }
      } catch (IOException var11) {
      }
   }

   private static List<JSONObject> autoMovePreflightChecks(
      String scenario, boolean inGame, boolean playerPresent, boolean mapfileAvailable, boolean playerIdle, boolean botBusy
   ) {
      List<JSONObject> checks = new ArrayList<>();
      if (!inGame) {
         checks.add(check("in_game", false, "client is not in game (" + scenario + " requires in-game state)"));
         return checks;
      } else {
         checks.add(check("in_game", true, "in game"));
         if (!playerPresent) {
            checks.add(check("player_present", false, "no player gob in game"));
            return checks;
         } else {
            checks.add(check("player_present", true, "player gob present"));
            if (!mapfileAvailable) {
               checks.add(check("mapfile_available", false, "no map file in game state (in-game readiness required)"));
               return checks;
            } else {
               checks.add(check("mapfile_available", true, "map file present"));
               if (!playerIdle) {
                  checks.add(check("player_idle", false, "player is moving at scenario start; refusing to move (no-move)"));
                  return checks;
               } else {
                  checks.add(check("player_idle", true, "player is idle"));
                  if (botBusy) {
                     checks.add(check("bot_available", false, "another auto.Bot is currently running; refusing to navigate (no-move)"));
                     return checks;
                  } else {
                     checks.add(check("bot_available", true, "no current auto.Bot"));
                     return checks;
                  }
               }
            }
         }
      }
   }

   private static <T> T moveWatch(PfTestRunner.Run run, AtomicBoolean timedOut, long totalTimeoutMs, Callable<T> body) throws Exception {
      long deadline = System.currentTimeMillis() + totalTimeoutMs;
      Thread me = Thread.currentThread();
      AtomicBoolean stop = new AtomicBoolean(false);
      Thread watchdog = new HackThread(() -> {
         while (!stop.get()) {
            if (run.cancelled) {
               me.interrupt();
               return;
            }

            if (System.currentTimeMillis() >= deadline) {
               timedOut.set(true);
               me.interrupt();
               return;
            }

            try {
               Thread.sleep(50L);
            } catch (InterruptedException var7x) {
               return;
            }
         }
      }, "pf-auto-move-watchdog");
      watchdog.setDaemon(true);
      watchdog.start();

      Object var10;
      try {
         var10 = body.call();
      } finally {
         stop.set(true);
         watchdog.interrupt();
         Thread.interrupted();
      }

      return (T)var10;
   }

   private static Coord2d observePos(GameUI gui) {
      Gob player = gui != null && gui.map != null ? gui.map.player() : null;
      return player != null && player.rc != null ? player.rc : null;
   }

   private static long elapsed(long t0) {
      return System.currentTimeMillis() - t0;
   }

   private static JSONObject body(List<JSONObject> checks, String note, JSONObject facts) {
      JSONObject o = new JSONObject();
      o.put("verdict", verdictOf(checks));
      JSONArray arr = new JSONArray();

      for (JSONObject c : checks) {
         arr.put(c);
      }

      o.put("checks", arr);
      if (facts != null) {
         o.put("facts", facts);
      }

      if (note != null && !note.isEmpty()) {
         o.put("note", note);
      }

      return o;
   }

   private static String firstFailDetail(List<JSONObject> checks) {
      for (JSONObject c : checks) {
         if ("fail".equals(c.optString("status"))) {
            return c.optString("detail", "preflight refused");
         }
      }

      return "preflight refused";
   }

   static List<PrototypePathfinder.GobGeom> cupboardGobs(PrototypePathfinder.Scene scene) {
      List<PrototypePathfinder.GobGeom> out = new ArrayList<>();
      if (scene != null && scene.gobs != null) {
         for (PrototypePathfinder.GobGeom g : scene.gobs) {
            if (g != null && g.cupboard && g.rc != null) {
               out.add(g);
            }
         }
      }

      out.sort(Comparator.<PrototypePathfinder.GobGeom>comparingDouble(gx -> gx.rc.x).thenComparingDouble(gx -> gx.rc.y));
      return out;
   }

   static List<CupboardCatalog.Node> cupboardNodes(List<PrototypePathfinder.GobGeom> gobs) {
      List<CupboardCatalog.Node> out = new ArrayList<>();
      if (gobs != null) {
         for (PrototypePathfinder.GobGeom g : gobs) {
            if (g != null && g.rc != null) {
               out.add(new CupboardCatalog.Node(g.id, g.rc.x, g.rc.y));
            }
         }
      }

      return out;
   }

   static List<List<CupboardCatalog.Node>> clusterCupboards(List<CupboardCatalog.Node> nodes) {
      List<CupboardCatalog.Node> sorted = new ArrayList<>();
      if (nodes != null) {
         for (CupboardCatalog.Node n : nodes) {
            if (n != null) {
               sorted.add(n);
            }
         }
      }

      sorted.sort(Comparator.<CupboardCatalog.Node>comparingDouble(nx -> nx.x).thenComparingDouble(nx -> nx.y));
      int[] parent = new int[sorted.size()];
      int i = 0;

      while (i < parent.length) {
         parent[i] = i++;
      }

      for (int ix = 0; ix < sorted.size(); ix++) {
         for (int j = ix + 1; j < sorted.size(); j++) {
            if (CupboardCatalog.adjacent(sorted.get(ix), sorted.get(j))) {
               union(parent, ix, j);
            }
         }
      }

      Map<Integer, List<CupboardCatalog.Node>> groups = new LinkedHashMap<>();

      for (int ix = 0; ix < sorted.size(); ix++) {
         int root = find(parent, ix);
         groups.computeIfAbsent(root, k -> new ArrayList<>()).add(sorted.get(ix));
      }

      List<List<CupboardCatalog.Node>> out = new ArrayList<>(groups.values());

      for (List<CupboardCatalog.Node> c : out) {
         c.sort(Comparator.<CupboardCatalog.Node>comparingDouble(nx -> nx.x).thenComparingDouble(nx -> nx.y));
      }

      out.sort(Comparator.<List<CupboardCatalog.Node>>comparingInt(List::size).reversed().thenComparingDouble(PfTestRunner::clusterMinX).thenComparingDouble(PfTestRunner::clusterMinY));
      return out;
   }

   private static int find(int[] parent, int i) {
      while (parent[i] != i) {
         parent[i] = parent[parent[i]];
         i = parent[i];
      }

      return i;
   }

   private static void union(int[] parent, int a, int b) {
      int ra = find(parent, a);
      int rb = find(parent, b);
      if (ra != rb) {
         parent[ra] = rb;
      }
   }

   private static double clusterMinX(List<CupboardCatalog.Node> c) {
      double best = Double.POSITIVE_INFINITY;

      for (CupboardCatalog.Node n : c) {
         best = Math.min(best, n.x);
      }

      return best;
   }

   private static double clusterMinY(List<CupboardCatalog.Node> c) {
      double best = Double.POSITIVE_INFINITY;

      for (CupboardCatalog.Node n : c) {
         best = Math.min(best, n.y);
      }

      return best;
   }

   static List<CupboardCatalog.Node> uniqueLargest(List<List<CupboardCatalog.Node>> clusters) {
      if (clusters != null && !clusters.isEmpty()) {
         List<CupboardCatalog.Node> first = clusters.get(0);

         for (List<CupboardCatalog.Node> c : clusters) {
            if (c.size() == first.size() && c != first) {
               return null;
            }
         }

         return first;
      } else {
         return null;
      }
   }

   static JSONObject cupboardFingerprint(List<CupboardCatalog.Node> cluster) {
      JSONObject o = new JSONObject();
      List<CupboardCatalog.Node> sorted = new ArrayList<>();
      if (cluster != null) {
         for (CupboardCatalog.Node n : cluster) {
            if (n != null) {
               sorted.add(n);
            }
         }
      }

      sorted.sort(Comparator.<CupboardCatalog.Node>comparingDouble(nx -> nx.x).thenComparingDouble(nx -> nx.y));
      o.put("count", sorted.size());
      JSONArray offsets = new JSONArray();
      if (sorted.isEmpty()) {
         o.put("offsets", offsets);
         return o;
      } else {
         double minx = Double.POSITIVE_INFINITY;
         double miny = Double.POSITIVE_INFINITY;
         double maxx = Double.NEGATIVE_INFINITY;
         double maxy = Double.NEGATIVE_INFINITY;

         for (CupboardCatalog.Node nx : sorted) {
            minx = Math.min(minx, nx.x);
            miny = Math.min(miny, nx.y);
            maxx = Math.max(maxx, nx.x);
            maxy = Math.max(maxy, nx.y);
         }

         Set<Integer> rows = new TreeSet<>();
         Set<Integer> cols = new TreeSet<>();

         for (CupboardCatalog.Node nx : sorted) {
            JSONArray p = new JSONArray();
            p.put((int)Math.round(nx.x - minx));
            p.put((int)Math.round(nx.y - miny));
            offsets.put(p);
            rows.add((int)Math.round(nx.y - miny));
            cols.add((int)Math.round(nx.x - minx));
         }

         o.put("offsets", offsets);
         o.put("width", (int)Math.round(maxx - minx));
         o.put("height", (int)Math.round(maxy - miny));
         o.put("tiles_w", round2((maxx - minx) / 11.0));
         o.put("tiles_h", round2((maxy - miny) / 11.0));
         o.put("rows", rows.size());
         o.put("columns", cols.size());
         o.put("shape", rows.size() == 1 ? "row" : (cols.size() == 1 ? "column" : "block"));
         JSONArray kinds = new JSONArray();
         int corners = 0;

         for (CupboardCatalog.Node nx : sorted) {
            boolean corner = CupboardCatalog.isCorner(nx, sorted);
            if (corner) {
               corners++;
            }

            kinds.put(corner ? "corner" : "aisle");
         }

         o.put("corners", corners);
         o.put("kinds", kinds);
         return o;
      }
   }

   static JSONObject evaluateCabinets(PrototypePathfinder.Scene scene) {
      List<JSONObject> checks = new ArrayList<>();
      JSONObject fixture = null;
      String note = null;
      if (scene != null && scene.player != null) {
         checks.add(check("player_present", true, "player gob present"));
      } else {
         checks.add(check("player_present", false, "no player gob in observed scene"));
      }

      List<PrototypePathfinder.GobGeom> cups = cupboardGobs(scene);
      if (cups.isEmpty()) {
         checks.add(check("cupboards_present", false, "no cupboards in observed scene (within 220.0 world units of player)"));
      } else {
         checks.add(check("cupboards_present", true, cups.size() + (cups.size() == 1 ? " cupboard" : " cupboards") + " in observed scene"));
         List<List<CupboardCatalog.Node>> clusters = clusterCupboards(cupboardNodes(cups));
         List<CupboardCatalog.Node> pick = uniqueLargest(clusters);
         if (pick == null) {
            checks.add(check("unique_largest_cluster", false, "largest cupboard cluster is not unique: sizes " + clusterSizes(clusters)));
            note = "no stable fixture: largest cupboard cluster is ambiguous";
         } else {
            checks.add(check("unique_largest_cluster", true, pick.size() + " cupboards in the unique largest cluster"));
            fixture = cupboardFingerprint(pick);
            checks.add(check("fixture_fingerprint", true, fixtureBrief(fixture)));
         }
      }

      JSONObject body = new JSONObject();
      body.put("verdict", verdictOf(checks));
      JSONArray arr = new JSONArray();

      for (JSONObject c : checks) {
         arr.put(c);
      }

      body.put("checks", arr);
      if (fixture != null) {
         body.put("fixture", fixture);
      }

      if (note != null) {
         body.put("note", note);
      }

      return body;
   }

   private static String clusterSizes(List<List<CupboardCatalog.Node>> clusters) {
      StringBuilder sb = new StringBuilder("[");

      for (int i = 0; i < clusters.size(); i++) {
         if (i > 0) {
            sb.append(", ");
         }

         sb.append(clusters.get(i).size());
      }

      return sb.append("]").toString();
   }

   private static String fixtureBrief(JSONObject fixture) {
      StringBuilder sb = new StringBuilder();
      sb.append("count=").append(fixture.getInt("count"));
      sb.append(" offsets=").append(fixture.getJSONArray("offsets").toString());
      sb.append(" ").append(fixture.optString("shape"));
      return sb.toString();
   }

   static JSONObject sceneJson(PrototypePathfinder.Scene scene) {
      JSONObject o = new JSONObject();
      if (scene == null) {
         return o;
      } else {
         o.put("origin", arr(scene.origin));
         o.put("grid", new JSONArray().put(scene.w).put(scene.h));
         o.put("cell", scene.cell);
         o.put("radius", round2(scene.radius));
         o.put("player", arr(scene.player));
         o.put("player_cell", scene.playerCell == null ? JSONObject.NULL : new JSONArray().put(scene.playerCell.x).put(scene.playerCell.y));
         o.put("moving", scene.moving);
         o.put("player_in_solid", scene.playerInSolid);
         o.put("player_in_inflated", scene.playerInDilated);
         o.put("terrain", scene.terrain);
         o.put("solid_count", scene.solidCount);
         o.put("inflated_count", scene.dilatedCount);
         o.put("obstacles", scene.obstacles);
         if (scene.occupancy != null && scene.playerCell != null) {
            o.put("ascii", scene.occupancy.ascii(scene.playerCell, 8));
         }

         return o;
      }
   }

   private static JSONArray arr(Coord2d p) {
      JSONArray a = new JSONArray();
      if (p != null) {
         a.put(round2(p.x)).put(round2(p.y));
      }

      return a;
   }

   private static String pt(Coord2d p) {
      return p == null ? "?" : String.format("(%.1f, %.1f)", p.x, p.y);
   }

   private static double round2(double v) {
      return (double)Math.round(v * 100.0) / 100.0;
   }

   static {
      SCENARIOS.put("observe", new PfTestRunner.ObserveScenario());
      SCENARIOS.put("basement_cabinet_identify", new PfTestRunner.BasementCabinetIdentifyScenario());
      SCENARIOS.put("move_to_marker", new PfTestRunner.MoveToMarkerScenario());
      SCENARIOS.put("move_to_auto_open_ground", new PfTestRunner.MoveToAutoOpenGroundScenario());
      SCENARIOS.put("move_to_auto_obstacle_corridor", new PfTestRunner.MoveToAutoObstacleCorridorScenario());
      SCENARIOS.put("move_to_auto_known_long_leg", new PfTestRunner.MoveToAutoKnownLongLegScenario());
      SCENARIOS.put("move_to_auto_cave_transition_approach", new PfTestRunner.MoveToAutoCaveTransitionApproachScenario());
      SCENARIOS.put("cross_cellar_door", new PfTestRunner.CrossCellarDoorScenario());
      SCENARIOS.put("cross_cellar_stairs", new PfTestRunner.CrossCellarStairsScenario());
      SCENARIOS.put("cross_minehole", new PfTestRunner.CrossMineholeScenario());
      SCENARIOS.put("select_open_ground", new PfTestRunner.NavigationTestSpotScenario(NavigationTestSpotSelector.Profile.OPEN_GROUND));
      SCENARIOS.put("select_obstacle_corridor", new PfTestRunner.NavigationTestSpotScenario(NavigationTestSpotSelector.Profile.LOCAL_OBSTACLE_OR_CORRIDOR));
      SCENARIOS.put("select_known_long_leg", new PfTestRunner.NavigationTestSpotScenario(NavigationTestSpotSelector.Profile.KNOWN_MAP_LONG_LEG));
      SCENARIOS.put("select_boulder_approach", new PfTestRunner.SelectBoulderApproachScenario());
      SCENARIOS.put("select_cave_transition_approach", new PfTestRunner.SelectCaveTransitionApproachScenario());
      SCENARIOS.put("select_door_gate_approach", new PfTestRunner.SelectDoorGateApproachScenario());
      SCENARIOS.put("select_waterline_approach", new PfTestRunner.SelectWaterlineApproachScenario());
   }

   static final class BasementCabinetIdentifyScenario implements PfTestRunner.Scenario {
      @Override
      public String name() {
         return "basement_cabinet_identify";
      }

      @Override
      public JSONObject execute(PfTestRunner.Run run, UI ui) throws Exception {
         if (run.cancelled) {
            throw new PfTestRunner.Cancelled();
         } else {
            List<JSONObject> checks = new ArrayList<>();
            GameUI gui = ui == null ? null : ui.gui;
            if (gui != null && gui.map != null) {
               checks.add(PfTestRunner.check("in_game", true, "in game"));
               PrototypePathfinder.Scene scene;
               synchronized (ui) {
                  scene = PrototypePathfinder.observe(gui);
               }

               JSONObject eval = PfTestRunner.evaluateCabinets(scene);
               JSONArray arr = eval.getJSONArray("checks");

               for (int i = 0; i < arr.length(); i++) {
                  checks.add(arr.getJSONObject(i));
               }

               return body(checks, eval.has("fixture") ? eval.getJSONObject("fixture") : null, eval.optString("note", null));
            } else {
               checks.add(PfTestRunner.check("in_game", false, "client is not in game (basement_cabinet_identify requires in-game state)"));
               return body(checks, null, null);
            }
         }
      }

      private static JSONObject body(List<JSONObject> checks, JSONObject fixture, String note) {
         JSONObject o = new JSONObject();
         o.put("verdict", PfTestRunner.verdictOf(checks));
         JSONArray arr = new JSONArray();

         for (JSONObject c : checks) {
            arr.put(c);
         }

         o.put("checks", arr);
         if (fixture != null) {
            o.put("fixture", fixture);
         }

         if (note != null && !note.isEmpty()) {
            o.put("note", note);
         }

         return o;
      }
   }

   static final class Cancelled extends Exception {
      final JSONObject body;

      Cancelled() {
         super("run cancelled");
         this.body = null;
      }

      Cancelled(JSONObject body) {
         super("run cancelled");
         this.body = body;
      }
   }

   static final class CrossCellarDoorScenario implements PfTestRunner.Scenario {
      static final double MAX_TARGET_DIST = 52.25;
      static final double MAX_INTERACT_DIST = 35.0;
      static final long CROSS_WAIT_MS = 30000L;
      static final long CROSS_POLL_MS = 100L;
      private final PfTestRunner.CrossCellarDoorScenario.Navigation nav;
      private final PfTestRunner.CrossCellarDoorScenario.Interaction interaction;

      CrossCellarDoorScenario() {
         this.nav = (g, t, b, r) -> PfTestRunner.MoveToAutoCaveTransitionApproachScenario.liveMove(g, t, b);
         this.interaction = gob -> gob.rclick(0);
      }

      CrossCellarDoorScenario(PfTestRunner.CrossCellarDoorScenario.Navigation nav, PfTestRunner.CrossCellarDoorScenario.Interaction interaction) {
         this.nav = nav;
         this.interaction = interaction;
      }

      @Override
      public String name() {
         return "cross_cellar_door";
      }

      @Override
      public JSONObject execute(PfTestRunner.Run run, UI ui) throws Exception {
         if (run.cancelled) {
            throw new PfTestRunner.Cancelled();
         } else {
            GameUI gui = ui == null ? null : ui.gui;
            boolean inGame = gui != null && gui.map != null;
            boolean present = false;
            boolean idle = false;
            boolean mapfile = inGame && gui.mapfile != null && gui.mapfile.file != null;
            if (inGame) {
               synchronized (ui) {
                  Gob p = gui.map.player();
                  present = p != null && p.rc != null;
                  idle = p == null || p.getattr(Moving.class) == null;
               }
            }

            List<JSONObject> checks = PfTestRunner.autoMovePreflightChecks("cross_cellar_door", inGame, present, mapfile, idle, Bot.hasCurrent());
            if ("FAIL".equals(PfTestRunner.verdictOf(checks))) {
               return PfTestRunner.body(
                  checks, "no interaction performed: " + PfTestRunner.firstFailDetail(checks), facts(null, null, false, "NOT_STARTED", "PREFLIGHT")
               );
            } else {
               PrototypePathfinder.Scene scene;
               synchronized (ui) {
                  scene = PrototypePathfinder.observe(gui);
               }

               TransitionApproachSelector.Selection sel = TransitionApproachSelector.caveTransitionApproach(scene);
               checks.add(PfTestRunner.SelectCaveTransitionApproachScenario.selectionCheck(sel));
               if (sel.refused()) {
                  return PfTestRunner.body(checks, "no interaction performed: " + sel.refusal, facts(sel, null, false, "SELECTION_REFUSED", sel.refusal.name()));
               } else {
                  boolean kind = supportedKind(sel);
                  checks.add(kindCheck(kind));
                  if (!kind) {
                     return PfTestRunner.body(
                        checks, "no interaction performed: unsupported transition kind", facts(sel, null, false, "SELECTION_REFUSED", "UNSUPPORTED_KIND")
                     );
                  } else {
                     PrototypePathfinder.Scene fresh;
                     synchronized (ui) {
                        fresh = PrototypePathfinder.observe(gui);
                     }

                     JSONObject ar = PfTestRunner.MoveToAutoCaveTransitionApproachScenario.approachRevalidationCheck(sel, fresh);
                     checks.add(ar);
                     JSONObject tr = PfTestRunner.MoveToAutoOpenGroundScenario.revalidationCheck(fresh, sel.approachWorld, 52.25);
                     checks.add(tr);
                     if (!"fail".equals(ar.getString("status")) && !"fail".equals(tr.getString("status"))) {
                        Bot bot = Bot.execute(new BotAction[0]);
                        AtomicBoolean timedOut = new AtomicBoolean(false);
                        PfTestRunner.MoveToAutoOpenGroundScenario.MoveResult mv = PfTestRunner.moveWatch(
                           run, timedOut, 120000L, () -> this.nav.run(gui, sel.approachWorld, bot, run)
                        );
                        if (run.cancelled) {
                           throw new PfTestRunner.Cancelled(cancelledBody(sel, mv, false));
                        } else {
                           JSONObject arrival = PfTestRunner.MoveToAutoOpenGroundScenario.finalArrival(gui, ui, mv, sel.approachWorld);
                           checks.add(PfTestRunner.MoveToAutoOpenGroundScenario.routeCheck(mv));
                           checks.add(PfTestRunner.MoveToAutoOpenGroundScenario.walkCheck(mv));
                           checks.add(arrival);
                           if (!timedOut.get() && "pass".equals(arrival.getString("status"))) {
                              Gob door;
                              synchronized (ui) {
                                 PrototypePathfinder.Scene atDoor = PrototypePathfinder.observe(gui);
                                 door = resolveDoor(gui, atDoor);
                              }

                              boolean resolved = door != null;
                              checks.add(
                                 PfTestRunner.check(
                                    "fixture_resolved",
                                    resolved,
                                    resolved ? "unique fresh cellar door resolved" : "unique cellar door unavailable (no interaction)"
                                 )
                              );
                              if (!resolved) {
                                 return PfTestRunner.body(
                                    checks,
                                    "no interaction performed: cellar door unavailable",
                                    facts(sel, mv, false, "REVALIDATION_REFUSED", "FIXTURE_UNAVAILABLE")
                                 );
                              } else {
                                 boolean range;
                                 synchronized (ui) {
                                    Gob player = gui.map.player();
                                    range = player != null && player.rc != null && door.rc != null && player.rc.dist(door.rc) <= 35.0;
                                 }

                                 checks.add(rangeCheck(range));
                                 if (!range) {
                                    return PfTestRunner.body(
                                       checks,
                                       "no interaction performed: cellar door out of range",
                                       facts(sel, mv, false, "REVALIDATION_REFUSED", "OUT_OF_RANGE")
                                    );
                                 } else if (run.cancelled) {
                                    throw new PfTestRunner.Cancelled(cancelledBody(sel, mv, false));
                                 } else {
                                    issueOnce(this.interaction, door);
                                    checks.add(interactionCheck());
                                    boolean crossed = awaitCross(run, ui, gui, door.id, System.currentTimeMillis() + 30000L);
                                    checks.add(completionCheck(crossed));
                                    if (run.cancelled) {
                                       throw new PfTestRunner.Cancelled(cancelledBody(sel, mv, true));
                                    } else {
                                       return PfTestRunner.body(
                                          checks,
                                          crossed ? null : "one interaction issued; crossing not confirmed",
                                          facts(sel, mv, true, crossed ? "CROSSED" : "NO_RESPONSE", crossed ? null : "NO_RESPONSE")
                                       );
                                    }
                                 }
                              }
                           } else {
                              return PfTestRunner.body(
                                 checks, "no interaction performed: approach walk failed", facts(sel, mv, false, "WALK_REFUSED", "WALK_REFUSED")
                              );
                           }
                        }
                     } else {
                        return PfTestRunner.body(
                           checks, "no interaction performed: revalidation refused", facts(sel, null, false, "REVALIDATION_REFUSED", "REVALIDATION_REFUSED")
                        );
                     }
                  }
               }
            }
         }
      }

      static boolean supportedKind(TransitionApproachSelector.Selection sel) {
         return sel != null && sel.kind == TransitionApproachSelector.TransitionKind.CELLAR_DOOR;
      }

      static JSONObject kindCheck(boolean supported) {
         return PfTestRunner.check(
            "fixture_kind_supported", supported, supported ? "selected fixture is a cellar door" : "only CELLAR_DOOR is supported (no interaction)"
         );
      }

      static PrototypePathfinder.GobGeom uniqueDoorGeom(PrototypePathfinder.Scene scene) {
         PrototypePathfinder.GobGeom found = null;

         for (PrototypePathfinder.GobGeom g : TransitionApproachSelector.caveTransitionGobs(scene)) {
            if (TransitionApproachSelector.caveTransitionKind(g.resid) == TransitionApproachSelector.TransitionKind.CELLAR_DOOR) {
               if (found != null) {
                  return null;
               }

               found = g;
            }
         }

         return found;
      }

      static Gob resolveDoor(GameUI gui, PrototypePathfinder.Scene scene) {
         PrototypePathfinder.GobGeom found = uniqueDoorGeom(scene);
         return found == null ? null : gui.ui.sess.glob.oc.getgob(found.id);
      }

      static JSONObject rangeCheck(boolean inRange) {
         return PfTestRunner.check(
            "in_interaction_range",
            inRange,
            inRange ? "cellar door is within bounded interaction range" : "cellar door out of interaction range (no interaction)"
         );
      }

      static void issueOnce(PfTestRunner.CrossCellarDoorScenario.Interaction interaction, Gob door) {
         interaction.rightClick(door);
      }

      static JSONObject interactionCheck() {
         return PfTestRunner.check("interaction_issued", true, "one cellar-door right-click issued");
      }

      static JSONObject completionCheck(boolean crossed) {
         return PfTestRunner.check(
            "crossed", crossed, crossed ? "server relocated player; source cellar door absent" : "no confirmed relocation after one click"
         );
      }

      static boolean awaitCross(PfTestRunner.Run run, UI ui, GameUI gui, long sourceId, long deadline) throws InterruptedException {
         for (; System.currentTimeMillis() < deadline; Thread.sleep(100L)) {
            if (run.cancelled) {
               return false;
            }

            synchronized (ui) {
               Gob p = gui.map.player();
               if (p != null && p.rc != null && p.getattr(Moving.class) == null) {
                  PrototypePathfinder.Scene now = PrototypePathfinder.observe(gui);
                  boolean sourcePresent = false;

                  for (PrototypePathfinder.GobGeom g : now.gobs) {
                     if (g.id == sourceId) {
                        sourcePresent = true;
                        break;
                     }
                  }

                  if (!sourcePresent) {
                     return true;
                  }
               }
            }
         }

         return false;
      }

      static JSONObject cancelledBody(TransitionApproachSelector.Selection sel, PfTestRunner.MoveToAutoOpenGroundScenario.MoveResult mv, boolean issued) {
         List<JSONObject> checks = new ArrayList<>();
         checks.add(PfTestRunner.check("run_cancelled", false, issued ? "cancelled after one interaction; no retry" : "cancelled before interaction"));
         return PfTestRunner.body(checks, "cancelled", facts(sel, mv, issued, "CANCELLED", "CANCELLED"));
      }

      static JSONObject facts(
         TransitionApproachSelector.Selection sel, PfTestRunner.MoveToAutoOpenGroundScenario.MoveResult mv, boolean issued, String status, String refusal
      ) {
         JSONObject f = PfTestRunner.MoveToAutoCaveTransitionApproachScenario.factsJson(sel, mv, status, refusal);
         f.put("interaction_issued", issued);
         f.put("crossed", "CROSSED".equals(status));
         return f;
      }

      interface Interaction {
         void rightClick(Gob var1);
      }

      interface Navigation {
         PfTestRunner.MoveToAutoOpenGroundScenario.MoveResult run(GameUI var1, Coord2d var2, Bot var3, PfTestRunner.Run var4) throws Exception;
      }
   }

   static final class CrossCellarStairsScenario implements PfTestRunner.Scenario {
      static final double MAX_TARGET_DIST = 52.25;
      static final double MAX_INTERACT_DIST = 35.0;
      static final long CROSS_WAIT_MS = 30000L;
      private final PfTestRunner.CrossCellarStairsScenario.Navigation nav;
      private final PfTestRunner.CrossCellarStairsScenario.Interaction interaction;

      CrossCellarStairsScenario() {
         this.nav = (g, t, b, r) -> PfTestRunner.MoveToAutoCaveTransitionApproachScenario.liveMove(g, t, b);
         this.interaction = gob -> gob.rclick(0);
      }

      CrossCellarStairsScenario(PfTestRunner.CrossCellarStairsScenario.Navigation nav, PfTestRunner.CrossCellarStairsScenario.Interaction interaction) {
         this.nav = nav;
         this.interaction = interaction;
      }

      @Override
      public String name() {
         return "cross_cellar_stairs";
      }

      @Override
      public JSONObject execute(PfTestRunner.Run run, UI ui) throws Exception {
         if (run.cancelled) {
            throw new PfTestRunner.Cancelled();
         } else {
            GameUI gui = ui == null ? null : ui.gui;
            boolean inGame = gui != null && gui.map != null;
            boolean present = false;
            boolean idle = false;
            boolean mapfile = inGame && gui.mapfile != null && gui.mapfile.file != null;
            if (inGame) {
               synchronized (ui) {
                  Gob p = gui.map.player();
                  present = p != null && p.rc != null;
                  idle = p == null || p.getattr(Moving.class) == null;
               }
            }

            List<JSONObject> checks = PfTestRunner.autoMovePreflightChecks("cross_cellar_stairs", inGame, present, mapfile, idle, Bot.hasCurrent());
            if ("FAIL".equals(PfTestRunner.verdictOf(checks))) {
               return PfTestRunner.body(
                  checks, "no interaction performed: " + PfTestRunner.firstFailDetail(checks), facts(null, null, false, "NOT_STARTED", "PREFLIGHT")
               );
            } else {
               PrototypePathfinder.Scene scene;
               synchronized (ui) {
                  scene = PrototypePathfinder.observe(gui);
               }

               TransitionApproachSelector.Selection sel = TransitionApproachSelector.caveTransitionApproach(scene);
               checks.add(PfTestRunner.SelectCaveTransitionApproachScenario.selectionCheck(sel));
               boolean kind = supportedKind(sel);
               checks.add(kindCheck(kind));
               if (!sel.refused() && kind) {
                  PrototypePathfinder.Scene fresh;
                  synchronized (ui) {
                     fresh = PrototypePathfinder.observe(gui);
                  }

                  JSONObject ar = PfTestRunner.MoveToAutoCaveTransitionApproachScenario.approachRevalidationCheck(sel, fresh);
                  checks.add(ar);
                  JSONObject tr = PfTestRunner.MoveToAutoOpenGroundScenario.revalidationCheck(fresh, sel.approachWorld, 52.25);
                  checks.add(tr);
                  if (!"fail".equals(ar.getString("status")) && !"fail".equals(tr.getString("status"))) {
                     Bot bot = Bot.execute(new BotAction[0]);
                     AtomicBoolean timedOut = new AtomicBoolean(false);
                     PfTestRunner.MoveToAutoOpenGroundScenario.MoveResult mv = PfTestRunner.moveWatch(
                        run, timedOut, 120000L, () -> this.nav.run(gui, sel.approachWorld, bot, run)
                     );
                     if (run.cancelled) {
                        throw new PfTestRunner.Cancelled(cancelledBody(sel, mv, false));
                     } else {
                        JSONObject arrival = PfTestRunner.MoveToAutoOpenGroundScenario.finalArrival(gui, ui, mv, sel.approachWorld);
                        checks.add(PfTestRunner.MoveToAutoOpenGroundScenario.routeCheck(mv));
                        checks.add(PfTestRunner.MoveToAutoOpenGroundScenario.walkCheck(mv));
                        checks.add(arrival);
                        if (!timedOut.get() && "pass".equals(arrival.getString("status"))) {
                           Gob stairs;
                           synchronized (ui) {
                              stairs = resolveStairs(gui, PrototypePathfinder.observe(gui));
                           }

                           boolean resolved = stairs != null;
                           checks.add(
                              PfTestRunner.check(
                                 "fixture_resolved",
                                 resolved,
                                 resolved ? "unique fresh cellar stairs resolved" : "unique cellar stairs unavailable (no interaction)"
                              )
                           );
                           if (!resolved) {
                              return PfTestRunner.body(
                                 checks,
                                 "no interaction performed: cellar stairs unavailable",
                                 facts(sel, mv, false, "REVALIDATION_REFUSED", "FIXTURE_UNAVAILABLE")
                              );
                           } else {
                              boolean range;
                              synchronized (ui) {
                                 Gob p = gui.map.player();
                                 range = p != null && p.rc != null && stairs.rc != null && p.rc.dist(stairs.rc) <= 35.0;
                              }

                              checks.add(rangeCheck(range));
                              if (!range) {
                                 return PfTestRunner.body(
                                    checks,
                                    "no interaction performed: cellar stairs out of range",
                                    facts(sel, mv, false, "REVALIDATION_REFUSED", "OUT_OF_RANGE")
                                 );
                              } else if (run.cancelled) {
                                 throw new PfTestRunner.Cancelled(cancelledBody(sel, mv, false));
                              } else {
                                 issueOnce(this.interaction, stairs);
                                 checks.add(interactionCheck());
                                 boolean crossed = PfTestRunner.CrossCellarDoorScenario.awaitCross(run, ui, gui, stairs.id, System.currentTimeMillis() + 30000L);
                                 checks.add(completionCheck(crossed));
                                 if (run.cancelled) {
                                    throw new PfTestRunner.Cancelled(cancelledBody(sel, mv, true));
                                 } else {
                                    return PfTestRunner.body(
                                       checks,
                                       crossed ? null : "one interaction issued; crossing not confirmed",
                                       facts(sel, mv, true, crossed ? "CROSSED" : "NO_RESPONSE", crossed ? null : "NO_RESPONSE")
                                    );
                                 }
                              }
                           }
                        } else {
                           return PfTestRunner.body(
                              checks, "no interaction performed: approach walk failed", facts(sel, mv, false, "WALK_REFUSED", "WALK_REFUSED")
                           );
                        }
                     }
                  } else {
                     return PfTestRunner.body(
                        checks, "no interaction performed: revalidation refused", facts(sel, null, false, "REVALIDATION_REFUSED", "REVALIDATION_REFUSED")
                     );
                  }
               } else {
                  return PfTestRunner.body(
                     checks,
                     "no interaction performed: unsupported transition kind",
                     facts(sel, null, false, "SELECTION_REFUSED", sel.refused() ? sel.refusal.name() : "UNSUPPORTED_KIND")
                  );
               }
            }
         }
      }

      static boolean supportedKind(TransitionApproachSelector.Selection sel) {
         return sel != null && sel.kind == TransitionApproachSelector.TransitionKind.CELLAR_STAIRS;
      }

      static JSONObject kindCheck(boolean supported) {
         return PfTestRunner.check(
            "fixture_kind_supported", supported, supported ? "selected fixture is cellar stairs" : "only CELLAR_STAIRS is supported (no interaction)"
         );
      }

      static PrototypePathfinder.GobGeom uniqueStairsGeom(PrototypePathfinder.Scene scene) {
         PrototypePathfinder.GobGeom found = null;

         for (PrototypePathfinder.GobGeom g : TransitionApproachSelector.caveTransitionGobs(scene)) {
            if (TransitionApproachSelector.caveTransitionKind(g.resid) == TransitionApproachSelector.TransitionKind.CELLAR_STAIRS) {
               if (found != null) {
                  return null;
               }

               found = g;
            }
         }

         return found;
      }

      static Gob resolveStairs(GameUI gui, PrototypePathfinder.Scene scene) {
         PrototypePathfinder.GobGeom found = uniqueStairsGeom(scene);
         return found == null ? null : gui.ui.sess.glob.oc.getgob(found.id);
      }

      static JSONObject rangeCheck(boolean range) {
         return PfTestRunner.check(
            "in_interaction_range",
            range,
            range ? "cellar stairs are within bounded interaction range" : "cellar stairs out of interaction range (no interaction)"
         );
      }

      static void issueOnce(PfTestRunner.CrossCellarStairsScenario.Interaction interaction, Gob stairs) {
         interaction.rightClick(stairs);
      }

      static JSONObject interactionCheck() {
         return PfTestRunner.check("interaction_issued", true, "one cellar-stairs right-click issued");
      }

      static JSONObject completionCheck(boolean crossed) {
         return PfTestRunner.check(
            "crossed", crossed, crossed ? "server relocated player; source cellar stairs absent" : "no confirmed relocation after one click"
         );
      }

      static JSONObject cancelledBody(TransitionApproachSelector.Selection sel, PfTestRunner.MoveToAutoOpenGroundScenario.MoveResult mv, boolean issued) {
         List<JSONObject> checks = new ArrayList<>();
         checks.add(PfTestRunner.check("run_cancelled", false, issued ? "cancelled after one interaction; no retry" : "cancelled before interaction"));
         return PfTestRunner.body(checks, "cancelled", facts(sel, mv, issued, "CANCELLED", "CANCELLED"));
      }

      static JSONObject facts(
         TransitionApproachSelector.Selection sel, PfTestRunner.MoveToAutoOpenGroundScenario.MoveResult mv, boolean issued, String status, String refusal
      ) {
         JSONObject f = PfTestRunner.MoveToAutoCaveTransitionApproachScenario.factsJson(sel, mv, status, refusal);
         f.put("interaction_issued", issued);
         f.put("crossed", "CROSSED".equals(status));
         return f;
      }

      interface Interaction {
         void rightClick(Gob var1);
      }

      interface Navigation {
         PfTestRunner.MoveToAutoOpenGroundScenario.MoveResult run(GameUI var1, Coord2d var2, Bot var3, PfTestRunner.Run var4) throws Exception;
      }
   }

   static final class CrossMineholeScenario implements PfTestRunner.Scenario {
      static final double MAX_TARGET_DIST = 52.25;
      static final double MAX_INTERACT_DIST = 35.0;
      static final long CROSS_WAIT_MS = 30000L;
      static final long CROSS_POLL_MS = 100L;
      private final PfTestRunner.CrossMineholeScenario.Navigation nav;
      private final PfTestRunner.CrossMineholeScenario.Interaction interaction;

      CrossMineholeScenario() {
         this.nav = (g, t, b, r) -> PfTestRunner.MoveToAutoCaveTransitionApproachScenario.liveMove(g, t, b);
         this.interaction = gob -> gob.rclick(0);
      }

      CrossMineholeScenario(PfTestRunner.CrossMineholeScenario.Navigation nav, PfTestRunner.CrossMineholeScenario.Interaction interaction) {
         this.nav = nav;
         this.interaction = interaction;
      }

      @Override
      public String name() {
         return "cross_minehole";
      }

      @Override
      public JSONObject execute(PfTestRunner.Run run, UI ui) throws Exception {
         if (run.cancelled) {
            throw new PfTestRunner.Cancelled();
         } else {
            List<JSONObject> checks = new ArrayList<>();
            boolean confirmed = confirmed();
            checks.add(confirmationCheck(confirmed));
            if (!confirmed) {
               return PfTestRunner.body(
                  checks, "no interaction performed: minehole descent not manually confirmed", facts(null, null, false, "NOT_CONFIRMED", "NOT_CONFIRMED", null)
               );
            } else {
               GameUI gui = ui == null ? null : ui.gui;
               boolean inGame = gui != null && gui.map != null;
               boolean present = false;
               boolean idle = false;
               boolean mapfile = inGame && gui.mapfile != null && gui.mapfile.file != null;
               if (inGame) {
                  synchronized (ui) {
                     Gob p = gui.map.player();
                     present = p != null && p.rc != null;
                     idle = p == null || p.getattr(Moving.class) == null;
                  }
               }

               checks.addAll(PfTestRunner.autoMovePreflightChecks("cross_minehole", inGame, present, mapfile, idle, Bot.hasCurrent()));
               if ("FAIL".equals(PfTestRunner.verdictOf(checks))) {
                  return PfTestRunner.body(
                     checks, "no interaction performed: " + PfTestRunner.firstFailDetail(checks), facts(null, null, false, "NOT_STARTED", "PREFLIGHT", null)
                  );
               } else {
                  PrototypePathfinder.Scene scene;
                  synchronized (ui) {
                     scene = PrototypePathfinder.observe(gui);
                  }

                  TransitionApproachSelector.Selection sel = TransitionApproachSelector.caveTransitionApproach(scene);
                  checks.add(PfTestRunner.SelectCaveTransitionApproachScenario.selectionCheck(sel));
                  if (sel.refused()) {
                     return PfTestRunner.body(
                        checks, "no interaction performed: " + sel.refusal, facts(sel, null, false, "SELECTION_REFUSED", sel.refusal.name(), null)
                     );
                  } else {
                     boolean kind = supportedKind(sel);
                     checks.add(kindCheck(kind));
                     if (!kind) {
                        return PfTestRunner.body(
                           checks,
                           "no interaction performed: unsupported transition kind",
                           facts(sel, null, false, "SELECTION_REFUSED", "UNSUPPORTED_KIND", null)
                        );
                     } else {
                        PrototypePathfinder.Scene fresh;
                        synchronized (ui) {
                           fresh = PrototypePathfinder.observe(gui);
                        }

                        JSONObject ar = PfTestRunner.MoveToAutoCaveTransitionApproachScenario.approachRevalidationCheck(sel, fresh);
                        checks.add(ar);
                        JSONObject tr = PfTestRunner.MoveToAutoOpenGroundScenario.revalidationCheck(fresh, sel.approachWorld, 52.25);
                        checks.add(tr);
                        if (!"fail".equals(ar.getString("status")) && !"fail".equals(tr.getString("status"))) {
                           Bot bot = Bot.execute(new BotAction[0]);
                           AtomicBoolean timedOut = new AtomicBoolean(false);
                           PfTestRunner.MoveToAutoOpenGroundScenario.MoveResult mv = PfTestRunner.moveWatch(
                              run, timedOut, 120000L, () -> this.nav.run(gui, sel.approachWorld, bot, run)
                           );
                           if (run.cancelled) {
                              throw new PfTestRunner.Cancelled(cancelledBody(sel, mv, false));
                           } else {
                              JSONObject arrival = PfTestRunner.MoveToAutoOpenGroundScenario.finalArrival(gui, ui, mv, sel.approachWorld);
                              checks.add(PfTestRunner.MoveToAutoOpenGroundScenario.routeCheck(mv));
                              checks.add(PfTestRunner.MoveToAutoOpenGroundScenario.walkCheck(mv));
                              checks.add(arrival);
                              if (!timedOut.get() && "pass".equals(arrival.getString("status"))) {
                                 Gob hole;
                                 synchronized (ui) {
                                    hole = resolveMinehole(gui, PrototypePathfinder.observe(gui));
                                 }

                                 boolean resolved = hole != null;
                                 checks.add(
                                    PfTestRunner.check(
                                       "fixture_resolved",
                                       resolved,
                                       resolved ? "unique fresh minehole resolved" : "unique minehole unavailable (no interaction)"
                                    )
                                 );
                                 if (!resolved) {
                                    return PfTestRunner.body(
                                       checks,
                                       "no interaction performed: minehole unavailable",
                                       facts(sel, mv, false, "REVALIDATION_REFUSED", "FIXTURE_UNAVAILABLE", null)
                                    );
                                 } else {
                                    boolean range;
                                    synchronized (ui) {
                                       Gob p = gui.map.player();
                                       range = p != null && p.rc != null && hole.rc != null && p.rc.dist(hole.rc) <= 35.0;
                                    }

                                    checks.add(rangeCheck(range));
                                    if (!range) {
                                       return PfTestRunner.body(
                                          checks,
                                          "no interaction performed: minehole out of range",
                                          facts(sel, mv, false, "REVALIDATION_REFUSED", "OUT_OF_RANGE", null)
                                       );
                                    } else if (run.cancelled) {
                                       throw new PfTestRunner.Cancelled(cancelledBody(sel, mv, false));
                                    } else {
                                       PfTestRunner.MineholeDescent.Observation before;
                                       synchronized (ui) {
                                          before = observeLocation(ui, gui);
                                       }

                                       checks.add(observationCheck(before != null));
                                       if (before == null) {
                                          return PfTestRunner.body(
                                             checks,
                                             "no interaction performed: pre-descent location unavailable",
                                             facts(sel, mv, false, "REVALIDATION_REFUSED", "LOCATION_UNAVAILABLE", null)
                                          );
                                       } else {
                                          issueOnce(this.interaction, hole);
                                          checks.add(interactionCheck());
                                          PfTestRunner.MineholeDescent.Completion completion = awaitDescent(
                                             run, ui, gui, before, System.currentTimeMillis() + 30000L
                                          );
                                          checks.add(completionCheck(completion));
                                          if (run.cancelled) {
                                             throw new PfTestRunner.Cancelled(cancelledBody(sel, mv, true));
                                          } else {
                                             boolean descended = completion == PfTestRunner.MineholeDescent.Completion.DESCENDED;
                                             return PfTestRunner.body(
                                                checks,
                                                descended ? null : "one interaction issued; descent not confirmed",
                                                facts(
                                                   sel, mv, true, descended ? "DESCENDED" : "NO_RESPONSE", descended ? null : "NO_RESPONSE", completion.name()
                                                )
                                             );
                                          }
                                       }
                                    }
                                 }
                              } else {
                                 return PfTestRunner.body(
                                    checks, "no interaction performed: approach walk failed", facts(sel, mv, false, "WALK_REFUSED", "WALK_REFUSED", null)
                                 );
                              }
                           }
                        } else {
                           return PfTestRunner.body(
                              checks,
                              "no interaction performed: revalidation refused",
                              facts(sel, null, false, "REVALIDATION_REFUSED", "REVALIDATION_REFUSED", null)
                           );
                        }
                     }
                  }
               }
            }
         }
      }

      static boolean confirmed() {
         return "true".equalsIgnoreCase(System.getProperty("haven.pf.minehole.confirm", "false").trim());
      }

      static JSONObject confirmationCheck(boolean confirmed) {
         return PfTestRunner.check(
            "minehole_confirmed",
            confirmed,
            confirmed
               ? "launch-time manual confirmation present (haven.pf.minehole.confirm=true)"
               : "minehole descent requires manual confirmation at launch (-Dhaven.pf.minehole.confirm=true); no interaction"
         );
      }

      static boolean supportedKind(TransitionApproachSelector.Selection sel) {
         return sel != null && sel.kind == TransitionApproachSelector.TransitionKind.MINEHOLE;
      }

      static JSONObject kindCheck(boolean supported) {
         return PfTestRunner.check(
            "fixture_kind_supported", supported, supported ? "selected fixture is a minehole" : "only MINEHOLE is supported (no interaction)"
         );
      }

      static PrototypePathfinder.GobGeom uniqueMineholeGeom(PrototypePathfinder.Scene scene) {
         PrototypePathfinder.GobGeom found = null;

         for (PrototypePathfinder.GobGeom g : TransitionApproachSelector.caveTransitionGobs(scene)) {
            if (TransitionApproachSelector.caveTransitionKind(g.resid) == TransitionApproachSelector.TransitionKind.MINEHOLE) {
               if (found != null) {
                  return null;
               }

               found = g;
            }
         }

         return found;
      }

      static Gob resolveMinehole(GameUI gui, PrototypePathfinder.Scene scene) {
         PrototypePathfinder.GobGeom found = uniqueMineholeGeom(scene);
         return found == null ? null : gui.ui.sess.glob.oc.getgob(found.id);
      }

      static JSONObject rangeCheck(boolean range) {
         return PfTestRunner.check(
            "in_interaction_range", range, range ? "minehole is within bounded interaction range" : "minehole out of interaction range (no interaction)"
         );
      }

      static void issueOnce(PfTestRunner.CrossMineholeScenario.Interaction interaction, Gob hole) {
         interaction.rightClick(hole);
      }

      static JSONObject interactionCheck() {
         return PfTestRunner.check("interaction_issued", true, "one minehole right-click issued");
      }

      static PfTestRunner.MineholeDescent.Observation observeLocation(UI ui, GameUI gui) {
         synchronized (ui) {
            NamedPlaceNavigator.Location loc = NamedPlaceNavigator.liveState(gui).current();
            if (loc == null) {
               return null;
            } else {
               PrototypePathfinder.Scene scene = PrototypePathfinder.observe(gui);
               if (scene == null) {
                  return null;
               } else {
                  Gob p = gui.map.player();
                  boolean idle = p == null || p.getattr(Moving.class) == null;
                  return new PfTestRunner.MineholeDescent.Observation(loc.seg, loc.world, idle, !scene.playerInSolid);
               }
            }
         }
      }

      static JSONObject observationCheck(boolean available) {
         return PfTestRunner.check(
            "location_observed",
            available,
            available ? "pre-descent segment/world/idle/body-free observation captured" : "pre-descent location observation unavailable (no interaction)"
         );
      }

      static PfTestRunner.MineholeDescent.Completion awaitDescent(
         PfTestRunner.Run run, UI ui, GameUI gui, PfTestRunner.MineholeDescent.Observation before, long deadline
      ) throws InterruptedException {
         PfTestRunner.MineholeDescent.Completion last = PfTestRunner.MineholeDescent.Completion.LOCATION_UNAVAILABLE;

         while (System.currentTimeMillis() < deadline) {
            if (run.cancelled) {
               return last;
            }

            PfTestRunner.MineholeDescent.Observation after = observeLocation(ui, gui);
            last = PfTestRunner.MineholeDescent.completion(before, after);
            if (last == PfTestRunner.MineholeDescent.Completion.DESCENDED) {
               return last;
            }

            Thread.sleep(100L);
         }

         return last;
      }

      static JSONObject completionCheck(PfTestRunner.MineholeDescent.Completion completion) {
         boolean descended = completion == PfTestRunner.MineholeDescent.Completion.DESCENDED;
         return PfTestRunner.check(
            "crossed",
            descended,
            descended
               ? "server relocated player to a new segment; idle and body-free landing confirmed"
               : "no confirmed descent after one click (" + completion + ")"
         );
      }

      static JSONObject cancelledBody(TransitionApproachSelector.Selection sel, PfTestRunner.MoveToAutoOpenGroundScenario.MoveResult mv, boolean issued) {
         List<JSONObject> checks = new ArrayList<>();
         checks.add(PfTestRunner.check("run_cancelled", false, issued ? "cancelled after one interaction; no retry" : "cancelled before interaction"));
         return PfTestRunner.body(checks, "cancelled", facts(sel, mv, issued, "CANCELLED", "CANCELLED", null));
      }

      static JSONObject facts(
         TransitionApproachSelector.Selection sel,
         PfTestRunner.MoveToAutoOpenGroundScenario.MoveResult mv,
         boolean issued,
         String status,
         String refusal,
         String completion
      ) {
         JSONObject f = PfTestRunner.MoveToAutoCaveTransitionApproachScenario.factsJson(sel, mv, status, refusal);
         f.put("interaction_issued", issued);
         f.put("crossed", "DESCENDED".equals(status));
         if (completion != null) {
            f.put("descent_completion", completion);
         }

         return f;
      }

      interface Interaction {
         void rightClick(Gob var1);
      }

      interface Navigation {
         PfTestRunner.MoveToAutoOpenGroundScenario.MoveResult run(GameUI var1, Coord2d var2, Bot var3, PfTestRunner.Run var4) throws Exception;
      }
   }

   static final class MineholeDescent {
      static final double MIN_DESCENT_DISP_U = 704.0;

      static PfTestRunner.MineholeDescent.Completion completion(PfTestRunner.MineholeDescent.Observation before, PfTestRunner.MineholeDescent.Observation after) {
         if (before == null || after == null || before.world == null || after.world == null) {
            return PfTestRunner.MineholeDescent.Completion.LOCATION_UNAVAILABLE;
         } else if (!after.idle) {
            return PfTestRunner.MineholeDescent.Completion.STILL_MOVING;
         } else if (!after.bodyFree) {
            return PfTestRunner.MineholeDescent.Completion.STUCK_IN_SOLID;
         } else if (before.segment == after.segment) {
            return PfTestRunner.MineholeDescent.Completion.SAME_SEGMENT;
         } else {
            return before.world.dist(after.world) < 704.0
               ? PfTestRunner.MineholeDescent.Completion.INSUFFICIENT_DISPLACEMENT
               : PfTestRunner.MineholeDescent.Completion.DESCENDED;
         }
      }

      static enum Completion {
         DESCENDED,
         LOCATION_UNAVAILABLE,
         STILL_MOVING,
         STUCK_IN_SOLID,
         SAME_SEGMENT,
         INSUFFICIENT_DISPLACEMENT;
      }

      static final class Observation {
         final long segment;
         final Coord2d world;
         final boolean idle;
         final boolean bodyFree;

         Observation(long segment, Coord2d world, boolean idle, boolean bodyFree) {
            this.segment = segment;
            this.world = world;
            this.idle = idle;
            this.bodyFree = bodyFree;
         }
      }
   }

   static final class MoveToAutoCaveTransitionApproachScenario implements PfTestRunner.Scenario {
      static final double MAX_TARGET_DIST = 52.25;
      static final double SAME_APPROACH_EPS = 0.01;
      private final PfTestRunner.MoveToAutoCaveTransitionApproachScenario.Navigation nav;

      MoveToAutoCaveTransitionApproachScenario() {
         this.nav = PfTestRunner.MoveToAutoCaveTransitionApproachScenario.Navigation.LIVE;
      }

      MoveToAutoCaveTransitionApproachScenario(PfTestRunner.MoveToAutoCaveTransitionApproachScenario.Navigation nav) {
         this.nav = nav;
      }

      @Override
      public String name() {
         return "move_to_auto_cave_transition_approach";
      }

      @Override
      public JSONObject execute(PfTestRunner.Run run, UI ui) throws Exception {
         if (run.cancelled) {
            throw new PfTestRunner.Cancelled();
         } else {
            GameUI gui = ui == null ? null : ui.gui;
            boolean inGame = gui != null && gui.map != null;
            boolean playerPresent = false;
            boolean mapfileAvailable = false;
            boolean playerIdle = false;
            if (inGame) {
               synchronized (ui) {
                  Gob me = gui.map.player();
                  playerPresent = me != null && me.rc != null;
                  playerIdle = me == null || me.getattr(Moving.class) == null;
               }

               mapfileAvailable = gui.mapfile != null && gui.mapfile.file != null;
            }

            List<JSONObject> checks = preflightChecks(inGame, playerPresent, mapfileAvailable, playerIdle, Bot.hasCurrent());
            if (PfTestRunner.verdictOf(checks).equals("FAIL")) {
               String why = PfTestRunner.firstFailDetail(checks);
               return PfTestRunner.body(checks, "no movement performed: " + why, factsJson(null, null, "NOT_STARTED", why));
            } else if (run.cancelled) {
               throw new PfTestRunner.Cancelled();
            } else {
               PrototypePathfinder.Scene scene;
               synchronized (ui) {
                  scene = PrototypePathfinder.observe(gui);
               }

               TransitionApproachSelector.Selection sel = TransitionApproachSelector.caveTransitionApproach(scene);
               checks.add(PfTestRunner.SelectCaveTransitionApproachScenario.selectionCheck(sel));
               if (sel.refused()) {
                  return PfTestRunner.body(checks, "no movement performed: " + sel.refusal, factsJson(sel, null, "SELECTION_REFUSED", null));
               } else if (run.cancelled) {
                  throw new PfTestRunner.Cancelled();
               } else {
                  PrototypePathfinder.Scene fresh;
                  synchronized (ui) {
                     fresh = PrototypePathfinder.observe(gui);
                  }

                  JSONObject approach = approachRevalidationCheck(sel, fresh);
                  checks.add(approach);
                  if ("fail".equals(approach.getString("status"))) {
                     return PfTestRunner.body(
                        checks, "no movement performed: " + approach.getString("detail"), factsJson(sel, null, "REVALIDATION_REFUSED", null)
                     );
                  } else {
                     JSONObject target = PfTestRunner.MoveToAutoOpenGroundScenario.revalidationCheck(fresh, sel.approachWorld, 52.25);
                     checks.add(target);
                     if ("fail".equals(target.getString("status"))) {
                        return PfTestRunner.body(
                           checks, "no movement performed: " + target.getString("detail"), factsJson(sel, null, "REVALIDATION_REFUSED", null)
                        );
                     } else if (run.cancelled) {
                        throw new PfTestRunner.Cancelled();
                     } else {
                        Bot bot = Bot.execute(new BotAction[0]);
                        AtomicBoolean timedOut = new AtomicBoolean(false);
                        PfTestRunner.MoveToAutoOpenGroundScenario.MoveResult mv = PfTestRunner.moveWatch(
                           run, timedOut, 120000L, () -> this.nav.run(gui, sel.approachWorld, bot, run)
                        );
                        if (run.cancelled) {
                           throw new PfTestRunner.Cancelled(cancelledBody(sel, mv));
                        } else {
                           return completedBody(sel, mv, timedOut.get(), PfTestRunner.MoveToAutoOpenGroundScenario.finalArrival(gui, ui, mv, sel.approachWorld));
                        }
                     }
                  }
               }
            }
         }
      }

      static PfTestRunner.MoveToAutoOpenGroundScenario.MoveResult liveMove(GameUI gui, Coord2d target, Bot bot) {
         PrototypePathfinder.Plan plan = PrototypePathfinder.planAny(gui, Collections.singletonList(target), true);
         return PfTestRunner.MoveToAutoOpenGroundScenario.walkPlan(gui, plan, target, bot, 60000L, 60000L);
      }

      static List<JSONObject> preflightChecks(boolean inGame, boolean playerPresent, boolean mapfileAvailable, boolean playerIdle, boolean botBusy) {
         return PfTestRunner.autoMovePreflightChecks("move_to_auto_cave_transition_approach", inGame, playerPresent, mapfileAvailable, playerIdle, botBusy);
      }

      static JSONObject approachRevalidationCheck(TransitionApproachSelector.Selection selected, PrototypePathfinder.Scene fresh) {
         if (selected != null && !selected.refused() && selected.approachWorld != null) {
            TransitionApproachSelector.Selection now = TransitionApproachSelector.caveTransitionApproach(fresh);
            if (now.refused()) {
               return PfTestRunner.check("approach_revalidated", false, "fresh cave approach refused " + now.refusal + ": " + now.evidence + " (no-move)");
            } else {
               return now.kind == selected.kind && now.approachWorld != null && !(now.approachWorld.dist(selected.approachWorld) > 0.01)
                  ? PfTestRunner.check("approach_revalidated", true, "same " + selected.kind + " approach remains selected in the fresh observation")
                  : PfTestRunner.check("approach_revalidated", false, "fresh cave approach changed (no-move)");
            }
         } else {
            return PfTestRunner.check("approach_revalidated", false, "no selected cave approach to revalidate (no-move)");
         }
      }

      static JSONObject cancelledBody(TransitionApproachSelector.Selection sel, PfTestRunner.MoveToAutoOpenGroundScenario.MoveResult mv) {
         List<JSONObject> checks = new ArrayList<>();
         checks.add(PfTestRunner.MoveToAutoOpenGroundScenario.routeCheck(mv));
         checks.add(PfTestRunner.MoveToAutoOpenGroundScenario.walkCheck(mv));
         checks.add(PfTestRunner.check("run_cancelled", false, "the run was cancelled; no transition was clicked or crossed"));
         return PfTestRunner.body(checks, "cancelled", factsJson(sel, mv, "WALKED", null));
      }

      static JSONObject completedBody(
         TransitionApproachSelector.Selection sel, PfTestRunner.MoveToAutoOpenGroundScenario.MoveResult mv, boolean timedOut, JSONObject arrival
      ) {
         List<JSONObject> checks = new ArrayList<>();
         if (timedOut) {
            checks.add(PfTestRunner.check("move_timeout", false, "hard wall-clock deadline exceeded; movement interrupted"));
         }

         checks.add(PfTestRunner.MoveToAutoOpenGroundScenario.routeCheck(mv));
         checks.add(PfTestRunner.MoveToAutoOpenGroundScenario.walkCheck(mv));
         if (arrival != null) {
            checks.add(arrival);
         }

         boolean walked = mv != null && (mv.walk != null || mv.cancelledDetail != null);
         return PfTestRunner.body(
            checks, timedOut ? "hard wall-clock deadline exceeded; movement interrupted" : null, factsJson(sel, mv, walked ? "WALKED" : "ROUTE_REFUSED", null)
         );
      }

      static JSONObject factsJson(
         TransitionApproachSelector.Selection sel, PfTestRunner.MoveToAutoOpenGroundScenario.MoveResult mv, String status, String reason
      ) {
         JSONObject f = sel == null
            ? PfTestRunner.SelectCaveTransitionApproachScenario.refusalFacts("PREFLIGHT", reason == null ? "preflight refused" : reason)
            : PfTestRunner.SelectCaveTransitionApproachScenario.factsJson(sel);
         f.put("moved", mv != null && (mv.walk != null || mv.cancelledDetail != null));
         f.put("arrived", mv != null && mv.walk == WaypointWalker.Result.ARRIVED);
         f.put("status", status == null ? "NOT_STARTED" : status);
         if (mv != null) {
            f.put("plan_status", mv.planStatus.name());
            f.put("expanded", mv.expanded);
            f.put("obstacles", mv.obstacles);
            if (mv.cancelledDetail != null) {
               f.put("walk_outcome", "CANCELLED");
            } else if (mv.walk != null) {
               f.put("walk_outcome", mv.walk.name());
            }

            if (mv.endPos != null) {
               f.put("end_pos", new JSONArray().put(PfTestRunner.round2(mv.endPos.x)).put(PfTestRunner.round2(mv.endPos.y)));
            }

            f.put("elapsed_ms", mv.elapsedMs);
         }

         return f;
      }

      interface Navigation {
         PfTestRunner.MoveToAutoCaveTransitionApproachScenario.Navigation LIVE = (gui, target, bot, run) -> PfTestRunner.MoveToAutoCaveTransitionApproachScenario.liveMove(
               gui, target, bot
            );

         PfTestRunner.MoveToAutoOpenGroundScenario.MoveResult run(GameUI var1, Coord2d var2, Bot var3, PfTestRunner.Run var4) throws Exception;
      }
   }

   static final class MoveToAutoKnownLongLegScenario implements PfTestRunner.Scenario {
      static final int MOVE_MAX_LEGS = 40;
      static final int MOVE_MAX_REPLANS = 5;
      static final long MOVE_WAYPOINT_TIMEOUT_MS = 90000L;
      static final long MOVE_LEG_BUDGET_MS = 120000L;
      static final long MOVE_TOTAL_TIMEOUT_MS = 240000L;
      static final double ARRIVAL_EPS = 3.0;
      static final RecedingHorizonNavigator.Bounds NAV_BOUNDS = new RecedingHorizonNavigator.Bounds(40, 5, 1000000);
      private final PfTestRunner.MoveToAutoKnownLongLegScenario.Navigation nav;

      MoveToAutoKnownLongLegScenario() {
         this.nav = PfTestRunner.MoveToAutoKnownLongLegScenario.Navigation.LIVE;
      }

      MoveToAutoKnownLongLegScenario(PfTestRunner.MoveToAutoKnownLongLegScenario.Navigation nav) {
         this.nav = nav;
      }

      @Override
      public String name() {
         return "move_to_auto_known_long_leg";
      }

      @Override
      public JSONObject execute(PfTestRunner.Run run, UI ui) throws Exception {
         if (run.cancelled) {
            throw new PfTestRunner.Cancelled();
         } else {
            GameUI gui = ui == null ? null : ui.gui;
            boolean inGame = gui != null && gui.map != null;
            boolean playerPresent = false;
            boolean mapfileAvailable = false;
            boolean playerIdle = false;
            if (inGame) {
               synchronized (ui) {
                  Gob me = gui.map.player();
                  playerPresent = me != null && me.rc != null;
                  playerIdle = me == null || me.getattr(Moving.class) == null;
               }

               mapfileAvailable = gui.mapfile != null && gui.mapfile.file != null;
            }

            List<JSONObject> checks = preflightChecks(inGame, playerPresent, mapfileAvailable, playerIdle, Bot.hasCurrent());
            if (PfTestRunner.verdictOf(checks).equals("FAIL")) {
               String why = PfTestRunner.firstFailDetail(checks);
               return PfTestRunner.body(checks, "no movement performed: " + why, factsJson(null, null, null, "NOT_STARTED", why));
            } else if (run.cancelled) {
               throw new PfTestRunner.Cancelled();
            } else {
               NamedPlaceNavigator.Location start;
               MapFile file;
               synchronized (ui) {
                  file = gui.mapfile == null ? null : gui.mapfile.file;
                  start = NamedPlaceNavigator.liveState(gui).current();
               }

               if (file != null && start != null) {
                  checks.add(PfTestRunner.check("session_state", true, String.format("segment %x, start tile %s", start.seg, start.tile)));
                  if (run.cancelled) {
                     throw new PfTestRunner.Cancelled();
                  } else {
                     Area bounds = selectBounds(start.tile);
                     MapFileTileSource src = MapFileTileSource.of(file, start.seg, bounds);
                     NavigationTestSpotSelector.Selection sel = NavigationTestSpotSelector.knownMapLongLeg(
                        src, start.seg, start.tile.sub(bounds.ul), 12.0, 24.0, 40000, 2000000
                     );
                     checks.add(PfTestRunner.NavigationTestSpotScenario.selectionCheck(sel));
                     if (sel.refused()) {
                        return PfTestRunner.body(checks, "no movement performed: " + sel.refusal, factsJson(null, sel, bounds, "SELECTION_REFUSED", null));
                     } else if (run.cancelled) {
                        throw new PfTestRunner.Cancelled();
                     } else {
                        Coord goalTile = bounds.ul.add(sel.targetTile);
                        boolean freshPresent = false;
                        boolean freshIdle = false;
                        NamedPlaceNavigator.Location fresh;
                        synchronized (ui) {
                           Gob me = gui.map.player();
                           freshPresent = me != null && me.rc != null;
                           freshIdle = me == null || me.getattr(Moving.class) == null;
                           fresh = NamedPlaceNavigator.liveState(gui).current();
                        }

                        CoarseRoutePlanner.Route freshRoute = null;
                        if (fresh != null) {
                           freshRoute = CoarseTileNavigator.planRoute(file, fresh.seg, fresh.tile, goalTile, bounds, NAV_BOUNDS.coarseExpanded);
                        }

                        JSONObject reval = goalRevalidationCheck(fresh, sel.segment, freshPresent, freshIdle, freshRoute);
                        checks.add(reval);
                        if ("fail".equals(reval.getString("status"))) {
                           return PfTestRunner.body(
                              checks, "no movement performed: " + reval.getString("detail"), factsJson(null, sel, bounds, "REVALIDATION_REFUSED", null)
                           );
                        } else if (run.cancelled) {
                           throw new PfTestRunner.Cancelled();
                        } else {
                           Bot bot = Bot.execute(new BotAction[0]);
                           AtomicBoolean timedOut = new AtomicBoolean(false);
                           CoarseTileNavigator.Run navRun = PfTestRunner.moveWatch(run, timedOut, 240000L, () -> this.nav.run(gui, goalTile, bounds, bot, run));
                           if (run.cancelled) {
                              throw new PfTestRunner.Cancelled(cancelledBody(navRun, sel, bounds, timedOut.get()));
                           } else {
                              return completedBody(navRun, sel, bounds, timedOut.get(), finalArrival(gui, ui, navRun, goalTile));
                           }
                        }
                     }
                  }
               } else {
                  checks.add(PfTestRunner.check("session_state", false, "map file or session location unavailable (no-move)"));
                  return PfTestRunner.body(
                     checks, "no movement performed: session state unavailable", factsJson(null, null, null, "NOT_STARTED", "session state unavailable")
                  );
               }
            }
         }
      }

      static CoarseTileNavigator.Run liveNavigation(GameUI gui, Coord goalTile, Area bounds, Bot bot) {
         WaypointWalker.Params params = new WaypointWalker.Params(2.475, 0.6875, 800L, 90000L, 3000L);
         CoarseTileNavigator nav = CoarseTileNavigator.live(gui, gui.mapfile.file, bot, NAV_BOUNDS, 120000L, params, NamedPlaceNavigator.NOOP);
         return nav.navigate(goalTile, bounds);
      }

      private static JSONObject finalArrival(GameUI gui, UI ui, CoarseTileNavigator.Run navRun, Coord goalTile) {
         boolean present = false;
         boolean idle = false;
         Coord2d at = null;
         if (gui != null && gui.map != null && ui != null) {
            synchronized (ui) {
               Gob me = gui.map.player();
               present = me != null && me.rc != null;
               idle = me == null || me.getattr(Moving.class) == null;
               at = present ? me.rc : null;
            }
         }

         Coord2d goalWorld = null;
         if (navRun.start != null && goalTile != null) {
            goalWorld = NamedPlaceNavigator.worldPos(goalTile, NamedPlaceNavigator.translation(navRun.start));
         }

         return arrivalCheck(navRun.reached(), present, idle, at, goalWorld);
      }

      static List<JSONObject> preflightChecks(boolean inGame, boolean playerPresent, boolean mapfileAvailable, boolean playerIdle, boolean botBusy) {
         return PfTestRunner.autoMovePreflightChecks("move_to_auto_known_long_leg", inGame, playerPresent, mapfileAvailable, playerIdle, botBusy);
      }

      static Area selectBounds(Coord startTile) {
         return PfTestRunner.NavigationTestSpotScenario.selectBounds(startTile);
      }

      static JSONObject goalRevalidationCheck(
         NamedPlaceNavigator.Location fresh, long selectedSeg, boolean playerPresent, boolean playerIdle, CoarseRoutePlanner.Route freshRoute
      ) {
         if (fresh == null) {
            return PfTestRunner.check("goal_revalidated", false, "session location unavailable at revalidation (no-move)");
         } else if (fresh.seg != selectedSeg) {
            return PfTestRunner.check(
               "goal_revalidated", false, String.format("player is in segment %x but the frozen goal is in segment %x (no-move)", fresh.seg, selectedSeg)
            );
         } else if (!playerPresent) {
            return PfTestRunner.check("goal_revalidated", false, "player gob not observable at revalidation (no-move)");
         } else if (!playerIdle) {
            return PfTestRunner.check("goal_revalidated", false, "player is moving at revalidation; refusing to move (no-move)");
         } else {
            return freshRoute != null && freshRoute.reached()
               ? PfTestRunner.check(
                  "goal_revalidated",
                  true,
                  String.format(
                     "same segment %x, player idle; fresh coarse route REACHED (%d waypoints, %d expanded)",
                     fresh.seg,
                     freshRoute.waypoints.size(),
                     freshRoute.expanded
                  )
               )
               : PfTestRunner.check(
                  "goal_revalidated",
                  false,
                  "fresh coarse route to the frozen goal is not REACHED (" + (freshRoute == null ? "no plan" : freshRoute.status) + "); refusing (no-move)"
               );
         }
      }

      static List<JSONObject> outcomeChecks(CoarseTileNavigator.Run navRun, boolean timedOut) {
         List<JSONObject> checks = new ArrayList<>();
         switch (navRun.status) {
            case UNAVAILABLE:
               checks.add(PfTestRunner.check("session_state", false, "session location unavailable (no map file/segment/player)"));
               return checks;
            case ROUTE_REJECTED:
               checks.add(PfTestRunner.check("coarse_route", false, routeRefusal(navRun)));
               return checks;
            case NAVIGATED:
               if (timedOut) {
                  checks.add(PfTestRunner.check("move_timeout", false, "hard wall-clock deadline exceeded (240000ms); navigation interrupted"));
               }

               switch (navRun.navOutcome) {
                  case REACHED_DESTINATION:
                     checks.add(
                        PfTestRunner.check(
                           "navigation_completed",
                           true,
                           "verified arrival at the goal tile " + navRun.goalTile + " (legs=" + navRun.legs + ", replans=" + navRun.replans + ")"
                        )
                     );
                     break;
                  case CANCELLED:
                     checks.add(PfTestRunner.check("navigation_completed", false, "cancelled: " + (navRun.detail == null ? "bot cancelled" : navRun.detail)));
                     break;
                  case TERMINAL_FAILURE:
                     checks.add(PfTestRunner.check("navigation_completed", false, "walker failure: " + (navRun.detail == null ? "unknown" : navRun.detail)));
                     break;
                  case LEG_LIMIT_EXHAUSTED:
                     checks.add(PfTestRunner.check("navigation_completed", false, "leg limit exhausted (40 legs)"));
                     break;
                  case REPLAN_LIMIT_EXHAUSTED:
                     checks.add(PfTestRunner.check("navigation_completed", false, "replan limit exhausted (5 replans)"));
                     break;
                  case COARSE_PLAN_FAILED:
                     checks.add(PfTestRunner.check("navigation_completed", false, "coarse plan failed: " + (navRun.detail == null ? "unknown" : navRun.detail)));
                     break;
                  case COARSE_PLAN_INVALID:
                     checks.add(
                        PfTestRunner.check("navigation_completed", false, "coarse plan invalid: " + (navRun.detail == null ? "unknown" : navRun.detail))
                     );
                     break;
                  default:
                     throw new AssertionError(navRun.navOutcome);
               }

               return checks;
            default:
               throw new AssertionError(navRun.status);
         }
      }

      private static String routeRefusal(CoarseTileNavigator.Run navRun) {
         if (navRun.routeStatus == null) {
            return "route refused (unknown status)";
         } else {
            switch (navRun.routeStatus) {
               case INVALID_START:
                  return "start tile outside the planning bounds or unknown/blocked (INVALID_START)";
               case INVALID_GOAL:
                  return "goal tile outside the planning bounds or unknown/blocked (INVALID_GOAL)";
               case NO_KNOWN_ROUTE:
                  return "no known coarse route to the goal tile "
                     + navRun.goalTile
                     + " (NO_KNOWN_ROUTE"
                     + (navRun.routeCause == null ? "" : ":" + navRun.routeCause)
                     + ")";
               case EXHAUSTED:
                  return "route search exhausted its expansion budget (EXHAUSTED)";
               default:
                  return "route refused (" + navRun.routeStatus + ")";
            }
         }
      }

      static JSONObject arrivalCheck(boolean navigationVerified, boolean playerPresent, boolean playerIdle, Coord2d at, Coord2d goalWorld) {
         if (!navigationVerified) {
            return PfTestRunner.check("arrived_idle_at_goal", false, "navigation did not verify arrival at the goal tile");
         } else if (!playerPresent) {
            return PfTestRunner.check("arrived_idle_at_goal", false, "player gob not observable at the final check");
         } else if (!playerIdle) {
            return PfTestRunner.check("arrived_idle_at_goal", false, "player is still moving after navigation (server-confirmed idle arrival required)");
         } else if (at != null && goalWorld != null) {
            double d = at.dist(goalWorld);
            return PfTestRunner.check(
               "arrived_idle_at_goal", d <= 3.0, String.format("idle at (%.1f, %.1f), %.2f units from the goal tile center (eps %.2f)", at.x, at.y, d, 3.0)
            );
         } else {
            return PfTestRunner.check("arrived_idle_at_goal", false, "final position or goal position unavailable");
         }
      }

      static JSONObject cancelledBody(CoarseTileNavigator.Run navRun, NavigationTestSpotSelector.Selection sel, Area bounds, boolean timedOut) {
         List<JSONObject> checks = outcomeChecks(navRun, timedOut);
         checks.add(PfTestRunner.check("run_cancelled", false, "the run was cancelled (POST /pf/cancel); terminal navigation facts above are partial"));
         return PfTestRunner.body(
            checks,
            "cancelled: " + (navRun.detail == null ? "run cancelled" : navRun.detail),
            factsJson(navRun, sel, bounds, navRun.status == CoarseTileNavigator.RunStatus.NAVIGATED ? "NAVIGATED" : navRun.status.name(), null)
         );
      }

      static JSONObject completedBody(
         CoarseTileNavigator.Run navRun, NavigationTestSpotSelector.Selection sel, Area bounds, boolean timedOut, JSONObject arrival
      ) {
         List<JSONObject> checks = outcomeChecks(navRun, timedOut);
         if (arrival != null) {
            checks.add(arrival);
         }

         String note = null;
         if (timedOut) {
            note = "hard wall-clock deadline exceeded (240000ms); navigation interrupted";
         } else if (navRun.cancelled()) {
            note = "navigation cancelled (detail: " + navRun.detail + ")";
         } else if (!navRun.reached()) {
            note = "navigation did not verify arrival at the goal tile";
         }

         return PfTestRunner.body(
            checks, note, factsJson(navRun, sel, bounds, navRun.status == CoarseTileNavigator.RunStatus.NAVIGATED ? "NAVIGATED" : navRun.status.name(), null)
         );
      }

      static JSONObject factsJson(CoarseTileNavigator.Run navRun, NavigationTestSpotSelector.Selection sel, Area bounds, String status, String reason) {
         JSONObject f = new JSONObject();
         f.put("moved", navRun != null && navRun.status == CoarseTileNavigator.RunStatus.NAVIGATED);
         f.put("arrived", navRun != null && navRun.reached());
         f.put("profile", NavigationTestSpotSelector.Profile.KNOWN_MAP_LONG_LEG.name());
         f.put("status", status == null ? "NOT_STARTED" : status);
         if (sel != null) {
            f.put("selected", sel.selected());
            f.put("refusal", sel.refused() ? sel.refusal.name() : JSONObject.NULL);
            f.put("reason", sel.evidence);
            f.put("candidates", sel.candidates.size());
            if (!sel.candidates.isEmpty()) {
               f.put("best_score", PfTestRunner.round2(sel.candidates.get(0).score));
            }
         } else {
            f.put("selected", false);
            f.put("refusal", "PREFLIGHT");
            f.put("reason", reason == null ? "preflight refused" : reason);
            f.put("candidates", 0);
         }

         if (navRun != null) {
            f.put("goal_tile", new JSONArray().put(navRun.goalTile.x).put(navRun.goalTile.y));
            if (navRun.start != null) {
               f.put("segment", String.format("%x", navRun.start.seg));
            }
         } else if (sel != null && sel.targetTile != null && bounds != null) {
            f.put("goal_tile", new JSONArray().put(bounds.ul.x + sel.targetTile.x).put(bounds.ul.y + sel.targetTile.y));
            f.put("segment", String.format("%x", sel.segment));
         }

         if (navRun != null) {
            if (navRun.start != null) {
               f.put("start_tile", new JSONArray().put(navRun.start.tile.x).put(navRun.start.tile.y));
            }

            if (navRun.routeStatus != null) {
               f.put("route_kind", navRun.routeStatus.name());
            }

            if (navRun.routeStatus == CoarseRoutePlanner.Status.REACHED) {
               f.put("waypoint_count", navRun.waypointCount);
            }

            f.put("expanded", navRun.expanded);
            if (navRun.navOutcome != null) {
               f.put("nav_outcome", navRun.navOutcome.name());
               f.put("legs", navRun.legs);
               f.put("replans", navRun.replans);
               f.put("route_index", navRun.routeIndex);
               if (navRun.detail != null) {
                  f.put("detail", navRun.detail);
               }

               if (navRun.endPos != null) {
                  f.put("end_pos", new JSONArray().put(PfTestRunner.round2(navRun.endPos.x)).put(PfTestRunner.round2(navRun.endPos.y)));
               }
            }

            f.put("elapsed_ms", navRun.elapsedMs);
            JSONArray replans = new JSONArray();

            for (CoarseTileNavigator.ReplanFact r : navRun.replanFacts) {
               JSONObject rf = new JSONObject();
               rf.put("index", r.index);
               rf.put("status", r.status.name());
               if (r.cause != null) {
                  rf.put("cause", r.cause.name());
               }

               rf.put("expanded", r.expanded);
               replans.put(rf);
            }

            f.put("replan_facts", replans);
         }

         return f;
      }

      interface Navigation {
         PfTestRunner.MoveToAutoKnownLongLegScenario.Navigation LIVE = (gui, goalTile, bounds, bot, run) -> PfTestRunner.MoveToAutoKnownLongLegScenario.liveNavigation(
               gui, goalTile, bounds, bot
            );

         CoarseTileNavigator.Run run(GameUI var1, Coord var2, Area var3, Bot var4, PfTestRunner.Run var5) throws Exception;
      }
   }

   static final class MoveToAutoObstacleCorridorScenario implements PfTestRunner.Scenario {
      static final long MOVE_WAYPOINT_TIMEOUT_MS = 60000L;
      static final long MOVE_WALK_BUDGET_MS = 60000L;
      static final long MOVE_TOTAL_TIMEOUT_MS = 120000L;
      static final double MAX_TARGET_DIST = 44.0;
      static final double ARRIVAL_EPS = 3.0;
      static final int MIN_NONTRIVIAL_WAYPOINTS = 3;
      static final double MIN_DETOUR_RATIO = 1.05;
      private final PfTestRunner.MoveToAutoObstacleCorridorScenario.Navigation nav;

      MoveToAutoObstacleCorridorScenario() {
         this.nav = PfTestRunner.MoveToAutoObstacleCorridorScenario.Navigation.LIVE;
      }

      MoveToAutoObstacleCorridorScenario(PfTestRunner.MoveToAutoObstacleCorridorScenario.Navigation nav) {
         this.nav = nav;
      }

      @Override
      public String name() {
         return "move_to_auto_obstacle_corridor";
      }

      @Override
      public JSONObject execute(PfTestRunner.Run run, UI ui) throws Exception {
         if (run.cancelled) {
            throw new PfTestRunner.Cancelled();
         } else {
            GameUI gui = ui == null ? null : ui.gui;
            boolean inGame = gui != null && gui.map != null;
            boolean playerPresent = false;
            boolean mapfileAvailable = false;
            boolean playerIdle = false;
            if (inGame) {
               synchronized (ui) {
                  Gob me = gui.map.player();
                  playerPresent = me != null && me.rc != null;
                  playerIdle = me == null || me.getattr(Moving.class) == null;
               }

               mapfileAvailable = gui.mapfile != null && gui.mapfile.file != null;
            }

            List<JSONObject> checks = preflightChecks(inGame, playerPresent, mapfileAvailable, playerIdle, Bot.hasCurrent());
            if (PfTestRunner.verdictOf(checks).equals("FAIL")) {
               String why = PfTestRunner.firstFailDetail(checks);
               return PfTestRunner.body(checks, "no movement performed: " + why, factsJson(null, null, null, "NOT_STARTED", why));
            } else if (run.cancelled) {
               throw new PfTestRunner.Cancelled();
            } else {
               PrototypePathfinder.Scene scene;
               synchronized (ui) {
                  scene = PrototypePathfinder.observe(gui);
               }

               NavigationTestSpotSelector.Selection sel = NavigationTestSpotSelector.localObstacleOrCorridor(scene);
               checks.add(PfTestRunner.NavigationTestSpotScenario.selectionCheck(sel));
               if (sel.refused()) {
                  return PfTestRunner.body(checks, "no movement performed: " + sel.refusal, factsJson(sel, null, null, "SELECTION_REFUSED", null));
               } else if (run.cancelled) {
                  throw new PfTestRunner.Cancelled();
               } else {
                  Coord2d target = sel.targetWorld;
                  PrototypePathfinder.Scene fresh;
                  synchronized (ui) {
                     fresh = PrototypePathfinder.observe(gui);
                  }

                  JSONObject reval = PfTestRunner.MoveToAutoOpenGroundScenario.revalidationCheck(fresh, target, 44.0);
                  checks.add(reval);
                  if ("fail".equals(reval.getString("status"))) {
                     return PfTestRunner.body(
                        checks, "no movement performed: " + reval.getString("detail"), factsJson(sel, null, null, "REVALIDATION_REFUSED", null)
                     );
                  } else if (run.cancelled) {
                     throw new PfTestRunner.Cancelled();
                  } else {
                     PrototypePathfinder.Plan plan = PrototypePathfinder.planAny(gui, Collections.singletonList(target), true);
                     JSONObject routeReval = routeRevalidationCheck(fresh, plan, target);
                     checks.add(routeReval);
                     if ("fail".equals(routeReval.getString("status"))) {
                        return PfTestRunner.body(
                           checks, "no movement performed: " + routeReval.getString("detail"), factsJson(sel, plan, null, "REVALIDATION_REFUSED", null)
                        );
                     } else if (run.cancelled) {
                        throw new PfTestRunner.Cancelled();
                     } else {
                        Bot bot = Bot.execute(new BotAction[0]);
                        AtomicBoolean timedOut = new AtomicBoolean(false);
                        PfTestRunner.MoveToAutoOpenGroundScenario.MoveResult mv = PfTestRunner.moveWatch(
                           run, timedOut, 120000L, () -> this.nav.run(gui, plan, target, bot, run)
                        );
                        if (run.cancelled) {
                           throw new PfTestRunner.Cancelled(cancelledBody(sel, plan, routeReval, mv));
                        } else {
                           return completedBody(sel, plan, mv, timedOut.get(), PfTestRunner.MoveToAutoOpenGroundScenario.finalArrival(gui, ui, mv, target));
                        }
                     }
                  }
               }
            }
         }
      }

      static PfTestRunner.MoveToAutoOpenGroundScenario.MoveResult liveMove(GameUI gui, PrototypePathfinder.Plan plan, Coord2d target, Bot bot) {
         return PfTestRunner.MoveToAutoOpenGroundScenario.walkPlan(gui, plan, target, bot, 60000L, 60000L);
      }

      static List<JSONObject> preflightChecks(boolean inGame, boolean playerPresent, boolean mapfileAvailable, boolean playerIdle, boolean botBusy) {
         return PfTestRunner.autoMovePreflightChecks("move_to_auto_obstacle_corridor", inGame, playerPresent, mapfileAvailable, playerIdle, botBusy);
      }

      static JSONObject routeRevalidationCheck(PrototypePathfinder.Scene fresh, PrototypePathfinder.Plan plan, Coord2d target) {
         if (fresh == null || fresh.player == null) {
            return PfTestRunner.check("route_revalidated", false, "player gob not observable at route revalidation (no-move)");
         } else if (target == null || plan == null) {
            return PfTestRunner.check("route_revalidated", false, "no live local plan to revalidate (no-move)");
         } else if (plan.status != PrototypePathfinder.Plan.Status.REACHED) {
            return PfTestRunner.check("route_revalidated", false, "live local plan " + plan.status + " to the selected target; refusing to move (no-move)");
         } else {
            Coord start = fresh.cellOf(fresh.player);
            Coord goal = fresh.cellOf(target);
            if (start != null && goal != null && NavigationTestSpotSelector.lineOccluded(fresh, start, goal)) {
               double direct = fresh.player.dist(target);
               double route = routeLength(plan.waypoints);
               if (plan.waypoints.size() < 3) {
                  return PfTestRunner.check(
                     "route_revalidated",
                     false,
                     "live local plan is trivial (" + plan.waypoints.size() + " waypoints); refusing to degrade to a plain open-ground route (no-move)"
                  );
               } else {
                  return route <= direct * 1.05
                     ? PfTestRunner.check(
                        "route_revalidated",
                        false,
                        String.format(
                           "live local plan is not a material detour (route %.2fu vs direct %.2fu); refusing to degrade to a plain open-ground route (no-move)",
                           route,
                           direct
                        )
                     )
                     : PfTestRunner.check(
                        "route_revalidated",
                        true,
                        String.format(
                           "direct line occluded; plan REACHED with %d waypoints, route %.2fu vs direct %.2fu (detour ratio %.2f)",
                           plan.waypoints.size(),
                           route,
                           direct,
                           route / direct
                        )
                     );
               }
            } else {
               return PfTestRunner.check(
                  "route_revalidated",
                  false,
                  "the direct line to the target is no longer occluded in the fresh observation; the fixture degraded to open ground (no-move)"
               );
            }
         }
      }

      static double routeLength(List<Coord2d> waypoints) {
         double len = 0.0;
         if (waypoints == null) {
            return 0.0;
         } else {
            for (int i = 1; i < waypoints.size(); i++) {
               Coord2d a = waypoints.get(i - 1);
               Coord2d b = waypoints.get(i);
               if (a != null && b != null) {
                  len += a.dist(b);
               }
            }

            return len;
         }
      }

      static JSONObject cancelledBody(
         NavigationTestSpotSelector.Selection sel,
         PrototypePathfinder.Plan plan,
         JSONObject routeReval,
         PfTestRunner.MoveToAutoOpenGroundScenario.MoveResult mv
      ) {
         List<JSONObject> checks = new ArrayList<>();
         if (routeReval != null) {
            checks.add(routeReval);
         }

         checks.add(PfTestRunner.MoveToAutoOpenGroundScenario.walkCheck(mv));
         checks.add(PfTestRunner.check("run_cancelled", false, "the run was cancelled (POST /pf/cancel); terminal movement facts above are partial"));
         return PfTestRunner.body(
            checks, "cancelled: " + (mv != null && mv.cancelledDetail != null ? mv.cancelledDetail : "run cancelled"), factsJson(sel, plan, mv, "WALKED", null)
         );
      }

      static JSONObject completedBody(
         NavigationTestSpotSelector.Selection sel,
         PrototypePathfinder.Plan plan,
         PfTestRunner.MoveToAutoOpenGroundScenario.MoveResult mv,
         boolean timedOut,
         JSONObject arrival
      ) {
         List<JSONObject> checks = new ArrayList<>();
         if (timedOut) {
            checks.add(PfTestRunner.check("move_timeout", false, "hard wall-clock deadline exceeded (120000ms); movement interrupted"));
         }

         checks.add(PfTestRunner.MoveToAutoOpenGroundScenario.walkCheck(mv));
         if (arrival != null) {
            checks.add(arrival);
         }

         String note = null;
         if (timedOut) {
            note = "hard wall-clock deadline exceeded (120000ms); movement interrupted";
         } else if (mv != null && mv.cancelledDetail != null) {
            note = "movement cancelled (detail: " + mv.cancelledDetail + ")";
         } else if (mv != null && mv.walk != WaypointWalker.Result.ARRIVED) {
            note = "movement did not verify arrival at the selected target";
         }

         boolean walked = mv != null && (mv.walk != null || mv.cancelledDetail != null);
         return PfTestRunner.body(checks, note, factsJson(sel, plan, mv, walked ? "WALKED" : "ROUTE_REFUSED", null));
      }

      static JSONObject factsJson(
         NavigationTestSpotSelector.Selection sel,
         PrototypePathfinder.Plan plan,
         PfTestRunner.MoveToAutoOpenGroundScenario.MoveResult mv,
         String status,
         String reason
      ) {
         JSONObject f = new JSONObject();
         f.put("moved", mv != null && (mv.walk != null || mv.cancelledDetail != null));
         f.put("arrived", mv != null && mv.walk == WaypointWalker.Result.ARRIVED);
         f.put("profile", NavigationTestSpotSelector.Profile.LOCAL_OBSTACLE_OR_CORRIDOR.name());
         f.put("status", status == null ? "NOT_STARTED" : status);
         if (sel != null) {
            f.put("selected", sel.selected());
            f.put("refusal", sel.refused() ? sel.refusal.name() : JSONObject.NULL);
            f.put("reason", sel.evidence);
            f.put("candidates", sel.candidates.size());
            if (!sel.candidates.isEmpty()) {
               NavigationTestSpotSelector.Candidate best = sel.candidates.get(0);
               f.put("best_score", PfTestRunner.round2(best.score));
               f.put("route_cells", best.route.size());
               f.put("route_expanded", best.routeExpanded);
            }

            if (sel.targetTile != null) {
               f.put("target_tile", new JSONArray().put(sel.targetTile.x).put(sel.targetTile.y));
            }

            if (sel.targetWorld != null) {
               f.put("target_world", new JSONArray().put(PfTestRunner.round2(sel.targetWorld.x)).put(PfTestRunner.round2(sel.targetWorld.y)));
            }
         } else {
            f.put("selected", false);
            f.put("refusal", "PREFLIGHT");
            f.put("reason", reason == null ? "preflight refused" : reason);
            f.put("candidates", 0);
         }

         if (plan != null) {
            f.put("plan_status", plan.status.name());
            f.put("expanded", plan.expanded);
            f.put("obstacles", plan.obstacles);
            f.put("plan_waypoints", plan.waypoints.size());
            double direct = sel != null && sel.targetWorld != null && !plan.waypoints.isEmpty() && plan.waypoints.get(0) != null
               ? plan.waypoints.get(0).dist(sel.targetWorld)
               : 0.0;
            f.put("route_dist", PfTestRunner.round2(routeLength(plan.waypoints)));
            f.put("direct_dist", PfTestRunner.round2(direct));
         }

         if (mv != null) {
            if (mv.cancelledDetail != null) {
               f.put("walk_outcome", "CANCELLED");
               f.put("detail", mv.cancelledDetail);
            } else if (mv.walk != null) {
               f.put("walk_outcome", mv.walk.name());
            }

            if (mv.endPos != null) {
               f.put("end_pos", new JSONArray().put(PfTestRunner.round2(mv.endPos.x)).put(PfTestRunner.round2(mv.endPos.y)));
            }

            f.put("elapsed_ms", mv.elapsedMs);
         }

         return f;
      }

      interface Navigation {
         PfTestRunner.MoveToAutoObstacleCorridorScenario.Navigation LIVE = (gui, plan, target, bot, run) -> PfTestRunner.MoveToAutoObstacleCorridorScenario.liveMove(
               gui, plan, target, bot
            );

         PfTestRunner.MoveToAutoOpenGroundScenario.MoveResult run(GameUI var1, PrototypePathfinder.Plan var2, Coord2d var3, Bot var4, PfTestRunner.Run var5) throws Exception;
      }
   }

   static final class MoveToAutoOpenGroundScenario implements PfTestRunner.Scenario {
      static final long MOVE_WAYPOINT_TIMEOUT_MS = 60000L;
      static final long MOVE_WALK_BUDGET_MS = 60000L;
      static final long MOVE_TOTAL_TIMEOUT_MS = 120000L;
      static final double MAX_TARGET_DIST = 44.0;
      static final double ARRIVAL_EPS = 3.0;
      private final PfTestRunner.MoveToAutoOpenGroundScenario.Navigation nav;

      MoveToAutoOpenGroundScenario() {
         this.nav = PfTestRunner.MoveToAutoOpenGroundScenario.Navigation.LIVE;
      }

      MoveToAutoOpenGroundScenario(PfTestRunner.MoveToAutoOpenGroundScenario.Navigation nav) {
         this.nav = nav;
      }

      @Override
      public String name() {
         return "move_to_auto_open_ground";
      }

      @Override
      public JSONObject execute(PfTestRunner.Run run, UI ui) throws Exception {
         if (run.cancelled) {
            throw new PfTestRunner.Cancelled();
         } else {
            GameUI gui = ui == null ? null : ui.gui;
            boolean inGame = gui != null && gui.map != null;
            boolean playerPresent = false;
            boolean mapfileAvailable = false;
            boolean playerIdle = false;
            if (inGame) {
               synchronized (ui) {
                  Gob me = gui.map.player();
                  playerPresent = me != null && me.rc != null;
                  playerIdle = me == null || me.getattr(Moving.class) == null;
               }

               mapfileAvailable = gui.mapfile != null && gui.mapfile.file != null;
            }

            List<JSONObject> checks = preflightChecks(inGame, playerPresent, mapfileAvailable, playerIdle, Bot.hasCurrent());
            if (PfTestRunner.verdictOf(checks).equals("FAIL")) {
               String why = PfTestRunner.firstFailDetail(checks);
               return PfTestRunner.body(checks, "no movement performed: " + why, factsJson(null, null, "NOT_STARTED", why));
            } else if (run.cancelled) {
               throw new PfTestRunner.Cancelled();
            } else {
               PrototypePathfinder.Scene scene;
               synchronized (ui) {
                  scene = PrototypePathfinder.observe(gui);
               }

               NavigationTestSpotSelector.Selection sel = NavigationTestSpotSelector.openGround(scene);
               checks.add(PfTestRunner.NavigationTestSpotScenario.selectionCheck(sel));
               if (sel.refused()) {
                  return PfTestRunner.body(checks, "no movement performed: " + sel.refusal, factsJson(sel, null, "SELECTION_REFUSED", null));
               } else if (run.cancelled) {
                  throw new PfTestRunner.Cancelled();
               } else {
                  Coord2d target = sel.targetWorld;
                  PrototypePathfinder.Scene fresh;
                  synchronized (ui) {
                     fresh = PrototypePathfinder.observe(gui);
                  }

                  JSONObject reval = revalidationCheck(fresh, target, 44.0);
                  checks.add(reval);
                  if ("fail".equals(reval.getString("status"))) {
                     return PfTestRunner.body(checks, "no movement performed: " + reval.getString("detail"), factsJson(sel, null, "REVALIDATION_REFUSED", null));
                  } else if (run.cancelled) {
                     throw new PfTestRunner.Cancelled();
                  } else {
                     Bot bot = Bot.execute(new BotAction[0]);
                     AtomicBoolean timedOut = new AtomicBoolean(false);
                     PfTestRunner.MoveToAutoOpenGroundScenario.MoveResult mv = PfTestRunner.moveWatch(
                        run, timedOut, 120000L, () -> this.nav.run(gui, target, bot, run)
                     );
                     if (run.cancelled) {
                        throw new PfTestRunner.Cancelled(cancelledBody(sel, mv));
                     } else {
                        return completedBody(sel, mv, timedOut.get(), finalArrival(gui, ui, mv, target));
                     }
                  }
               }
            }
         }
      }

      static PfTestRunner.MoveToAutoOpenGroundScenario.MoveResult liveMove(GameUI gui, Coord2d target, Bot bot) {
         PrototypePathfinder.Plan plan = PrototypePathfinder.planAny(gui, Collections.singletonList(target), true);
         return walkPlan(gui, plan, target, bot, 60000L, 60000L);
      }

      static PfTestRunner.MoveToAutoOpenGroundScenario.MoveResult walkPlan(
         GameUI gui, PrototypePathfinder.Plan plan, Coord2d fallbackPos, Bot bot, long waypointTimeoutMs, long walkBudgetMs
      ) {
         long t0 = System.currentTimeMillis();
         Coord2d before = PfTestRunner.observePos(gui);
         if (plan != null && plan.waypoints.size() >= 2) {
            WaypointWalker.Params params = new WaypointWalker.Params(2.475, 0.6875, 800L, waypointTimeoutMs, 3000L);

            try {
               WaypointWalker.Result r = WaypointWalker.execute(
                  WaypointWalker.liveEnv(gui), bot, plan.waypoints, 0, walkBudgetMs, params, NamedPlaceNavigator.NOOP
               );
               Coord2d after = PfTestRunner.observePos(gui);
               return new PfTestRunner.MoveToAutoOpenGroundScenario.MoveResult(
                  plan.status,
                  plan.expanded,
                  plan.obstacles,
                  r,
                  null,
                  after != null ? after : (before != null ? before : fallbackPos),
                  PfTestRunner.elapsed(t0)
               );
            } catch (InterruptedException var15) {
               Coord2d afterx = PfTestRunner.observePos(gui);
               String detail = var15.getMessage() != null && !var15.getMessage().isEmpty() ? var15.getMessage() : "bot cancelled";
               return new PfTestRunner.MoveToAutoOpenGroundScenario.MoveResult(
                  plan.status,
                  plan.expanded,
                  plan.obstacles,
                  null,
                  detail,
                  afterx != null ? afterx : (before != null ? before : fallbackPos),
                  PfTestRunner.elapsed(t0)
               );
            }
         } else {
            PrototypePathfinder.Plan.Status st = plan == null ? PrototypePathfinder.Plan.Status.FAILED : plan.status;
            int expanded = plan == null ? 0 : plan.expanded;
            int obstacles = plan == null ? 0 : plan.obstacles;
            return new PfTestRunner.MoveToAutoOpenGroundScenario.MoveResult(
               st, expanded, obstacles, null, null, before != null ? before : fallbackPos, PfTestRunner.elapsed(t0)
            );
         }
      }

      static List<JSONObject> preflightChecks(boolean inGame, boolean playerPresent, boolean mapfileAvailable, boolean playerIdle, boolean botBusy) {
         return PfTestRunner.autoMovePreflightChecks("move_to_auto_open_ground", inGame, playerPresent, mapfileAvailable, playerIdle, botBusy);
      }

      static JSONObject revalidationCheck(PrototypePathfinder.Scene fresh, Coord2d target, double maxDist) {
         if (fresh == null || fresh.player == null) {
            return PfTestRunner.check("target_revalidated", false, "player gob not observable at revalidation (no-move)");
         } else if (fresh.moving) {
            return PfTestRunner.check("target_revalidated", false, "player is moving at revalidation; refusing to move (no-move)");
         } else if (target == null) {
            return PfTestRunner.check("target_revalidated", false, "no selected target to revalidate (no-move)");
         } else {
            double d = fresh.player.dist(target);
            if (d > maxDist) {
               return PfTestRunner.check(
                  "target_revalidated", false, String.format("selected target %.2fu from the live player exceeds the hard bound %.2fu (no-move)", d, maxDist)
               );
            } else {
               return !fresh.bodyFree(target)
                  ? PfTestRunner.check("target_revalidated", false, "selected target is occupancy-blocked in the fresh observation (no-move)")
                  : PfTestRunner.check("target_revalidated", true, String.format("target body-free, %.2fu from the idle player (bound %.2fu)", d, maxDist));
            }
         }
      }

      static JSONObject routeCheck(PfTestRunner.MoveToAutoOpenGroundScenario.MoveResult mv) {
         if (mv == null) {
            return PfTestRunner.check("route_planned", false, "no movement attempt (no-move)");
         } else {
            switch (mv.planStatus) {
               case REACHED:
                  return PfTestRunner.check("route_planned", true, "plan REACHED (" + mv.expanded + " expanded, " + mv.obstacles + " obstacles)");
               case CLIPPED:
                  return PfTestRunner.check("route_planned", false, "plan CLIPPED: selected target beyond the local planning horizon (no-move)");
               case SNAPPED:
                  return PfTestRunner.check("route_planned", false, "plan SNAPPED: selected target became blocked at plan time (no-move)");
               case PARTIAL:
                  return PfTestRunner.check("route_planned", false, "plan PARTIAL: best-effort route only (no-move)");
               case FAILED:
                  return PfTestRunner.check("route_planned", false, "plan FAILED: no route to the selected target (no-move)");
               default:
                  throw new AssertionError(mv.planStatus);
            }
         }
      }

      static JSONObject walkCheck(PfTestRunner.MoveToAutoOpenGroundScenario.MoveResult mv) {
         if (mv == null) {
            return PfTestRunner.check("walk_completed", false, "no walk was executed (no-move)");
         } else if (mv.cancelledDetail != null) {
            return PfTestRunner.check("walk_completed", false, "walk cancelled: " + mv.cancelledDetail);
         } else if (mv.walk == null) {
            return PfTestRunner.check("walk_completed", false, "no walk was executed (no-move)");
         } else {
            switch (mv.walk) {
               case ARRIVED:
                  return PfTestRunner.check("walk_completed", true, "walker verified arrival at every waypoint (" + mv.elapsedMs + "ms)");
               case REJECTED:
                  return PfTestRunner.check("walk_completed", false, "walker REJECTED: click never accepted / vehicle state changed");
               case SHORT_STOP:
                  return PfTestRunner.check("walk_completed", false, "walker SHORT_STOP: stopped short of a waypoint");
               case TIMEOUT:
                  return PfTestRunner.check("walk_completed", false, "walker TIMEOUT: walk budget exhausted (60000ms)");
               default:
                  throw new AssertionError(mv.walk);
            }
         }
      }

      static JSONObject finalArrival(GameUI gui, UI ui, PfTestRunner.MoveToAutoOpenGroundScenario.MoveResult mv, Coord2d target) {
         boolean present = false;
         boolean idle = false;
         Coord2d at = null;
         if (gui != null && gui.map != null && ui != null) {
            synchronized (ui) {
               Gob me = gui.map.player();
               present = me != null && me.rc != null;
               idle = me == null || me.getattr(Moving.class) == null;
               at = present ? me.rc : null;
            }
         }

         boolean walkVerified = mv != null && mv.walk == WaypointWalker.Result.ARRIVED;
         return arrivalCheck(walkVerified, present, idle, at, target);
      }

      static JSONObject arrivalCheck(boolean walkVerified, boolean playerPresent, boolean playerIdle, Coord2d at, Coord2d target) {
         if (!walkVerified) {
            return PfTestRunner.check("arrived_idle_at_target", false, "the walk did not verify arrival at the selected target");
         } else if (!playerPresent) {
            return PfTestRunner.check("arrived_idle_at_target", false, "player gob not observable at the final check");
         } else if (!playerIdle) {
            return PfTestRunner.check("arrived_idle_at_target", false, "player is still moving after the walk (server-confirmed idle arrival required)");
         } else if (at != null && target != null) {
            double d = at.dist(target);
            return PfTestRunner.check(
               "arrived_idle_at_target", d <= 3.0, String.format("idle at (%.1f, %.1f), %.2f units from the selected target (eps %.2f)", at.x, at.y, d, 3.0)
            );
         } else {
            return PfTestRunner.check("arrived_idle_at_target", false, "final position or target position unavailable");
         }
      }

      static JSONObject cancelledBody(NavigationTestSpotSelector.Selection sel, PfTestRunner.MoveToAutoOpenGroundScenario.MoveResult mv) {
         List<JSONObject> checks = new ArrayList<>();
         checks.add(routeCheck(mv));
         checks.add(walkCheck(mv));
         checks.add(PfTestRunner.check("run_cancelled", false, "the run was cancelled (POST /pf/cancel); terminal movement facts above are partial"));
         return PfTestRunner.body(
            checks, "cancelled: " + (mv != null && mv.cancelledDetail != null ? mv.cancelledDetail : "run cancelled"), factsJson(sel, mv, "WALKED", null)
         );
      }

      static JSONObject completedBody(
         NavigationTestSpotSelector.Selection sel, PfTestRunner.MoveToAutoOpenGroundScenario.MoveResult mv, boolean timedOut, JSONObject arrival
      ) {
         List<JSONObject> checks = new ArrayList<>();
         if (timedOut) {
            checks.add(PfTestRunner.check("move_timeout", false, "hard wall-clock deadline exceeded (120000ms); movement interrupted"));
         }

         checks.add(routeCheck(mv));
         checks.add(walkCheck(mv));
         if (arrival != null) {
            checks.add(arrival);
         }

         String note = null;
         if (timedOut) {
            note = "hard wall-clock deadline exceeded (120000ms); movement interrupted";
         } else if (mv != null && mv.cancelledDetail != null) {
            note = "movement cancelled (detail: " + mv.cancelledDetail + ")";
         } else if (mv != null && mv.walk != WaypointWalker.Result.ARRIVED) {
            note = "movement did not verify arrival at the selected target";
         }

         boolean walked = mv != null && (mv.walk != null || mv.cancelledDetail != null);
         return PfTestRunner.body(checks, note, factsJson(sel, mv, walked ? "WALKED" : "ROUTE_REFUSED", null));
      }

      static JSONObject factsJson(
         NavigationTestSpotSelector.Selection sel, PfTestRunner.MoveToAutoOpenGroundScenario.MoveResult mv, String status, String reason
      ) {
         JSONObject f = new JSONObject();
         f.put("moved", mv != null && (mv.walk != null || mv.cancelledDetail != null));
         f.put("arrived", mv != null && mv.walk == WaypointWalker.Result.ARRIVED);
         f.put("profile", NavigationTestSpotSelector.Profile.OPEN_GROUND.name());
         f.put("status", status == null ? "NOT_STARTED" : status);
         if (sel != null) {
            f.put("selected", sel.selected());
            f.put("refusal", sel.refused() ? sel.refusal.name() : JSONObject.NULL);
            f.put("reason", sel.evidence);
            f.put("candidates", sel.candidates.size());
            if (!sel.candidates.isEmpty()) {
               f.put("best_score", PfTestRunner.round2(sel.candidates.get(0).score));
            }

            if (sel.targetTile != null) {
               f.put("target_tile", new JSONArray().put(sel.targetTile.x).put(sel.targetTile.y));
            }

            if (sel.targetWorld != null) {
               f.put("target_world", new JSONArray().put(PfTestRunner.round2(sel.targetWorld.x)).put(PfTestRunner.round2(sel.targetWorld.y)));
            }
         } else {
            f.put("selected", false);
            f.put("refusal", "PREFLIGHT");
            f.put("reason", reason == null ? "preflight refused" : reason);
            f.put("candidates", 0);
         }

         if (mv != null) {
            f.put("plan_status", mv.planStatus.name());
            f.put("expanded", mv.expanded);
            f.put("obstacles", mv.obstacles);
            if (mv.cancelledDetail != null) {
               f.put("walk_outcome", "CANCELLED");
               f.put("detail", mv.cancelledDetail);
            } else if (mv.walk != null) {
               f.put("walk_outcome", mv.walk.name());
            }

            if (mv.endPos != null) {
               f.put("end_pos", new JSONArray().put(PfTestRunner.round2(mv.endPos.x)).put(PfTestRunner.round2(mv.endPos.y)));
            }

            f.put("elapsed_ms", mv.elapsedMs);
         }

         return f;
      }

      static final class MoveResult {
         final PrototypePathfinder.Plan.Status planStatus;
         final int expanded;
         final int obstacles;
         final WaypointWalker.Result walk;
         final String cancelledDetail;
         final Coord2d endPos;
         final long elapsedMs;

         MoveResult(
            PrototypePathfinder.Plan.Status planStatus,
            int expanded,
            int obstacles,
            WaypointWalker.Result walk,
            String cancelledDetail,
            Coord2d endPos,
            long elapsedMs
         ) {
            this.planStatus = planStatus;
            this.expanded = expanded;
            this.obstacles = obstacles;
            this.walk = walk;
            this.cancelledDetail = cancelledDetail;
            this.endPos = endPos;
            this.elapsedMs = elapsedMs;
         }
      }

      interface Navigation {
         PfTestRunner.MoveToAutoOpenGroundScenario.Navigation LIVE = (gui, target, bot, run) -> PfTestRunner.MoveToAutoOpenGroundScenario.liveMove(
               gui, target, bot
            );

         PfTestRunner.MoveToAutoOpenGroundScenario.MoveResult run(GameUI var1, Coord2d var2, Bot var3, PfTestRunner.Run var4) throws Exception;
      }
   }

   static final class MoveToMarkerScenario implements PfTestRunner.Scenario {
      static final int MOVE_MAX_LEGS = 40;
      static final int MOVE_MAX_REPLANS = 5;
      static final long MOVE_WAYPOINT_TIMEOUT_MS = 90000L;
      static final long MOVE_LEG_BUDGET_MS = 120000L;
      static final long MOVE_TOTAL_TIMEOUT_MS = 240000L;
      static final int MOVE_BOUNDS_HALF = 256;
      static final double ARRIVAL_EPS = 3.0;
      static final RecedingHorizonNavigator.Bounds NAV_BOUNDS = new RecedingHorizonNavigator.Bounds(40, 5, 1000000);
      private final PfTestRunner.MoveToMarkerScenario.Navigation nav;

      MoveToMarkerScenario() {
         this.nav = PfTestRunner.MoveToMarkerScenario.Navigation.LIVE;
      }

      MoveToMarkerScenario(PfTestRunner.MoveToMarkerScenario.Navigation nav) {
         this.nav = nav;
      }

      @Override
      public String name() {
         return "move_to_marker";
      }

      @Override
      public JSONObject execute(PfTestRunner.Run run, UI ui) throws Exception {
         if (run.cancelled) {
            throw new PfTestRunner.Cancelled();
         } else {
            GameUI gui = ui == null ? null : ui.gui;
            String marker = markerName();
            boolean inGame = gui != null && gui.map != null;
            boolean playerPresent = false;
            boolean mapfileAvailable = false;
            boolean playerIdle = false;
            if (inGame) {
               synchronized (ui) {
                  Gob me = gui.map.player();
                  playerPresent = me != null && me.rc != null;
                  playerIdle = me == null || me.getattr(Moving.class) == null;
               }

               mapfileAvailable = gui.mapfile != null && gui.mapfile.file != null;
            }

            List<JSONObject> checks = preflightChecks(inGame, marker, playerPresent, mapfileAvailable, playerIdle, Bot.hasCurrent());
            if (PfTestRunner.verdictOf(checks).equals("FAIL")) {
               return PfTestRunner.body(checks, "no movement performed: " + PfTestRunner.firstFailDetail(checks), factsJson(null, marker, null, null, null));
            } else if (run.cancelled) {
               throw new PfTestRunner.Cancelled();
            } else {
               NamedPlaceNavigator.Location start = NamedPlaceNavigator.liveState(gui).current();
               if (start == null) {
                  checks.add(PfTestRunner.check("session_state", false, "session location unavailable (no map file/segment/player)"));
                  return PfTestRunner.body(checks, "no movement performed: session location unavailable", factsJson(null, marker, null, null, null));
               } else {
                  Area bounds = moveBounds(start.tile);
                  Bot bot = Bot.execute(new BotAction[0]);
                  AtomicBoolean timedOut = new AtomicBoolean(false);
                  NamedPlaceNavigator.Run navRun = PfTestRunner.moveWatch(run, timedOut, 240000L, () -> this.nav.run(gui, marker, bounds, bot, run));
                  Place place = navRun.place;
                  Long goalSeg = place == null ? null : place.seg;
                  Coord goalTile = place == null ? null : place.tc;
                  if (run.cancelled) {
                     throw new PfTestRunner.Cancelled(cancelledBody(navRun, marker, bounds, goalSeg, goalTile));
                  } else {
                     return completedBody(navRun, marker, bounds, goalSeg, goalTile, timedOut.get(), finalArrival(gui, ui, navRun, goalTile));
                  }
               }
            }
         }
      }

      static NamedPlaceNavigator.Run liveNavigation(GameUI gui, String marker, Area bounds, Bot bot) {
         WaypointWalker.Params params = new WaypointWalker.Params(2.475, 0.6875, 800L, 90000L, 3000L);
         NamedPlaceNavigator nav = NamedPlaceNavigator.live(gui, gui.mapfile.file, bot, NAV_BOUNDS, 120000L, params, NamedPlaceNavigator.NOOP);
         return nav.navigate(marker, bounds);
      }

      private static JSONObject finalArrival(GameUI gui, UI ui, NamedPlaceNavigator.Run navRun, Coord goalTile) {
         boolean present = false;
         boolean idle = false;
         Coord2d at = null;
         if (gui != null && gui.map != null && ui != null) {
            synchronized (ui) {
               Gob me = gui.map.player();
               present = me != null && me.rc != null;
               idle = me == null || me.getattr(Moving.class) == null;
               at = present ? me.rc : null;
            }
         }

         Coord2d markerWorld = null;
         if (navRun.start != null && goalTile != null) {
            markerWorld = NamedPlaceNavigator.worldPos(goalTile, NamedPlaceNavigator.translation(navRun.start));
         }

         return arrivalCheck(navRun.reached(), present, idle, at, markerWorld);
      }

      static String markerName() {
         String v = System.getProperty("haven.pf.move.marker");
         return v == null ? "" : v.trim();
      }

      static List<JSONObject> preflightChecks(
         boolean inGame, String marker, boolean playerPresent, boolean mapfileAvailable, boolean playerIdle, boolean botBusy
      ) {
         List<JSONObject> checks = new ArrayList<>();
         if (!inGame) {
            checks.add(PfTestRunner.check("in_game", false, "client is not in game (move_to_marker requires in-game state)"));
            return checks;
         } else {
            checks.add(PfTestRunner.check("in_game", true, "in game"));
            if (marker != null && !marker.trim().isEmpty()) {
               checks.add(PfTestRunner.check("marker_configured", true, "marker \"" + marker.trim() + "\" (from launch-time haven.pf.move.marker)"));
               if (!playerPresent) {
                  checks.add(PfTestRunner.check("player_present", false, "no player gob in game"));
                  return checks;
               } else {
                  checks.add(PfTestRunner.check("player_present", true, "player gob present"));
                  if (!mapfileAvailable) {
                     checks.add(PfTestRunner.check("mapfile_available", false, "no map file in game state (named places unavailable)"));
                     return checks;
                  } else {
                     checks.add(PfTestRunner.check("mapfile_available", true, "map file present"));
                     if (!playerIdle) {
                        checks.add(PfTestRunner.check("player_idle", false, "player is moving at scenario start; refusing to move (no-move)"));
                        return checks;
                     } else {
                        checks.add(PfTestRunner.check("player_idle", true, "player is idle"));
                        if (botBusy) {
                           checks.add(PfTestRunner.check("bot_available", false, "another auto.Bot is currently running; refusing to navigate (no-move)"));
                           return checks;
                        } else {
                           checks.add(PfTestRunner.check("bot_available", true, "no current auto.Bot"));
                           return checks;
                        }
                     }
                  }
               }
            } else {
               checks.add(
                  PfTestRunner.check(
                     "marker_configured", false, "system property haven.pf.move.marker is unset or blank (set it at launch, e.g. -Dhaven.pf.move.marker=Home)"
                  )
               );
               return checks;
            }
         }
      }

      static Area moveBounds(Coord startTile) {
         if (startTile == null) {
            throw new IllegalArgumentException("startTile must not be null");
         } else {
            return Area.corn(startTile.sub(256, 256), startTile.add(256, 256));
         }
      }

      static List<JSONObject> outcomeChecks(NamedPlaceNavigator.Run navRun, String marker, boolean timedOut) {
         List<JSONObject> checks = new ArrayList<>();
         switch (navRun.status) {
            case UNAVAILABLE:
               checks.add(PfTestRunner.check("session_state", false, "session location unavailable (no map file/segment/player)"));
               return checks;
            case ROUTE_REJECTED:
               checks.add(PfTestRunner.check("marker_resolved", false, routeRefusal(navRun, marker)));
               return checks;
            case NAVIGATED:
               if (timedOut) {
                  checks.add(PfTestRunner.check("move_timeout", false, "hard wall-clock deadline exceeded (240000ms); navigation interrupted"));
               }

               switch (navRun.navOutcome) {
                  case REACHED_DESTINATION:
                     checks.add(
                        PfTestRunner.check(
                           "navigation_completed", true, "verified arrival at the marker (legs=" + navRun.legs + ", replans=" + navRun.replans + ")"
                        )
                     );
                     break;
                  case CANCELLED:
                     checks.add(PfTestRunner.check("navigation_completed", false, "cancelled: " + (navRun.detail == null ? "bot cancelled" : navRun.detail)));
                     break;
                  case TERMINAL_FAILURE:
                     checks.add(PfTestRunner.check("navigation_completed", false, "walker failure: " + (navRun.detail == null ? "unknown" : navRun.detail)));
                     break;
                  case LEG_LIMIT_EXHAUSTED:
                     checks.add(PfTestRunner.check("navigation_completed", false, "leg limit exhausted (40 legs)"));
                     break;
                  case REPLAN_LIMIT_EXHAUSTED:
                     checks.add(PfTestRunner.check("navigation_completed", false, "replan limit exhausted (5 replans)"));
                     break;
                  case COARSE_PLAN_FAILED:
                     checks.add(PfTestRunner.check("navigation_completed", false, "coarse plan failed: " + (navRun.detail == null ? "unknown" : navRun.detail)));
                     break;
                  case COARSE_PLAN_INVALID:
                     checks.add(
                        PfTestRunner.check("navigation_completed", false, "coarse plan invalid: " + (navRun.detail == null ? "unknown" : navRun.detail))
                     );
                     break;
                  default:
                     throw new AssertionError(navRun.navOutcome);
               }

               return checks;
            default:
               throw new AssertionError(navRun.status);
         }
      }

      private static String routeRefusal(NamedPlaceNavigator.Run navRun, String marker) {
         if (navRun.routeKind == null) {
            return "route refused (unknown kind)";
         } else {
            switch (navRun.routeKind) {
               case DEST_MISSING:
                  return "unknown marker \"" + marker + "\" (DEST_MISSING)";
               case DEST_AMBIGUOUS:
                  return "duplicate marker \"" + marker + "\" (DEST_AMBIGUOUS: more than one marker matches; never guessed)";
               case CROSS_SEGMENT:
                  return "marker \"" + marker + "\" is in another segment (CROSS_SEGMENT)";
               case NO_KNOWN_ROUTE:
                  return "no known route to marker \"" + marker + "\" (NO_KNOWN_ROUTE)";
               case INVALID_START:
                  return "start tile outside the planning bounds or unknown/blocked (INVALID_START)";
               case INVALID_GOAL:
                  return "marker tile outside the planning bounds or unknown/blocked (INVALID_GOAL)";
               case EXHAUSTED:
                  return "route search exhausted its expansion budget (EXHAUSTED)";
               default:
                  return "route refused (" + navRun.routeKind + ")";
            }
         }
      }

      static JSONObject arrivalCheck(boolean navigationVerified, boolean playerPresent, boolean playerIdle, Coord2d at, Coord2d markerWorld) {
         if (!navigationVerified) {
            return PfTestRunner.check("arrived_idle_at_marker", false, "navigation did not verify arrival at the marker");
         } else if (!playerPresent) {
            return PfTestRunner.check("arrived_idle_at_marker", false, "player gob not observable at the final check");
         } else if (!playerIdle) {
            return PfTestRunner.check("arrived_idle_at_marker", false, "player is still moving after navigation (server-confirmed idle arrival required)");
         } else if (at != null && markerWorld != null) {
            double d = at.dist(markerWorld);
            return PfTestRunner.check(
               "arrived_idle_at_marker", d <= 3.0, String.format("idle at (%.1f, %.1f), %.2f units from marker tile center (eps %.2f)", at.x, at.y, d, 3.0)
            );
         } else {
            return PfTestRunner.check("arrived_idle_at_marker", false, "final position or marker position unavailable");
         }
      }

      static JSONObject cancelledBody(NamedPlaceNavigator.Run navRun, String marker, Area bounds, Long goalSeg, Coord goalTile) {
         List<JSONObject> checks = outcomeChecks(navRun, marker, false);
         checks.add(PfTestRunner.check("run_cancelled", false, "the run was cancelled (POST /pf/cancel); terminal navigation facts above are partial"));
         return PfTestRunner.body(
            checks, "cancelled: " + (navRun.detail == null ? "run cancelled" : navRun.detail), factsJson(navRun, marker, bounds, goalSeg, goalTile)
         );
      }

      static JSONObject completedBody(
         NamedPlaceNavigator.Run navRun, String marker, Area bounds, Long goalSeg, Coord goalTile, boolean timedOut, JSONObject arrival
      ) {
         List<JSONObject> checks = outcomeChecks(navRun, marker, timedOut);
         if (arrival != null) {
            checks.add(arrival);
         }

         String note = null;
         if (timedOut) {
            note = "hard wall-clock deadline exceeded (240000ms); navigation interrupted";
         } else if (navRun.cancelled()) {
            note = "navigation cancelled (detail: " + navRun.detail + ")";
         } else if (!navRun.reached()) {
            note = "navigation did not verify arrival at the marker";
         }

         return PfTestRunner.body(checks, note, factsJson(navRun, marker, bounds, goalSeg, goalTile));
      }

      static JSONObject factsJson(NamedPlaceNavigator.Run navRun, String marker, Area bounds, Long goalSeg, Coord goalTile) {
         JSONObject f = new JSONObject();
         f.put("moved", navRun != null && navRun.status == NamedPlaceNavigator.RunStatus.NAVIGATED);
         f.put("arrived", navRun != null && navRun.reached());
         if (marker != null) {
            f.put("marker", marker);
         }

         if (bounds != null) {
            JSONObject b = new JSONObject();
            b.put("ul", new JSONArray().put(bounds.ul.x).put(bounds.ul.y));
            b.put("br", new JSONArray().put(bounds.br.x).put(bounds.br.y));
            f.put("bounds", b);
         }

         if (navRun == null) {
            f.put("status", "UNAVAILABLE");
            f.put("note", "navigation not started (preflight refused)");
            return f;
         } else {
            f.put("status", navRun.status.toString());
            if (navRun.routeKind != null) {
               f.put("route_kind", navRun.routeKind.toString());
            }

            f.put("markerseq", navRun.markerseq);
            if (navRun.start != null) {
               f.put("segment", String.format("%x", navRun.start.seg));
               f.put("start_tile", new JSONArray().put(navRun.start.tile.x).put(navRun.start.tile.y));
               f.put("start_world", new JSONArray().put(PfTestRunner.round2(navRun.start.world.x)).put(PfTestRunner.round2(navRun.start.world.y)));
            }

            if (goalSeg != null) {
               f.put("goal_segment", String.format("%x", goalSeg));
            }

            if (goalTile != null) {
               f.put("goal_tile", new JSONArray().put(goalTile.x).put(goalTile.y));
            }

            if (navRun.routeKind == NamedPlaceRouteService.Kind.REACHED) {
               f.put("waypoint_count", navRun.waypointCount);
            }

            f.put("expanded", navRun.expanded);
            if (navRun.navOutcome != null) {
               f.put("nav_outcome", navRun.navOutcome.toString());
               f.put("legs", navRun.legs);
               f.put("replans", navRun.replans);
               f.put("route_index", navRun.routeIndex);
               if (navRun.detail != null) {
                  f.put("detail", navRun.detail);
               }

               if (navRun.endPos != null) {
                  f.put("end_pos", new JSONArray().put(PfTestRunner.round2(navRun.endPos.x)).put(PfTestRunner.round2(navRun.endPos.y)));
               }
            }

            f.put("elapsed_ms", navRun.elapsedMs);
            JSONArray replans = new JSONArray();

            for (NamedPlaceNavigator.ReplanFact r : navRun.replanFacts) {
               JSONObject rf = new JSONObject();
               rf.put("index", r.index);
               rf.put("kind", r.kind.toString());
               rf.put("markerseq", r.markerseq);
               rf.put("expanded", r.expanded);
               replans.put(rf);
            }

            f.put("replan_facts", replans);
            return f;
         }
      }

      interface Navigation {
         PfTestRunner.MoveToMarkerScenario.Navigation LIVE = (gui, marker, bounds, bot, run) -> PfTestRunner.MoveToMarkerScenario.liveNavigation(
               gui, marker, bounds, bot
            );

         NamedPlaceNavigator.Run run(GameUI var1, String var2, Area var3, Bot var4, PfTestRunner.Run var5) throws Exception;
      }
   }

   static final class NavigationTestSpotScenario implements PfTestRunner.Scenario {
      static final int SELECT_BOUNDS_HALF = 32;
      static final double SELECT_MIN_TILES = 12.0;
      static final double SELECT_MAX_TILES = 24.0;
      static final int SELECT_MAX_EXPANDED = 40000;
      static final int SELECT_MAX_TOTAL_EXPANDED = 2000000;
      private final NavigationTestSpotSelector.Profile profile;

      NavigationTestSpotScenario(NavigationTestSpotSelector.Profile profile) {
         this.profile = profile;
      }

      @Override
      public String name() {
         switch (this.profile) {
            case OPEN_GROUND:
               return "select_open_ground";
            case LOCAL_OBSTACLE_OR_CORRIDOR:
               return "select_obstacle_corridor";
            case KNOWN_MAP_LONG_LEG:
               return "select_known_long_leg";
            default:
               throw new AssertionError(this.profile);
         }
      }

      @Override
      public JSONObject execute(PfTestRunner.Run run, UI ui) throws Exception {
         if (run.cancelled) {
            throw new PfTestRunner.Cancelled();
         } else {
            List<JSONObject> checks = new ArrayList<>();
            GameUI gui = ui == null ? null : ui.gui;
            if (gui != null && gui.map != null) {
               checks.add(PfTestRunner.check("in_game", true, "in game"));
               if (run.cancelled) {
                  throw new PfTestRunner.Cancelled();
               } else {
                  return this.profile == NavigationTestSpotSelector.Profile.KNOWN_MAP_LONG_LEG
                     ? this.executeLongLeg(run, ui, gui, checks)
                     : this.executeLocal(run, ui, gui, checks);
               }
            } else {
               checks.add(PfTestRunner.check("in_game", false, "client is not in game (" + this.name() + " requires in-game state)"));
               return body(
                  checks, refusalFacts(this.profile, "NO_GAME", "client is not in game; selection not attempted"), "not in game: selection not attempted"
               );
            }
         }
      }

      private JSONObject executeLocal(PfTestRunner.Run run, UI ui, GameUI gui, List<JSONObject> checks) throws Exception {
         PrototypePathfinder.Scene scene;
         synchronized (ui) {
            scene = PrototypePathfinder.observe(gui);
         }

         NavigationTestSpotSelector.Selection sel = this.profile == NavigationTestSpotSelector.Profile.OPEN_GROUND
            ? NavigationTestSpotSelector.openGround(scene)
            : NavigationTestSpotSelector.localObstacleOrCorridor(scene);
         if (run.cancelled) {
            throw new PfTestRunner.Cancelled();
         } else {
            checks.add(selectionCheck(sel));
            return body(checks, factsJson(this.profile, sel, reportTile(sel, null), null), sel.refused() ? "no spot selected: " + sel.refusal : null);
         }
      }

      private JSONObject executeLongLeg(PfTestRunner.Run run, UI ui, GameUI gui, List<JSONObject> checks) throws Exception {
         MapFile file;
         synchronized (ui) {
            file = gui.mapfile == null ? null : gui.mapfile.file;
         }

         checks.add(
            PfTestRunner.check(
               "mapfile_available",
               file != null,
               file != null ? "map file present (persisted coarse tiles)" : "no map file in game state (select_known_long_leg needs persisted coarse tiles)"
            )
         );
         if (file == null) {
            return body(
               checks, refusalFacts(this.profile, "NO_SOURCE", "no map file in game state; selection not attempted"), "no map file: selection not attempted"
            );
         } else {
            NamedPlaceNavigator.Location loc;
            synchronized (ui) {
               loc = NamedPlaceNavigator.liveState(gui).current();
            }

            if (loc == null) {
               checks.add(PfTestRunner.check("session_state", false, "session location unavailable (no map file view/segment/player)"));
               return body(
                  checks,
                  refusalFacts(this.profile, "NO_SOURCE", "session location unavailable; selection not attempted"),
                  "session location unavailable: selection not attempted"
               );
            } else {
               checks.add(PfTestRunner.check("session_state", true, String.format("segment %x, start tile %s", loc.seg, loc.tile)));
               if (run.cancelled) {
                  throw new PfTestRunner.Cancelled();
               } else {
                  Area bounds = selectBounds(loc.tile);
                  MapFileTileSource src = MapFileTileSource.of(file, loc.seg, bounds);
                  NavigationTestSpotSelector.Selection sel = NavigationTestSpotSelector.knownMapLongLeg(
                     src, loc.seg, loc.tile.sub(bounds.ul), 12.0, 24.0, 40000, 2000000
                  );
                  if (run.cancelled) {
                     throw new PfTestRunner.Cancelled();
                  } else {
                     checks.add(selectionCheck(sel));
                     return body(
                        checks,
                        factsJson(this.profile, sel, reportTile(sel, bounds), sel.selected() ? loc.seg : null),
                        sel.refused() ? "no spot selected: " + sel.refusal : null
                     );
                  }
               }
            }
         }
      }

      static JSONObject selectionCheck(NavigationTestSpotSelector.Selection sel) {
         return sel == null
            ? PfTestRunner.check("selection_completed", false, "selection not attempted")
            : PfTestRunner.check("selection_completed", sel.selected(), sel.evidence);
      }

      static Area selectBounds(Coord startTile) {
         if (startTile == null) {
            throw new IllegalArgumentException("startTile must not be null");
         } else {
            return Area.corn(startTile.sub(32, 32), startTile.add(32, 32));
         }
      }

      static Coord reportTile(NavigationTestSpotSelector.Selection sel, Area bounds) {
         if (sel != null && sel.targetTile != null) {
            return bounds == null ? sel.targetTile : bounds.ul.add(sel.targetTile);
         } else {
            return null;
         }
      }

      static JSONObject refusalFacts(NavigationTestSpotSelector.Profile profile, String refusal, String reason) {
         JSONObject f = new JSONObject();
         f.put("profile", profile.name());
         f.put("selected", false);
         f.put("refusal", refusal);
         f.put("reason", reason);
         f.put("candidates", 0);
         return f;
      }

      static JSONObject factsJson(NavigationTestSpotSelector.Profile profile, NavigationTestSpotSelector.Selection sel, Coord reportTile, Long segment) {
         if (sel == null) {
            return refusalFacts(profile, "NO_GAME", "selection not attempted (no in-game state)");
         } else {
            JSONObject f = new JSONObject();
            f.put("profile", profile.name());
            if (sel.refused()) {
               f.put("selected", false);
               f.put("refusal", sel.refusal.name());
               f.put("reason", sel.evidence);
               f.put("candidates", sel.candidates.size());
               return f;
            } else {
               f.put("selected", true);
               f.put("refusal", JSONObject.NULL);
               f.put("reason", sel.evidence);
               f.put("candidates", sel.candidates.size());
               if (!sel.candidates.isEmpty()) {
                  f.put("best_score", PfTestRunner.round2(sel.candidates.get(0).score));
               }

               if (reportTile != null) {
                  f.put("target_tile", new JSONArray().put(reportTile.x).put(reportTile.y));
               }

               if (sel.targetWorld != null) {
                  f.put("target_world", new JSONArray().put(PfTestRunner.round2(sel.targetWorld.x)).put(PfTestRunner.round2(sel.targetWorld.y)));
               }

               if (segment != null) {
                  f.put("segment", String.format("%x", segment));
               }

               return f;
            }
         }
      }

      private static JSONObject body(List<JSONObject> checks, JSONObject facts, String note) {
         JSONObject o = new JSONObject();
         o.put("verdict", PfTestRunner.verdictOf(checks));
         JSONArray arr = new JSONArray();

         for (JSONObject c : checks) {
            arr.put(c);
         }

         o.put("checks", arr);
         if (facts != null) {
            o.put("facts", facts);
         }

         if (note != null && !note.isEmpty()) {
            o.put("note", note);
         }

         return o;
      }
   }

   static final class ObserveScenario implements PfTestRunner.Scenario {
      @Override
      public String name() {
         return "observe";
      }

      @Override
      public JSONObject execute(PfTestRunner.Run run, UI ui) throws Exception {
         List<JSONObject> checks = new ArrayList<>();
         GameUI gui = ui == null ? null : ui.gui;
         if (gui != null && gui.map != null) {
            long deadline = System.currentTimeMillis() + 3000L;
            boolean idle = false;

            while (true) {
               label107: {
                  if (System.currentTimeMillis() < deadline) {
                     if (run.cancelled) {
                        throw new PfTestRunner.Cancelled();
                     }

                     synchronized (ui) {
                        Gob me = gui.map.player();
                        if (me != null && me.getattr(Moving.class) != null) {
                           break label107;
                        }

                        idle = true;
                     }
                  }

                  if (run.cancelled) {
                     throw new PfTestRunner.Cancelled();
                  }

                  PrototypePathfinder.Scene scene;
                  synchronized (ui) {
                     scene = PrototypePathfinder.observe(gui);
                  }

                  checks.add(
                     PfTestRunner.check(
                        "player_present", scene.player != null, scene.player == null ? "no player gob in game" : "player at " + PfTestRunner.pt(scene.player)
                     )
                  );
                  checks.add(
                     PfTestRunner.check(
                        "occupancy_present",
                        scene.occupancy != null,
                        scene.occupancy == null
                           ? "occupancy build returned null"
                           : scene.occupancy.w + "x" + scene.occupancy.h + " grid, " + scene.obstacles + " obstacles"
                     )
                  );
                  if (scene.player == null) {
                     checks.add(PfTestRunner.skip("idle_player_not_solid", "no player to evaluate"));
                  } else if (!idle) {
                     checks.add(PfTestRunner.skip("idle_player_not_solid", "player still moving after 3000ms; idle invariant not evaluated"));
                  } else {
                     checks.add(
                        PfTestRunner.check(
                           "idle_player_not_solid",
                           !scene.playerInSolid,
                           scene.playerInSolid ? "idle player cell is occupancy-SOLID (LEGAL POS MARKED SOLID)" : "idle player cell is free"
                        )
                     );
                  }

                  return body(checks, scene, idle ? null : "idle invariant skipped: player was moving at capture");
               }

               Thread.sleep(100L);
            }
         } else {
            checks.add(PfTestRunner.check("in_game", false, "client is not in game (observe requires in-game state)"));
            return body(checks, null, null);
         }
      }

      private static JSONObject body(List<JSONObject> checks, PrototypePathfinder.Scene scene, String note) {
         JSONObject o = new JSONObject();
         o.put("verdict", PfTestRunner.verdictOf(checks));
         JSONArray arr = new JSONArray();

         for (JSONObject c : checks) {
            arr.put(c);
         }

         o.put("checks", arr);
         if (scene != null) {
            o.put("scene", PfTestRunner.sceneJson(scene));
         }

         if (note != null) {
            o.put("note", note);
         }

         return o;
      }
   }

   public static final class Run {
      public final String id;
      public final String scenario;
      public final long startedMs;
      volatile boolean cancelled;

      Run(String scenario) {
         this.id = String.format("%s-%d-%d", scenario, System.currentTimeMillis(), PfTestRunner.seq.incrementAndGet());
         this.scenario = scenario;
         this.startedMs = System.currentTimeMillis();
      }
   }

   public interface Scenario {
      String name();

      JSONObject execute(PfTestRunner.Run var1, UI var2) throws Exception;
   }

   static final class SelectBoulderApproachScenario implements PfTestRunner.Scenario {
      @Override
      public String name() {
         return "select_boulder_approach";
      }

      @Override
      public JSONObject execute(PfTestRunner.Run run, UI ui) throws Exception {
         if (run.cancelled) {
            throw new PfTestRunner.Cancelled();
         } else {
            List<JSONObject> checks = new ArrayList<>();
            GameUI gui = ui == null ? null : ui.gui;
            if (gui != null && gui.map != null) {
               checks.add(PfTestRunner.check("in_game", true, "in game"));
               if (run.cancelled) {
                  throw new PfTestRunner.Cancelled();
               } else {
                  PrototypePathfinder.Scene scene;
                  synchronized (ui) {
                     scene = PrototypePathfinder.observe(gui);
                  }

                  TransitionApproachSelector.Selection sel = TransitionApproachSelector.boulderApproach(scene);
                  if (run.cancelled) {
                     throw new PfTestRunner.Cancelled();
                  } else {
                     checks.add(selectionCheck(sel));
                     return body(checks, factsJson(sel), sel.refused() ? "no approach selected: " + sel.refusal : null);
                  }
               }
            } else {
               checks.add(PfTestRunner.check("in_game", false, "client is not in game (select_boulder_approach requires in-game state)"));
               return body(checks, refusalFacts("NO_GAME", "client is not in game; selection not attempted"), "not in game: selection not attempted");
            }
         }
      }

      static JSONObject selectionCheck(TransitionApproachSelector.Selection sel) {
         return sel == null
            ? PfTestRunner.check("selection_completed", false, "selection not attempted")
            : PfTestRunner.check("selection_completed", sel.selected(), sel.evidence);
      }

      static JSONObject refusalFacts(String refusal, String reason) {
         JSONObject f = new JSONObject();
         f.put("profile", TransitionApproachSelector.TransitionProfile.BOULDER.name());
         f.put("selected", false);
         f.put("refusal", refusal);
         f.put("reason", reason);
         f.put("fixtures", 0);
         f.put("candidates", 0);
         return f;
      }

      static JSONObject factsJson(TransitionApproachSelector.Selection sel) {
         if (sel == null) {
            return refusalFacts("NO_GAME", "selection not attempted (no in-game state)");
         } else {
            JSONObject f = new JSONObject();
            f.put("profile", sel.profile.name());
            f.put("selected", sel.selected());
            if (sel.refused()) {
               f.put("refusal", sel.refusal.name());
               f.put("reason", sel.evidence);
               f.put("fixtures", sel.fixtureCount);
               f.put("candidates", sel.candidates.size());
               return f;
            } else {
               f.put("refusal", JSONObject.NULL);
               f.put("reason", sel.evidence);
               f.put("fixtures", sel.fixtureCount);
               f.put("candidates", sel.candidates.size());
               if (!sel.candidates.isEmpty()) {
                  f.put("best_score", PfTestRunner.round2(sel.candidates.get(0).score));
               }

               f.put("approach_tile", new JSONArray().put(sel.approachTile.x).put(sel.approachTile.y));
               f.put("approach_world", new JSONArray().put(PfTestRunner.round2(sel.approachWorld.x)).put(PfTestRunner.round2(sel.approachWorld.y)));
               f.put("approach_side", sel.side);
               f.put("standoff", PfTestRunner.round2(sel.standoff));
               f.put("footprint_tiles", PfTestRunner.round2(sel.footprintTiles));
               f.put("player_dist", PfTestRunner.round2(sel.playerDist));
               if (!sel.candidates.isEmpty()) {
                  f.put("route_cells", sel.candidates.get(0).route.size());
                  f.put("route_expanded", sel.candidates.get(0).routeExpanded);
               }

               return f;
            }
         }
      }

      private static JSONObject body(List<JSONObject> checks, JSONObject facts, String note) {
         JSONObject o = new JSONObject();
         o.put("verdict", PfTestRunner.verdictOf(checks));
         JSONArray arr = new JSONArray();

         for (JSONObject c : checks) {
            arr.put(c);
         }

         o.put("checks", arr);
         if (facts != null) {
            o.put("facts", facts);
         }

         if (note != null && !note.isEmpty()) {
            o.put("note", note);
         }

         return o;
      }
   }

   static final class SelectCaveTransitionApproachScenario implements PfTestRunner.Scenario {
      @Override
      public String name() {
         return "select_cave_transition_approach";
      }

      @Override
      public JSONObject execute(PfTestRunner.Run run, UI ui) throws Exception {
         if (run.cancelled) {
            throw new PfTestRunner.Cancelled();
         } else {
            List<JSONObject> checks = new ArrayList<>();
            GameUI gui = ui == null ? null : ui.gui;
            if (gui != null && gui.map != null) {
               checks.add(PfTestRunner.check("in_game", true, "in game"));
               if (run.cancelled) {
                  throw new PfTestRunner.Cancelled();
               } else {
                  PrototypePathfinder.Scene scene;
                  synchronized (ui) {
                     scene = PrototypePathfinder.observe(gui);
                  }

                  TransitionApproachSelector.Selection sel = TransitionApproachSelector.caveTransitionApproach(scene);
                  if (run.cancelled) {
                     throw new PfTestRunner.Cancelled();
                  } else {
                     checks.add(selectionCheck(sel));
                     return body(checks, factsJson(sel), sel.refused() ? "no approach selected: " + sel.refusal : null);
                  }
               }
            } else {
               checks.add(PfTestRunner.check("in_game", false, "client is not in game (select_cave_transition_approach requires in-game state)"));
               return body(checks, refusalFacts("NO_GAME", "client is not in game; selection not attempted"), "not in game: selection not attempted");
            }
         }
      }

      static JSONObject selectionCheck(TransitionApproachSelector.Selection sel) {
         return sel == null
            ? PfTestRunner.check("selection_completed", false, "selection not attempted")
            : PfTestRunner.check("selection_completed", sel.selected(), sel.evidence);
      }

      static JSONObject refusalFacts(String refusal, String reason) {
         JSONObject f = new JSONObject();
         f.put("profile", TransitionApproachSelector.TransitionProfile.CAVE_TRANSITION.name());
         f.put("selected", false);
         f.put("refusal", refusal);
         f.put("reason", reason);
         f.put("fixtures", 0);
         f.put("candidates", 0);
         return f;
      }

      static JSONObject factsJson(TransitionApproachSelector.Selection sel) {
         if (sel == null) {
            return refusalFacts("NO_GAME", "selection not attempted (no in-game state)");
         } else {
            JSONObject f = new JSONObject();
            f.put("profile", sel.profile.name());
            f.put("selected", sel.selected());
            if (sel.refused()) {
               f.put("refusal", sel.refusal.name());
               f.put("reason", sel.evidence);
               f.put("fixtures", sel.fixtureCount);
               f.put("candidates", sel.candidates.size());
               return f;
            } else {
               f.put("refusal", JSONObject.NULL);
               f.put("reason", sel.evidence);
               f.put("fixtures", sel.fixtureCount);
               f.put("candidates", sel.candidates.size());
               f.put("transition_kind", sel.kind == null ? JSONObject.NULL : sel.kind.name());
               if (!sel.candidates.isEmpty()) {
                  f.put("best_score", PfTestRunner.round2(sel.candidates.get(0).score));
               }

               f.put("approach_tile", new JSONArray().put(sel.approachTile.x).put(sel.approachTile.y));
               f.put("approach_world", new JSONArray().put(PfTestRunner.round2(sel.approachWorld.x)).put(PfTestRunner.round2(sel.approachWorld.y)));
               f.put("approach_side", sel.side);
               f.put("standoff", PfTestRunner.round2(sel.standoff));
               f.put("footprint_tiles", PfTestRunner.round2(sel.footprintTiles));
               f.put("player_dist", PfTestRunner.round2(sel.playerDist));
               if (!sel.candidates.isEmpty()) {
                  f.put("route_cells", sel.candidates.get(0).route.size());
                  f.put("route_expanded", sel.candidates.get(0).routeExpanded);
               }

               return f;
            }
         }
      }

      private static JSONObject body(List<JSONObject> checks, JSONObject facts, String note) {
         JSONObject o = new JSONObject();
         o.put("verdict", PfTestRunner.verdictOf(checks));
         JSONArray arr = new JSONArray();

         for (JSONObject c : checks) {
            arr.put(c);
         }

         o.put("checks", arr);
         if (facts != null) {
            o.put("facts", facts);
         }

         if (note != null && !note.isEmpty()) {
            o.put("note", note);
         }

         return o;
      }
   }

   static final class SelectDoorGateApproachScenario implements PfTestRunner.Scenario {
      @Override
      public String name() {
         return "select_door_gate_approach";
      }

      @Override
      public JSONObject execute(PfTestRunner.Run run, UI ui) throws Exception {
         if (run.cancelled) {
            throw new PfTestRunner.Cancelled();
         } else {
            List<JSONObject> checks = new ArrayList<>();
            GameUI gui = ui == null ? null : ui.gui;
            if (gui != null && gui.map != null) {
               checks.add(PfTestRunner.check("in_game", true, "in game"));
               if (run.cancelled) {
                  throw new PfTestRunner.Cancelled();
               } else {
                  PrototypePathfinder.Scene scene;
                  synchronized (ui) {
                     scene = PrototypePathfinder.observe(gui);
                  }

                  TransitionApproachSelector.Selection sel = TransitionApproachSelector.doorGateApproach(scene);
                  if (run.cancelled) {
                     throw new PfTestRunner.Cancelled();
                  } else {
                     checks.add(selectionCheck(sel));
                     return body(checks, factsJson(sel), sel.refused() ? "no approach selected: " + sel.refusal : null);
                  }
               }
            } else {
               checks.add(PfTestRunner.check("in_game", false, "client is not in game (select_door_gate_approach requires in-game state)"));
               return body(checks, refusalFacts("NO_GAME", "client is not in game; selection not attempted"), "not in game: selection not attempted");
            }
         }
      }

      static JSONObject selectionCheck(TransitionApproachSelector.Selection sel) {
         return sel == null
            ? PfTestRunner.check("selection_completed", false, "selection not attempted")
            : PfTestRunner.check("selection_completed", sel.selected(), sel.evidence);
      }

      static JSONObject refusalFacts(String refusal, String reason) {
         JSONObject f = new JSONObject();
         f.put("profile", TransitionApproachSelector.TransitionProfile.DOOR_GATE.name());
         f.put("selected", false);
         f.put("refusal", refusal);
         f.put("reason", reason);
         f.put("fixtures", 0);
         f.put("candidates", 0);
         return f;
      }

      static JSONObject factsJson(TransitionApproachSelector.Selection sel) {
         if (sel == null) {
            return refusalFacts("NO_GAME", "selection not attempted (no in-game state)");
         } else {
            JSONObject f = new JSONObject();
            f.put("profile", sel.profile.name());
            f.put("selected", sel.selected());
            if (sel.refused()) {
               f.put("refusal", sel.refusal.name());
               f.put("reason", sel.evidence);
               f.put("fixtures", sel.fixtureCount);
               f.put("candidates", sel.candidates.size());
               return f;
            } else {
               f.put("refusal", JSONObject.NULL);
               f.put("reason", sel.evidence);
               f.put("fixtures", sel.fixtureCount);
               f.put("candidates", sel.candidates.size());
               f.put("door_gate_kind", sel.doorGateKind == null ? JSONObject.NULL : sel.doorGateKind.name());
               f.put("transition_state", sel.transitionState == null ? JSONObject.NULL : sel.transitionState.name());
               if (!sel.candidates.isEmpty()) {
                  f.put("best_score", PfTestRunner.round2(sel.candidates.get(0).score));
               }

               f.put("approach_tile", new JSONArray().put(sel.approachTile.x).put(sel.approachTile.y));
               f.put("approach_world", new JSONArray().put(PfTestRunner.round2(sel.approachWorld.x)).put(PfTestRunner.round2(sel.approachWorld.y)));
               f.put("approach_side", sel.side);
               f.put("standoff", PfTestRunner.round2(sel.standoff));
               f.put("footprint_tiles", PfTestRunner.round2(sel.footprintTiles));
               f.put("player_dist", PfTestRunner.round2(sel.playerDist));
               if (!sel.candidates.isEmpty()) {
                  f.put("route_cells", sel.candidates.get(0).route.size());
                  f.put("route_expanded", sel.candidates.get(0).routeExpanded);
               }

               return f;
            }
         }
      }

      private static JSONObject body(List<JSONObject> checks, JSONObject facts, String note) {
         JSONObject o = new JSONObject();
         o.put("verdict", PfTestRunner.verdictOf(checks));
         JSONArray arr = new JSONArray();

         for (JSONObject c : checks) {
            arr.put(c);
         }

         o.put("checks", arr);
         if (facts != null) {
            o.put("facts", facts);
         }

         if (note != null && !note.isEmpty()) {
            o.put("note", note);
         }

         return o;
      }
   }

   static final class SelectWaterlineApproachScenario implements PfTestRunner.Scenario {
      @Override
      public String name() {
         return "select_waterline_approach";
      }

      @Override
      public JSONObject execute(PfTestRunner.Run run, UI ui) throws Exception {
         if (run.cancelled) {
            throw new PfTestRunner.Cancelled();
         } else {
            List<JSONObject> checks = new ArrayList<>();
            GameUI gui = ui == null ? null : ui.gui;
            if (gui != null && gui.map != null) {
               checks.add(PfTestRunner.check("in_game", true, "in game"));
               if (run.cancelled) {
                  throw new PfTestRunner.Cancelled();
               } else {
                  PrototypePathfinder.Scene scene;
                  synchronized (ui) {
                     scene = PrototypePathfinder.observe(gui);
                  }

                  TransitionApproachSelector.Selection sel = TransitionApproachSelector.waterlineApproach(scene);
                  if (run.cancelled) {
                     throw new PfTestRunner.Cancelled();
                  } else {
                     checks.add(selectionCheck(sel));
                     return body(checks, factsJson(sel), sel.refused() ? "no approach selected: " + sel.refusal : null);
                  }
               }
            } else {
               checks.add(PfTestRunner.check("in_game", false, "client is not in game (select_waterline_approach requires in-game state)"));
               return body(checks, refusalFacts("NO_GAME", "client is not in game; selection not attempted"), "not in game: selection not attempted");
            }
         }
      }

      static JSONObject selectionCheck(TransitionApproachSelector.Selection sel) {
         return sel == null
            ? PfTestRunner.check("selection_completed", false, "selection not attempted")
            : PfTestRunner.check("selection_completed", sel.selected(), sel.evidence);
      }

      static JSONObject refusalFacts(String refusal, String reason) {
         JSONObject f = new JSONObject();
         f.put("profile", TransitionApproachSelector.TransitionProfile.WATERLINE.name());
         f.put("selected", false);
         f.put("refusal", refusal);
         f.put("reason", reason);
         f.put("water_cells", 0);
         f.put("waterline_cells", 0);
         f.put("candidates", 0);
         return f;
      }

      static JSONObject factsJson(TransitionApproachSelector.Selection sel) {
         if (sel == null) {
            return refusalFacts("NO_GAME", "selection not attempted (no in-game state)");
         } else {
            JSONObject f = new JSONObject();
            f.put("profile", sel.profile.name());
            f.put("selected", sel.selected());
            if (sel.refused()) {
               f.put("refusal", sel.refusal.name());
               f.put("reason", sel.evidence);
               f.put("water_cells", sel.waterCells);
               f.put("waterline_cells", sel.waterlineCells);
               f.put("candidates", sel.candidates.size());
               return f;
            } else {
               f.put("refusal", JSONObject.NULL);
               f.put("reason", sel.evidence);
               f.put("water_cells", sel.waterCells);
               f.put("waterline_cells", sel.waterlineCells);
               f.put("candidates", sel.candidates.size());
               if (!sel.candidates.isEmpty()) {
                  f.put("best_score", PfTestRunner.round2(sel.candidates.get(0).score));
               }

               f.put("approach_tile", new JSONArray().put(sel.approachTile.x).put(sel.approachTile.y));
               f.put("approach_world", new JSONArray().put(PfTestRunner.round2(sel.approachWorld.x)).put(PfTestRunner.round2(sel.approachWorld.y)));
               f.put("approach_side", sel.side);
               f.put("standoff", PfTestRunner.round2(sel.standoff));
               f.put("footprint_tiles", PfTestRunner.round2(sel.footprintTiles));
               f.put("player_dist", PfTestRunner.round2(sel.playerDist));
               if (!sel.candidates.isEmpty()) {
                  f.put("route_cells", sel.candidates.get(0).route.size());
                  f.put("route_expanded", sel.candidates.get(0).routeExpanded);
               }

               return f;
            }
         }
      }

      private static JSONObject body(List<JSONObject> checks, JSONObject facts, String note) {
         JSONObject o = new JSONObject();
         o.put("verdict", PfTestRunner.verdictOf(checks));
         JSONArray arr = new JSONArray();

         for (JSONObject c : checks) {
            arr.put(c);
         }

         o.put("checks", arr);
         if (facts != null) {
            o.put("facts", facts);
         }

         if (note != null && !note.isEmpty()) {
            o.put("note", note);
         }

         return o;
      }
   }
}
