package haven.pathfinding;

import haven.CFG;
import haven.Coord;
import haven.Coord2d;
import haven.GameUI;
import haven.Utils;
import haven.GameUI.MsgType;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

public final class PathfinderLog {
   private static final int KEEP = 24;
   private static final Deque<JSONObject> recent = new ArrayDeque<>();
   private static volatile JSONObject last;
   private static volatile List<Coord2d> lastPath = Collections.emptyList();
   private static volatile List<Coord2d> lastAStar = Collections.emptyList();
   private static volatile List<Coord2d[]> lastPolys = Collections.emptyList();
   private static volatile Coord2d lastDest;
   private static volatile PathfinderLog.Occupancy lastOcc;
   private static volatile String lastReason = "";
   private static volatile String lastReplanReason = "";
   private static volatile Coord2d lastActiveWaypoint;
   private static volatile Coord2d lastConfirmedPos;
   private static volatile List<Coord2d> lastHazards = Collections.emptyList();
   private static final ThreadLocal<String> target = new ThreadLocal<>();
   private static final ThreadLocal<Integer> probeDepth = ThreadLocal.withInitial(() -> 0);

   private PathfinderLog() {
   }

   public static void setTarget(String label) {
      target.set(label);
   }

   public static void clearTarget() {
      target.remove();
   }

   public static void beginProbe() {
      probeDepth.set(probeDepth.get() + 1);
   }

   public static void endProbe() {
      int n = probeDepth.get() - 1;
      if (n <= 0) {
         probeDepth.remove();
      } else {
         probeDepth.set(n);
      }
   }

   public static boolean probing() {
      Integer n = probeDepth.get();
      return n != null && n > 0;
   }

   public static void record(GameUI gui, PathfinderLog.Trace trace) {
      if (trace != null) {
         JSONObject o = trace.toJson();
         boolean probe = probing();
         synchronized (recent) {
            recent.addLast(o);

            while (recent.size() > 24) {
               recent.removeFirst();
            }

            if (!probe) {
               last = o;
               lastPath = new ArrayList<>(trace.waypoints);
               lastAStar = (List<Coord2d>)(trace.astar == null ? Collections.emptyList() : new ArrayList<>(trace.astar));
               lastPolys = (List<Coord2d[]>)(trace.polys == null ? Collections.emptyList() : new ArrayList<>(trace.polys));
               lastDest = Coord2d.of(trace.dx, trace.dy);
               lastOcc = trace.occupancy;
               lastReason = trace.reason;
               lastHazards = (List<Coord2d>)(trace.hazards == null ? Collections.emptyList() : new ArrayList<>(trace.hazards));
               if (gui != null && gui.map != null && gui.map.player() != null) {
                  lastConfirmedPos = gui.map.player().rc;
               }
            }
         }

         append(o);
         if (!probe && gui != null && Boolean.TRUE.equals(CFG.DEBUG_PATHFIND.get())) {
            gui.msg("PF " + trace.summary(), MsgType.INFO);
         }
      }
   }

   public static JSONObject last() {
      return last;
   }

   public static List<Coord2d> lastPath() {
      return lastPath;
   }

   public static List<Coord2d> lastAStar() {
      return lastAStar;
   }

   public static List<Coord2d[]> lastPolys() {
      return lastPolys;
   }

   public static Coord2d lastDest() {
      return lastDest;
   }

   public static PathfinderLog.Occupancy lastOccupancy() {
      return lastOcc;
   }

   public static void recordOccupancy(PathfinderLog.Occupancy occ) {
      lastOcc = occ;
   }

   public static String lastReason() {
      return lastReason;
   }

   public static void setReplanReason(String reason) {
      lastReplanReason = reason == null ? "" : reason;
   }

   public static String lastReplanReason() {
      return lastReplanReason;
   }

   public static void setActiveWaypoint(Coord2d waypoint) {
      lastActiveWaypoint = waypoint;
   }

   public static Coord2d lastActiveWaypoint() {
      return lastActiveWaypoint;
   }

   public static Coord2d lastConfirmedPos() {
      return lastConfirmedPos;
   }

   public static void recordConfirmedPos(Coord2d pos) {
      lastConfirmedPos = pos;
   }

   public static void recordHazards(List<Coord2d> hazards) {
      lastHazards = hazards == null ? Collections.emptyList() : new ArrayList<>(hazards);
   }

   public static List<Coord2d> lastHazards() {
      return lastHazards;
   }

   public static List<JSONObject> recent() {
      synchronized (recent) {
         return new ArrayList<>(recent);
      }
   }

   public static Path logFile() {
      return Utils.path(System.getProperty("user.dir", ".")).resolve("dev-snapshots").resolve("pf").resolve("plans.jsonl");
   }

   public static JSONObject capture() {
      JSONObject body = new JSONObject();
      body.put("log_file", logFile().toString());
      body.put("last", last == null ? JSONObject.NULL : last);
      PathfinderLog.Occupancy occ = lastOcc;
      if (occ != null) {
         body.put("occupancy_ascii", occ.ascii(occ.start, 10));
      }

      JSONArray arr = new JSONArray();

      for (JSONObject o : recent()) {
         arr.put(o);
      }

      body.put("recent", arr);
      return body;
   }

