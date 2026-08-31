package haven.pathfinding;

import haven.Coord;
import haven.Coord2d;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * NavReplay v1: versioned JSON for reproducing a navigation plan without a
 * live game connection. Stores occupancy, goal, routes, observations,
 * decisions, and outcome. Does not serialize renderer or UI objects.
 */
public final class NavReplay {
   public static final String FORMAT = "NavReplay";
   public static final int VERSION = 1;
   public static final String FEATURE = "navreplay";

   private NavReplay() {
   }

   public static JSONObject document(PfTestRunner.Run run, JSONObject result) {
      JSONObject o = new JSONObject();
      o.put("format", FORMAT);
      o.put("version", VERSION);
      o.put("scenario", run == null ? JSONObject.NULL : run.scenario);
      o.put("run_id", run == null ? JSONObject.NULL : run.id);
      o.put("generated_at_ms", System.currentTimeMillis());
      o.put("world", worldFromLogs(result));
      o.put("goal", goalFrom(result));
      o.put("raw_route", points(PathfinderLog.lastAStar()));
      o.put("smoothed_route", points(PathfinderLog.lastPath()));
      o.put("observations", observationsFrom(result));
      o.put("decisions", decisionsFrom());
      o.put("outcome", outcomeFrom(result));
      return o;
   }