   private static void append(JSONObject o) {
      try {
         Path file = logFile();
         Files.createDirectories(file.getParent());
         Writer w = Files.newBufferedWriter(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND);

         try {
            w.write(o.toString());
            w.write(10);
         } catch (Throwable var6) {
            if (w != null) {
               try {
                  w.close();
               } catch (Throwable var5) {
                  var6.addSuppressed(var5);
               }
            }

            throw var6;
         }

         if (w != null) {
            w.close();
         }
      } catch (IOException var7) {
      }
   }

   private static JSONArray cell(Coord c) {
      return c == null ? new JSONArray() : new JSONArray().put(c.x).put(c.y);
   }

   private static double round(double v) {
      return (double)Math.round(v * 10.0) / 10.0;
   }

   public static final class Occupancy {
      public static final byte FREE = 0;
      public static final byte SOLID = 1;
      public static final byte DILATED = 2;
      public static final byte CARVED = 3;
      public final Coord2d origin;
      public final int w;
      public final int h;
      public final double cell;
      public final byte[] occ;
      public final Coord start;
      public final Coord goal;
      public final Coord freeGoal;
      public final List<Coord> astar;

      public Occupancy(Coord2d origin, int w, int h, double cell, byte[] occ, Coord start, Coord goal, Coord freeGoal, List<Coord> astar) {
         this.origin = origin;
         this.w = w;
         this.h = h;
         this.cell = cell;
         this.occ = occ;
         this.start = start;
         this.goal = goal;
         this.freeGoal = freeGoal;
         this.astar = astar == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(astar));
      }

      public static PathfinderLog.Occupancy capture(
         Coord2d origin,
         int w,
         int h,
         double cell,
         boolean[] solid,
         boolean[] dilated,
         boolean[] blockedAfter,
         Coord start,
         Coord goal,
         Coord freeGoal,
         List<Coord> astar
      ) {
         byte[] occ = new byte[Math.max(0, w * h)];
         int n = Math.min(occ.length, Math.min(len(solid), len(dilated)));

         for (int i = 0; i < n; i++) {
            if (solid != null && solid[i]) {
               occ[i] = 1;
            } else if (dilated != null && dilated[i]) {
               occ[i] = (byte)(blockedAfter != null && i < blockedAfter.length && blockedAfter[i] ? 2 : 3);
            } else {
               occ[i] = 0;
            }
         }

         return new PathfinderLog.Occupancy(origin, w, h, cell, occ, start, goal, freeGoal, astar);
      }

      public static String encode(PathfinderLog.Occupancy occ) {
         if (occ == null || occ.occ == null) {
            return "";
         }
         char[] chars = new char[occ.occ.length];
         for (int i = 0; i < occ.occ.length; i++) {
            int v = occ.occ[i] & 0xff;
            chars[i] = (char)('0' + Math.min(9, v));
         }
         return new String(chars);
      }

      public static PathfinderLog.Occupancy decode(
         Coord2d origin, int w, int h, double cell, String encoded, Coord start, Coord goal, Coord freeGoal
      ) {
         byte[] occ = new byte[Math.max(0, w * h)];
         if (encoded != null) {
            int n = Math.min(occ.length, encoded.length());
            for (int i = 0; i < n; i++) {
               char c = encoded.charAt(i);
               occ[i] = (byte)(c >= '0' && c <= '9' ? c - '0' : 1);
            }
         }
         return new PathfinderLog.Occupancy(origin, w, h, cell, occ, start, goal, freeGoal, Collections.emptyList());
      }

      public Coord cellOf(Coord2d p) {
         return p != null && this.origin != null && !(this.cell <= 0.0)
            ? Coord.of((int)Math.floor((p.x - this.origin.x) / this.cell), (int)Math.floor((p.y - this.origin.y) / this.cell))
            : null;
      }

      public Coord2d world(int x, int y) {
         return this.origin.add(((double)x + 0.5) * this.cell, ((double)y + 0.5) * this.cell);
      }

      public Coord2d corner(int x, int y) {
         return this.origin.add((double)x * this.cell, (double)y * this.cell);
      }

      public byte at(int x, int y) {
         return x >= 0 && y >= 0 && x < this.w && y < this.h ? this.occ[y * this.w + x] : 1;
      }

      public boolean onPath(int x, int y) {
         for (Coord c : this.astar) {
            if (c != null && c.x == x && c.y == y) {
               return true;
            }
         }

         return false;
      }

      public static String name(byte v) {
         switch (v) {
            case 1:
               return "SOLID";
            case 2:
               return "INFLATED";
            case 3:
               return "carve";
            default:
               return "free";
         }
      }

      public String ascii(Coord focus, int radius) {
         if (focus == null) {
            focus = this.start == null ? Coord.z : this.start;
         }

         int r = Math.max(1, radius);
         Set<Long> path = new HashSet<>();

         for (Coord c : this.astar) {
            if (c != null) {
               path.add((long)c.y << 32 ^ (long)c.x & 4294967295L);
            }
         }

         StringBuilder sb = new StringBuilder();
         sb.append("  ");

         for (int x = focus.x - r; x <= focus.x + r; x++) {
            sb.append(Math.abs(x) % 10);
         }

         sb.append('\n');

         for (int y = focus.y - r; y <= focus.y + r; y++) {
            sb.append(Math.abs(y) % 10).append(' ');

            for (int x = focus.x - r; x <= focus.x + r; x++) {
               char ch = '.';
               if (x >= 0 && y >= 0 && x < this.w && y < this.h) {
                  byte v = this.occ[y * this.w + x];
                  if (v == 1) {
                     ch = '#';
                  } else if (v == 2) {
                     ch = '+';
                  } else if (v == 3) {
                     ch = 'o';
                  }

                  if (path.contains((long)y << 32 ^ (long)x & 4294967295L)) {
                     ch = '*';
                  }

                  if (this.freeGoal != null && this.freeGoal.x == x && this.freeGoal.y == y) {
                     ch = 'F';
                  }

                  if (this.goal != null && this.goal.x == x && this.goal.y == y) {
                     ch = 'G';
                  }

                  if (this.start != null && this.start.x == x && this.start.y == y) {
                     ch = 'S';
                  }

                  if (focus.x == x && focus.y == y) {
                     ch = '@';
                  }
               } else {
                  ch = ' ';
               }

               sb.append(ch);
            }

            sb.append('\n');
         }

         sb.append("@ here  S start  G wanted  F snapped  * path  # solid  + inflated  o carve  . free");
         return sb.toString();
      }

      private static int len(boolean[] a) {
         return a == null ? 0 : a.length;
      }
   }

   public static final class Trace {
      public String reason = "ok";
      public String clip = "";
      public double sx;
      public double sy;
      public double dx;
      public double dy;
      public double radius;
      public int dilation;
      public int expanded;
      public int obstacles;
      public int gridW;
      public int gridH;
      public boolean startBlocked;
      public boolean startBlockedAfter;
      public boolean startInSolid;
      public boolean goalBlocked;
      public boolean complete;
      public Coord startCell;
      public Coord goalCell;
      public Coord freeGoal;
      public List<Coord2d> waypoints = Collections.emptyList();
      public List<Coord2d> astar = Collections.emptyList();
      public List<String> near = Collections.emptyList();
      public List<Coord2d[]> polys = Collections.emptyList();
      public List<Coord2d> hazards = Collections.emptyList();
      public PathfinderLog.Occupancy occupancy;

      JSONObject toJson() {
         JSONObject o = new JSONObject();
         o.put("t", System.currentTimeMillis());
         String tgt = PathfinderLog.target.get();
         o.put("target", tgt == null ? "" : tgt);
         o.put("reason", this.reason);
         o.put("clip", this.clip == null ? "" : this.clip);
         o.put("start", new JSONArray().put(PathfinderLog.round(this.sx)).put(PathfinderLog.round(this.sy)));
         o.put("dest", new JSONArray().put(PathfinderLog.round(this.dx)).put(PathfinderLog.round(this.dy)));
         o.put("agent_radius", PathfinderLog.round(this.radius));
         o.put("dilation", this.dilation);
         o.put("grid", new JSONArray().put(this.gridW).put(this.gridH));
         o.put("start_in_solid", this.startInSolid);
         o.put("start_blocked", this.startBlocked);
         o.put("start_blocked_after_carve", this.startBlockedAfter);
         o.put("goal_blocked", this.goalBlocked);
         o.put("start_cell", PathfinderLog.cell(this.startCell));
         o.put("goal_cell", PathfinderLog.cell(this.goalCell));
         o.put("free_goal", PathfinderLog.cell(this.freeGoal));
         o.put("expanded", this.expanded);
         o.put("obstacles", this.obstacles);
         o.put("complete", this.complete);
         o.put("probe", PathfinderLog.probing());
         o.put("waypoint_count", this.waypoints.size());
         o.put("astar_count", this.astar == null ? 0 : this.astar.size());
         JSONArray wps = new JSONArray();

         for (Coord2d p : this.waypoints) {
            wps.put(new JSONArray().put(PathfinderLog.round(p.x)).put(PathfinderLog.round(p.y)));
         }

         o.put("waypoints", wps);
         JSONArray nearArr = new JSONArray();

         for (String n : this.near) {
            nearArr.put(n);
         }

         o.put("near", nearArr);
         return o;
      }

      public String summary() {
         String tgt = PathfinderLog.target.get();
         if (tgt == null || tgt.isEmpty()) {
            tgt = this.destLabel();
         }

         String clipBit = this.clip != null && !this.clip.isEmpty() ? " clip=" + this.clip : "";
         return String.format(
            "%s  %s  %d wp  %d nodes  r=%.1f dil=%d%s%s",
            this.reason,
            tgt,
            Math.max(0, this.waypoints.size() - 1),
            this.expanded,
            this.radius,
            this.dilation,
            this.complete ? "" : " partial",
            clipBit
         );
      }

      private String destLabel() {
         return String.format("%.0f,%.0f", this.dx, this.dy);
      }
   }
}