   public static Path emit(PfTestRunner.Run run, JSONObject result, Path dir) throws IOException {
      if (run == null || dir == null) {
         return null;
      }
      Files.createDirectories(dir);
      Path file = dir.resolve(run.id + ".navreplay.jsonl");
      JSONObject body = document(run, result);
      JSONObject header = new JSONObject()
         .put("type", "header")
         .put("feature", FEATURE)
         .put("format", FORMAT)
         .put("version", VERSION)
         .put("scenario", run.scenario)
         .put("run_id", run.id)
         .put("generated_at_ms", System.currentTimeMillis());
      Writer w = Files.newBufferedWriter(file, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
      try {
         w.write(header.toString());
         w.write(10);
         w.write(body.toString());
         w.write(10);
      } finally {
         w.close();
      }
      return file;
   }

   public static JSONObject parse(String json) {
      return parseObject(new JSONObject(json));
   }

   public static JSONObject parseObject(JSONObject o) {
      if (o == null) {
         throw new IllegalArgumentException("NavReplay body is null");
      }
      if (!FORMAT.equals(o.optString("format"))) {
         throw new IllegalArgumentException("not a NavReplay document (format=" + o.optString("format") + ")");
      }
      if (o.optInt("version") != VERSION) {
         throw new IllegalArgumentException("unsupported NavReplay version " + o.optInt("version"));
      }
      return o;
   }

   public static JSONObject loadFile(Path file) throws IOException {
      List<String> lines = Files.readAllLines(file);
      if (lines.size() < 2) {
         throw new IllegalArgumentException("NavReplay file has no body: " + file);
      }
      JSONObject header = new JSONObject(lines.get(0));
      if (!"header".equals(header.optString("type"))) {
         throw new IllegalArgumentException("first line is not a header: " + file);
      }
      if (!FEATURE.equals(header.optString("feature")) && !FORMAT.equals(header.optString("format"))) {
         JSONObject body = new JSONObject(lines.get(1));
         if (FORMAT.equals(body.optString("format"))) {
            return parseObject(body);
         }
         throw new IllegalArgumentException("header is not a NavReplay: " + header.optString("feature"));
      }
      return parseObject(new JSONObject(lines.get(1)));
   }

   static JSONObject worldFromLogs(JSONObject result) {
      JSONObject world = new JSONObject();
      PathfinderLog.Occupancy occ = PathfinderLog.lastOccupancy();
      JSONObject scene = result == null ? null : result.optJSONObject("scene");
      if (occ != null) {
         world.put("cell", occ.cell);
         world.put("origin", point(occ.origin));
         world.put("grid", new JSONArray().put(occ.w).put(occ.h));
         world.put("occupancy", PathfinderLog.Occupancy.encode(occ));
         world.put("start_cell", cell(occ.start));
         world.put("goal_cell", cell(occ.goal));
         world.put("free_goal_cell", cell(occ.freeGoal));
      } else if (scene != null) {
         world.put("cell", scene.optDouble("cell", PrototypePathfinder.CELL));
         world.put("origin", scene.optJSONArray("origin"));
         world.put("grid", scene.optJSONArray("grid"));
      } else {
         world.put("cell", PrototypePathfinder.CELL);
         world.put("origin", new JSONArray());
         world.put("grid", new JSONArray().put(0).put(0));
      }
      if (scene != null) {
         world.put("player", scene.optJSONArray("player"));
         world.put("player_cell", scene.opt("player_cell"));
         world.put("radius", scene.optDouble("radius", PrototypePathfinder.DEFAULT_AGENT_RADIUS));
         world.put("moving", scene.optBoolean("moving"));
         world.put("obstacles", scene.optInt("obstacles"));
      } else {
         Coord2d player = PathfinderLog.lastConfirmedPos();
         if (player == null && occ != null && occ.start != null) {
            player = occ.world(occ.start.x, occ.start.y);
         }
         world.put("player", point(player));
         world.put("player_cell", occ == null ? JSONObject.NULL : cell(occ.start));
         world.put("radius", PrototypePathfinder.DEFAULT_AGENT_RADIUS);
         world.put("moving", false);
         world.put("obstacles", 0);
      }
      world.put("hazards", points(PathfinderLog.lastHazards()));
      return world;
   }

   static Object goalFrom(JSONObject result) {
      Coord2d dest = PathfinderLog.lastDest();
      if (dest == null && result != null) {
         JSONObject facts = result.optJSONObject("facts");
         dest = coord2d(facts == null ? null : facts.opt("target"));
         if (dest == null && facts != null) {
            dest = coord2d(facts.opt("goal"));
         }
      }
      if (dest == null) {
         return JSONObject.NULL;
      }
      JSONObject g = new JSONObject();
      g.put("kind", "POINT");
      g.put("position", point(dest));
      return g;
   }

   static JSONArray observationsFrom(JSONObject result) {
      JSONArray arr = new JSONArray();
      Coord2d pos = PathfinderLog.lastConfirmedPos();
      JSONObject scene = result == null ? null : result.optJSONObject("scene");
      if (pos == null && scene != null) {
         pos = coord2d(scene.opt("player"));
      }
      JSONObject o = new JSONObject();
      o.put("seq", 0);
      o.put("pos", point(pos));
      o.put("moving", scene != null && scene.optBoolean("moving"));
      o.put("confirmed", true);
      arr.put(o);
      return arr;
   }

   static JSONArray decisionsFrom() {
      JSONArray arr = new JSONArray();
      List<JSONObject> recent = PathfinderLog.recent();
      int seq = 0;
      for (JSONObject src : recent) {
         if (src == null || src.optBoolean("probe")) {
            continue;
         }
         JSONObject d = new JSONObject();
         d.put("seq", seq++);
         d.put("kind", "PLAN");
         d.put("reason", src.optString("reason", ""));
         d.put("clip", src.optString("clip", ""));
         d.put("waypoint_count", src.optInt("waypoint_count"));
         d.put("expanded", src.optInt("expanded"));
         arr.put(d);
      }
      String replan = PathfinderLog.lastReplanReason();
      if (replan != null && !replan.isEmpty()) {
         JSONObject d = new JSONObject();
         d.put("seq", seq);
         d.put("kind", "REPLAN");
         d.put("reason", replan);
         arr.put(d);
      }
      return arr;
   }

   static JSONObject outcomeFrom(JSONObject result) {
      JSONObject o = new JSONObject();
      if (result == null) {
         o.put("status", "unknown");
         o.put("verdict", JSONObject.NULL);
         o.put("reason", "");
         return o;
      }
      o.put("status", result.optString("status", "unknown"));
      o.put("verdict", result.has("verdict") ? result.get("verdict") : JSONObject.NULL);
      String reason = PathfinderLog.lastReason();
      if (reason == null || reason.isEmpty()) {
         reason = result.optString("note", result.optString("error", ""));
      }
      o.put("reason", reason == null ? "" : reason);
      return o;
   }

   static JSONArray points(List<Coord2d> pts) {
      JSONArray arr = new JSONArray();
      if (pts != null) {
         for (Coord2d p : pts) {
            arr.put(point(p));
         }
      }
      return arr;
   }

   static JSONArray point(Coord2d p) {
      JSONArray a = new JSONArray();
      if (p != null) {
         a.put(round4(p.x)).put(round4(p.y));
      }
      return a;
   }

   static JSONArray cell(Coord c) {
      return c == null ? new JSONArray() : new JSONArray().put(c.x).put(c.y);
   }

   static Coord2d coord2d(Object v) {
      if (v instanceof JSONArray) {
         JSONArray a = (JSONArray) v;
         if (a.length() >= 2) {
            return Coord2d.of(a.getDouble(0), a.getDouble(1));
         }
      }
      return null;
   }

   static Coord coord(Object v) {
      if (v instanceof JSONArray) {
         JSONArray a = (JSONArray) v;
         if (a.length() >= 2) {
            return Coord.of(a.getInt(0), a.getInt(1));
         }
      }
      return null;
   }

   static List<Coord2d> coords2d(JSONArray arr) {
      if (arr == null) {
         return Collections.emptyList();
      }
      List<Coord2d> out = new ArrayList<>();
      for (int i = 0; i < arr.length(); i++) {
         Coord2d p = coord2d(arr.opt(i));
         if (p != null) {
            out.add(p);
         }
      }
      return out;
   }

   static double round4(double v) {
      return (double) Math.round(v * 10000.0) / 10000.0;
   }
}
