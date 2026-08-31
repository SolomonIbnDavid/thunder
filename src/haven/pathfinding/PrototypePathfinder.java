package haven.pathfinding;

import auto.Bot;
import haven.CFG;
import haven.Coord;
import haven.Coord2d;
import haven.GameUI;
import haven.Gob;
import haven.Hitbox;
import haven.Loading;
import haven.MCache;
import haven.Moving;
import haven.OCache;
import haven.Resource;
import haven.GameUI.MsgType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.ender.ClientUtils;
import me.ender.gob.KinInfo;

public final class PrototypePathfinder {
   public static final double CELL = 2.75;
   public static final double PAD = 1.0;
   public static final double DEFAULT_AGENT_RADIUS = 4.5;
   public static final int MAX_SIDE = 192;
   public static final int MAX_EXPANDED = 36864;
   private static final double MARGIN = 33.0;
   public static final double OVERLAP = 2.75 * Math.sqrt(2.0) / 2.0;
   public static final double HOUSE_CLEARANCE = 2.0;
   private static final Map<String, Coord2d> BUILDING_HALF = new HashMap<>();
   private static final Map<String, Coord2d> FURNITURE_HALF = new HashMap<>();
   public static final int SNAP_SIDE = 72;

   private PrototypePathfinder() {
   }

   public static PrototypePathfinder.Plan plan(GameUI gui, Coord2d destination) {
      return destination == null ? planAny(gui, Collections.emptyList(), true) : planAny(gui, Collections.singletonList(destination), true);
   }

   public static PrototypePathfinder.Plan planAny(GameUI gui, List<Coord2d> destinations, boolean snap) {
      PathfinderLog.Trace tr = new PathfinderLog.Trace();
      Gob player = gui != null && gui.map != null ? gui.map.player() : null;
      if (player != null && destinations != null && !destinations.isEmpty()) {
         Coord2d start = player.rc;
         tr.sx = start.x;
         tr.sy = start.y;
         Coord2d first = destinations.get(0);
         tr.dx = first.x;
         tr.dy = first.y;
         double radius = agentRadius(player);
         tr.radius = radius;
         PrototypePathfinder.ClipResult clip = clipToHorizon(start, destinations);
         List<Coord2d> targets = clip.targets;
         if (targets.isEmpty()) {
            tr.reason = "no_player";
            PathfinderLog.record(gui, tr);
            return new PrototypePathfinder.Plan(Collections.emptyList(), false, 0, 0, PrototypePathfinder.Plan.Status.FAILED);
         } else {
            PrototypePathfinder.Grid grid = planGrid(start, targets);
            tr.gridW = grid.w;
            tr.gridH = grid.h;
            List<Coord2d[]> debugPolys = new ArrayList<>();
            PrototypePathfinder.OccupancyBuild occ = PrototypePathfinder.OccupancyBuild.build(gui, grid, player, debugPolys);
            boolean[] solid = occ.solid;
            boolean[] dilated = occ.dilated;
            int obstacles = occ.obstacles;
            tr.obstacles = obstacles;
            tr.polys = debugPolys;
            tr.hazards = occ.hazards;
            tr.near = nearBlockers(gui, player, start);
            PrototypePathfinder.Plan plan = planCore(start, destinations, targets, clip.clipped, snap, radius, grid, solid, dilated, obstacles, tr);
            tr.clip = clipAlong(gui, player, plan.waypoints);
            PathfinderLog.record(gui, tr);
            return plan;
         }
      } else {
         tr.reason = "no_player";
         PathfinderLog.record(gui, tr);
         return new PrototypePathfinder.Plan(Collections.emptyList(), false, 0, 0, PrototypePathfinder.Plan.Status.FAILED);
      }
   }

   static PrototypePathfinder.Plan planCore(
      Coord2d start,
      List<Coord2d> destinations,
      List<Coord2d> targets,
      List<Boolean> clippedFlags,
      boolean snap,
      double radius,
      PrototypePathfinder.Grid grid,
      boolean[] solid,
      boolean[] dilated,
      int obstacles,
      PathfinderLog.Trace tr
   ) {
      int w = grid.w;
      int h = grid.h;
      if (targets != null && !targets.isEmpty()) {
         Coord sc = grid.cell(start);
         tr.startCell = sc;
         if (sc.x >= 0 && sc.y >= 0 && sc.x < w && sc.y < h) {
            int dil = dilationCells(radius);
            tr.dilation = dil;
            tr.startInSolid = solid[sc.y * w + sc.x];
            applyClearanceCost(grid.cost, solid, w, h);
            tr.startBlocked = dilated[sc.y * w + sc.x];
            openFootprint(grid.blocked, w, h, sc.x, sc.y, dil, solid);
            if (grid.blocked[sc.y * w + sc.x]) {
               openStartPocket(grid.blocked, w, h, sc.x, sc.y, dil + 1);
            }

            tr.startBlockedAfter = grid.blocked[sc.y * w + sc.x];
            List<Coord> goalCells = new ArrayList<>();
            List<Coord2d> exactAt = new ArrayList<>();
            List<Boolean> clippedOf = new ArrayList<>();
            Coord requestedGoal = clamp(grid.cell(targets.get(0)), w, h);
            tr.goalCell = requestedGoal;
            tr.goalBlocked = grid.blocked[requestedGoal.y * w + requestedGoal.x];

            for (int ti = 0; ti < targets.size(); ti++) {
               Coord2d t = targets.get(ti);
               Coord req = clamp(grid.cell(t), w, h);
               if (!solid[req.y * w + req.x] && grid.blocked[req.y * w + req.x]) {
                  openFootprint(grid.blocked, w, h, req.x, req.y, dil, solid);
               }

               Coord gc = req;
               if (grid.blocked[req.y * w + req.x]) {
                  if (!snap) {
                     continue;
                  }

                  gc = nearestFree(grid.blocked, w, h, req, sc);
               }

               if (gc != null) {
                  goalCells.add(gc);
                  exactAt.add(gc.equals(req) ? t : null);
                  clippedOf.add(clippedFlags.get(ti));
               }
            }

            if (goalCells.isEmpty()) {
               tr.reason = "no_free_goal";
               return new PrototypePathfinder.Plan(Collections.emptyList(), false, 0, obstacles, PrototypePathfinder.Plan.Status.FAILED);
            } else {
               tr.freeGoal = goalCells.get(0);
               GridAStar.Result result = GridAStar.find(grid, sc, goalCells, 36864);
               List<Coord2d> raw = new ArrayList<>();

               for (Coord c : result.cells) {
                  raw.add(grid.world(c));
               }

               tr.astar = new ArrayList<>(raw);
               tr.occupancy = PathfinderLog.Occupancy.capture(
                  grid.origin, w, h, 2.75, solid, dilated, grid.blocked, sc, requestedGoal, goalCells.get(0), result.cells
               );
               boolean reachedRequested = false;
               boolean reachedClipped = false;
               if (result.complete && !raw.isEmpty()) {
                  Coord last = result.cells.get(result.cells.size() - 1);

                  for (int i = 0; i < goalCells.size(); i++) {
                     if (goalCells.get(i).equals(last)) {
                        reachedClipped = clippedOf.get(i);
                        Coord2d exact = exactAt.get(i);
                        if (exact != null
                           && exact.x >= grid.origin.x
                           && exact.y >= grid.origin.y
                           && exact.x < grid.origin.x + (double)w * 2.75
                           && exact.y < grid.origin.y + (double)h * 2.75) {
                           raw.set(raw.size() - 1, exact);
                           reachedRequested = true;
                        }
                        break;
                     }
                  }
               }

               List<Coord2d> waypoints = smooth(raw, grid, dilated);
               boolean complete = result.complete;
               PrototypePathfinder.Plan.Status status;
               if (waypoints.size() < 2) {
                  status = complete ? PrototypePathfinder.Plan.Status.REACHED : PrototypePathfinder.Plan.Status.FAILED;
               } else if (!complete) {
                  status = PrototypePathfinder.Plan.Status.PARTIAL;
               } else if (reachedClipped) {
                  status = PrototypePathfinder.Plan.Status.CLIPPED;
               } else if (!reachedRequested) {
                  status = PrototypePathfinder.Plan.Status.SNAPPED;
               } else {
                  status = PrototypePathfinder.Plan.Status.REACHED;
               }

               if (waypoints.size() < 2) {
                  tr.reason = minDist(start, destinations) <= 11.0 ? "already_there" : "no_route";
               } else if (!complete) {
                  tr.reason = "partial";
               } else if (reachedClipped) {
                  tr.reason = "clipped";
               } else if (!reachedRequested) {
                  tr.reason = "snapped";
               } else {
                  tr.reason = "ok";
               }

               tr.waypoints = waypoints;
               tr.complete = complete;
               tr.expanded = result.expanded;
               return new PrototypePathfinder.Plan(waypoints, complete, !reachedRequested, result.expanded, obstacles, status);
            }
         } else {
            tr.reason = "start_off_grid";
            return new PrototypePathfinder.Plan(Collections.emptyList(), false, 0, obstacles, PrototypePathfinder.Plan.Status.FAILED);
         }
      } else {
         tr.reason = "no_goal";
         return new PrototypePathfinder.Plan(Collections.emptyList(), false, 0, obstacles, PrototypePathfinder.Plan.Status.FAILED);
      }
   }

   /**
    * Replay planning against a captured occupancy grid. Reconstructs the
    * pre-carve solid/dilated masks and calls {@link #planCore} — the same
    * planner used by live {@link #planAny}.
    */
   static PrototypePathfinder.Plan planFromOccupancy(
      Coord2d start, Coord2d dest, boolean snap, double radius, PathfinderLog.Occupancy occ, int obstacles, PathfinderLog.Trace tr
   ) {
      if (tr == null) {
         tr = new PathfinderLog.Trace();
      }
      if (start == null || occ == null || occ.w <= 0 || occ.h <= 0 || occ.occ == null) {
         tr.reason = start == null ? "no_player" : "no_goal";
         return new PrototypePathfinder.Plan(Collections.emptyList(), false, 0, obstacles, PrototypePathfinder.Plan.Status.FAILED);
      }
      List<Coord2d> destinations = dest == null ? Collections.emptyList() : Collections.singletonList(dest);
      PrototypePathfinder.ClipResult clip = clipToHorizon(start, destinations);
      List<Coord2d> targets = clip.targets;
      tr.sx = start.x;
      tr.sy = start.y;
      if (dest != null) {
         tr.dx = dest.x;
         tr.dy = dest.y;
      }
      tr.radius = radius;
      tr.gridW = occ.w;
      tr.gridH = occ.h;
      if (targets.isEmpty()) {
         tr.reason = dest == null ? "no_goal" : "no_player";
         return new PrototypePathfinder.Plan(Collections.emptyList(), false, 0, obstacles, PrototypePathfinder.Plan.Status.FAILED);
      }
      PrototypePathfinder.Grid grid = new PrototypePathfinder.Grid(occ.origin, occ.w, occ.h);
      boolean[] solid = new boolean[occ.w * occ.h];
      boolean[] dilated = new boolean[occ.w * occ.h];
      int n = Math.min(occ.occ.length, solid.length);
      for (int i = 0; i < n; i++) {
         byte v = occ.occ[i];
         if (v == PathfinderLog.Occupancy.SOLID) {
            solid[i] = true;
         } else if (v == PathfinderLog.Occupancy.DILATED || v == PathfinderLog.Occupancy.CARVED) {
            dilated[i] = true;
            grid.blocked[i] = true;
         }
      }
      return planCore(start, destinations, targets, clip.clipped, snap, radius, grid, solid, dilated, obstacles, tr);
   }

   static PrototypePathfinder.ClipResult clipToHorizon(Coord2d start, List<Coord2d> destinations) {
      PrototypePathfinder.ClipResult out = new PrototypePathfinder.ClipResult();
      double maxReach = maxReach();
      if (start != null && destinations != null) {
         for (Coord2d d : destinations) {
            if (d != null) {
               Coord2d delta = d.sub(start);
               boolean wasClipped = delta.abs() > maxReach;
               out.targets.add(wasClipped ? start.add(delta.norm().mul(maxReach)) : d);
               out.clipped.add(wasClipped);
            }
         }

         return out;
      } else {
         return out;
      }
   }

   static PrototypePathfinder.Grid planGrid(Coord2d start, List<Coord2d> targets) {
      double minx = start.x;
      double miny = start.y;
      double maxx = start.x;
      double maxy = start.y;

      for (Coord2d t : targets) {
         if (t != null) {
            minx = Math.min(minx, t.x);
            miny = Math.min(miny, t.y);
            maxx = Math.max(maxx, t.x);
            maxy = Math.max(maxy, t.y);
         }
      }

      double spanX = Math.min(528.0, maxx - minx + 66.0);
      double spanY = Math.min(528.0, maxy - miny + 66.0);
      double cx = (minx + maxx) * 0.5;
      double cy = (miny + maxy) * 0.5;
      int w = Math.max(8, Math.min(192, (int)Math.ceil(spanX / 2.75)));
      int h = Math.max(8, Math.min(192, (int)Math.ceil(spanY / 2.75)));
      return new PrototypePathfinder.Grid(alignedOrigin(cx - (double)w * 2.75 * 0.5, cy - (double)h * 2.75 * 0.5), w, h);
   }

   private static double minDist(Coord2d start, List<Coord2d> dests) {
      double best = Double.POSITIVE_INFINITY;
      if (start != null && dests != null) {
         for (Coord2d d : dests) {
            if (d != null) {
               best = Math.min(best, start.dist(d));
            }
         }

         return best;
      } else {
         return best;
      }
   }

   public static void execute(GameUI gui, PrototypePathfinder.Plan plan) {
      if (gui != null && gui.map != null && plan != null && plan.waypoints.size() >= 2) {
         gui.pathQueue.repath();

         for (int i = 1; i < plan.waypoints.size(); i++) {
            gui.pathQueue.add(plan.waypoints.get(i));
         }

         Coord2d first = plan.waypoints.get(1);
         gui.map.wdgmsg("click", new Object[]{Coord.z, first.floor(OCache.posres), 1, 0});
      }
   }

   public static double maxReach() {
      return 462.0;
   }

   public static Coord2d alignedOrigin(double x, double y) {
      return Coord2d.of(Math.floor(x / 2.75) * 2.75, Math.floor(y / 2.75) * 2.75);
   }

   public static PrototypePathfinder.Scene observe(GameUI gui) {
      PrototypePathfinder.Scene scene = new PrototypePathfinder.Scene();
      Gob player = gui != null && gui.map != null ? gui.map.player() : null;
      if (player != null && player.rc != null) {
         scene.player = player.rc;
         scene.moving = player.getattr(Moving.class) != null;
         scene.radius = agentRadius(player);
         scene.terrain = terrainName(gui, player.rc);
         int w = 72;
         int h = 72;
         scene.w = w;
         scene.h = h;
         scene.origin = alignedOrigin(player.rc.x - (double)w * 2.75 * 0.5, player.rc.y - (double)h * 2.75 * 0.5);
         PrototypePathfinder.Grid grid = new PrototypePathfinder.Grid(scene.origin, w, h);
         PrototypePathfinder.OccupancyBuild occ = PrototypePathfinder.OccupancyBuild.build(gui, grid, player, null);
         boolean[] solid = occ.solid;
         boolean[] dilated = occ.dilated;
         scene.obstacles = occ.obstacles;
         scene.terrainCells = occ.terrainNames;
         Coord sc = grid.cell(player.rc);
         scene.playerCell = sc;
         if (sc.x >= 0 && sc.y >= 0 && sc.x < w && sc.y < h) {
            scene.playerInSolid = solid[sc.y * w + sc.x];
            scene.playerInDilated = dilated[sc.y * w + sc.x];
         } else {
            scene.playerInSolid = scene.playerInDilated = true;
         }

         for (int i = 0; i < solid.length; i++) {
            if (solid[i]) {
               scene.solidCount++;
            } else if (dilated[i]) {
               scene.dilatedCount++;
            }
         }

         scene.occupancy = PathfinderLog.Occupancy.capture(scene.origin, w, h, 2.75, solid, dilated, dilated, sc, null, null, Collections.emptyList());
         scene.gobs = nearbyGeometry(gui, player, 220.0);
         PathfinderLog.recordOccupancy(scene.occupancy);
         PathfinderLog.recordHazards(occ.hazards);
         PathfinderLog.recordConfirmedPos(scene.player);
         return scene;
      } else {
         return scene;
      }
   }

   public static String terrainName(GameUI gui, Coord2d at) {
      if (gui != null && gui.ui != null && gui.ui.sess != null && at != null) {
         try {
            Resource res = gui.ui.sess.glob.map.tilesetr(gui.ui.sess.glob.map.gettile(at.floor(MCache.tilesz)));
            return res == null ? "" : res.name;
         } catch (Loading var3) {
            return "loading";
         }
      } else {
         return "";
      }
   }

   public static List<PrototypePathfinder.GobGeom> nearbyGeometry(GameUI gui, Gob player, double reach) {
      List<PrototypePathfinder.GobGeom> out = new ArrayList<>();
      if (gui != null && gui.ui != null && gui.ui.sess != null && player != null && player.rc != null) {
         synchronized (gui.ui.sess.glob.oc) {
            for (Gob gob : gui.ui.sess.glob.oc) {
               if (gob != null && gob != player && !gob.virtual && gob.id >= 0L && gob.rc != null && !(gob.rc.dist(player.rc) > reach)) {
                  PrototypePathfinder.GobGeom g = new PrototypePathfinder.GobGeom();
                  g.id = gob.id;
                  g.rc = gob.rc;
                  g.a = gob.a;
                  g.gobDist = gob.rc.dist(player.rc);

                  try {
                     g.name = displayName(gob);
                     g.resid = gob.resid() == null ? "" : gob.resid();
                     g.passable = Hitbox.passable(gob);
                     g.cupboard = CupboardCatalog.isCupboardResid(g.resid);
                     g.boulder = TransitionApproachSelector.isBoulderResid(g.resid);
                     g.caveTransition = TransitionApproachSelector.isCaveTransitionResid(g.resid);
                     g.doorGate = TransitionApproachSelector.isDoorGateResid(g.resid);
                     g.gateState = gob.sdt();
                     g.visitorGate = g.doorGate && gob.isVisitorGate();
                     g.movement = Hitbox.movementPolygons(gob);
                     g.hitbox = Hitbox.worldPolygons(gob, true);
                     g.polyDist = minPolyDist(player.rc, g.movement);
                     g.hitboxDist = minPolyDist(player.rc, g.hitbox);
                  } catch (Loading var11) {
                     g.name = "?";
                  }

                  if (g.cupboard || g.boulder || g.caveTransition || g.doorGate || g.polyDist <= 16.0 || g.gobDist <= MCache.tilesz.x * 2.0) {
                     out.add(g);
                  }
               }
            }
         }

         out.sort(Comparator.comparingDouble(a -> a.polyDist));
         return out;
      } else {
         return out;
      }
   }

   public static double minPolyDist(Coord2d p, List<Coord2d[]> polygons) {
      if (p != null && polygons != null) {
         double best = Double.POSITIVE_INFINITY;

         for (Coord2d[] poly : polygons) {
            if (poly != null && poly.length >= 2) {
               if (pointInside(p, poly)) {
                  return 0.0;
               }

               best = Math.min(best, edgeDistance(p, poly));
            }
         }

         return best;
      } else {
         return Double.POSITIVE_INFINITY;
      }
   }

   public static List<PrototypePathfinder.NearbyGob> nearby(GameUI gui, String query) {
      List<PrototypePathfinder.NearbyGob> out = new ArrayList<>();
      if (gui != null && gui.map != null && gui.ui != null && gui.ui.sess != null) {
         Gob player = gui.map.player();
         if (player != null && player.rc != null) {
            double reach = maxReach();
            OCache oc = gui.ui.sess.glob.oc;
            synchronized (oc) {
               for (Gob gob : oc) {
                  if (gob != null && gob != player && !gob.virtual && gob.id >= 0L && gob.rc != null) {
                     double dist = gob.rc.dist(player.rc);
                     if (!(dist > reach)) {
                        String name;
                        String resid;
                        try {
                           name = displayName(gob);
                           resid = gob.resid();
                        } catch (Loading var16) {
                           continue;
                        }

                        if (matches(query, gob.id, name, resid)) {
                           out.add(new PrototypePathfinder.NearbyGob(gob.id, name, resid == null ? "" : resid, dist, gob.rc));
                        }
                     }
                  }
               }
            }

            out.sort(Comparator.comparingDouble(g -> g.dist));
            if (out.size() > 200) {
               out = new ArrayList<>(out.subList(0, 200));
            }

            return out;
         } else {
            return out;
         }
      } else {
         return out;
      }
   }

   public static boolean matches(String query, long id, String name, String resid) {
      if (query != null && !query.isEmpty()) {
         String q = query.trim().toLowerCase();
         if (q.isEmpty()) {
            return true;
         } else if (Long.toString(id).contains(q)) {
            return true;
         } else {
            return name != null && name.toLowerCase().contains(q) ? true : resid != null && resid.toLowerCase().contains(q);
         }
      } else {
         return true;
      }
   }

   public static String displayName(Gob gob) {
      if (gob == null) {
         return "???";
      } else {
         try {
            KinInfo kin = gob.kin();
            if (kin != null && kin.name != null && !kin.name.isEmpty()) {
               return kin.name;
            }
         } catch (Loading var4) {
         }

         try {
            String tt = gob.tooltip();
            if (tt != null && !tt.isEmpty() && !"???".equals(tt)) {
               return tt;
            }
         } catch (Loading var3) {
         }

         try {
            String rid = gob.resid();
            if (rid != null) {
               return ClientUtils.prettyResName(rid);
            }
         } catch (Loading var2) {
         }

         return "#" + gob.id;
      }
   }

   public static void go(GameUI gui, Coord2d destination) {
      PrototypePathfinder.Plan plan = plan(gui, destination);
      if (plan.waypoints.size() < 2) {
         Gob player = gui.map == null ? null : gui.map.player();
         if (player != null && player.rc != null && destination != null && player.rc.dist(destination) <= 11.0) {
            gui.msg("Pathfinder: already next to it", MsgType.INFO);
         } else {
            gui.msg("Pathfinder: no route found", MsgType.ERROR);
         }
      } else {
         execute(gui, plan);
         String suffix = plan.status == PrototypePathfinder.Plan.Status.CLIPPED ? " (clipped)" : (plan.complete ? "" : " (partial)");
         gui.msg(
            String.format("Pathfinder: %d waypoints, %d nodes, %d obstacles%s", plan.waypoints.size() - 1, plan.expanded, plan.obstacles, suffix), MsgType.INFO
         );
      }
   }

   public static void goToGob(GameUI gui, long id) {
      if (gui != null && gui.ui != null && gui.ui.sess != null) {
         Gob gob = gui.ui.sess.glob.oc.getgob(id);
         if (gob != null && gob.rc != null) {
            Gob player = gui.map == null ? null : gui.map.player();
            gui.msg("Pathfinder: going to " + displayName(gob), MsgType.INFO);
            PathfinderLog.setTarget(displayName(gob) + " #" + id);

            try {
               Coord2d dest = gob.rc;
               if (player != null && player.rc != null) {
                  dest = approach(player.rc, gob.rc, MCache.tilesz.x * 0.85);
               }

               go(gui, dest);
            } finally {
               PathfinderLog.clearTarget();
            }
         } else {
            gui.msg("Pathfinder: that object is gone", MsgType.ERROR);
         }
      }
   }

   public static void goToNearest(GameUI gui, String query) {
      List<PrototypePathfinder.NearbyGob> hits = nearby(gui, query);
      if (hits.isEmpty()) {
         gui.msg("Pathfinder: no nearby object matches \"" + query + "\"", MsgType.ERROR);
      } else {
         goToGob(gui, hits.get(0).id);
      }
   }

   public static void console(GameUI gui, String[] args) throws Exception {
      if (args.length >= 2 && "catalog".equals(args[1])) {
         if (args.length >= 3 && "log".equals(args[2])) {
            CatalogDebug.printLog(gui.ui.cons.out);
            gui.msg("Catalog debug: " + CatalogDebug.logFile(), MsgType.INFO);
         } else if (args.length >= 3 && "cancel".equals(args[2])) {
            Bot.cancelCurrent();
            gui.msg("Catalog cancelled", MsgType.INFO);
         } else {
            CupboardBot.start(gui);
         }
      } else if (args.length >= 2 && "debug".equals(args[1])) {
         boolean on = args.length >= 3 ? parseBool(args[2], (Boolean)CFG.DEBUG_PATHFIND.get()) : !Boolean.TRUE.equals(CFG.DEBUG_PATHFIND.get());
         CFG.DEBUG_PATHFIND.set(on);
         gui.msg("Pathfinder debug " + (on ? "ON (cyan route overlay)" : "off"), MsgType.INFO);
      } else if (args.length >= 2 && "log".equals(args[1])) {
         PathfinderDebug.printLog(gui.ui.cons.out);
         gui.msg("PF log: " + PathfinderLog.logFile(), MsgType.INFO);
      } else if (args.length >= 2 && "stuck".equals(args[1])) {
         PathfinderDebug.dumpStuck(gui, gui.ui.cons.out);
      } else if (args.length >= 2 && "probe".equals(args[1])) {
         MechanicsProbe.console(gui, args);
      } else if (args.length >= 2 && "gob".equals(args[1])) {
         if (args.length == 2) {
            gui.togglePathfinder();
         } else {
            String rest = join(args, 2);

            try {
               goToGob(gui, Long.parseLong(rest.trim()));
            } catch (NumberFormatException var5) {
               goToNearest(gui, rest);
            }
         }
      } else if (args.length != 3 && args.length != 4) {
         throw new Exception(
            "Usage: pf [rel] <x> <y>  |  pf gob [id|name]  |  pf catalog [log|cancel]  |  pf probe [room|click|step|last]  |  pf debug  |  pf log  |  pf stuck"
         );
      } else {
         int off = args.length == 4 ? 2 : 1;
         Coord2d destination = Coord2d.of(Double.parseDouble(args[off]), Double.parseDouble(args[off + 1]));
         Gob player = gui.map == null ? null : gui.map.player();
         if (player == null) {
            throw new Exception("Player is not available");
         } else {
            if (args.length == 4) {
               if (!"rel".equals(args[1])) {
                  throw new Exception("Usage: pf [rel] <x> <y>  |  pf gob [id|name]");
               }

               destination = player.rc.add(destination);
            }

            go(gui, destination);
         }
      }
   }

   private static String join(String[] args, int from) {
      StringBuilder sb = new StringBuilder();

      for (int i = from; i < args.length; i++) {
         if (i > from) {
            sb.append(' ');
         }

         sb.append(args[i]);
      }

      return sb.toString();
   }

   private static Coord clamp(Coord c, int w, int h) {
      return Coord.of(Math.max(0, Math.min(w - 1, c.x)), Math.max(0, Math.min(h - 1, c.y)));
   }

   public static void openFootprint(boolean[] blocked, int w, int h, int sx, int sy, int radius, boolean[] solid) {
      if (blocked != null && radius >= 0 && w > 0 && h > 0) {
         int r2 = radius * radius;

         for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
               if (dx * dx + dy * dy <= r2) {
                  int x = sx + dx;
                  int y = sy + dy;
                  if (x >= 0 && y >= 0 && x < w && y < h && (solid == null || !solid[y * w + x])) {
                     blocked[y * w + x] = false;
                  }
               }
            }
         }
      }
   }

   public static void openFootprint(boolean[] blocked, int w, int h, int sx, int sy, int radius) {
      openFootprint(blocked, w, h, sx, sy, radius, null);
   }

   public static void openStartPocket(boolean[] blocked, int w, int h, int sx, int sy, int radius) {
      if (blocked != null && radius >= 0 && w > 0 && h > 0) {
         int r2 = radius * radius;

         for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
               if (dx * dx + dy * dy <= r2) {
                  int x = sx + dx;
                  int y = sy + dy;
                  if (x >= 0 && y >= 0 && x < w && y < h) {
                     blocked[y * w + x] = false;
                  }
               }
            }
         }
      }
   }

   public static Coord nearestFree(boolean[] blocked, int w, int h, Coord goal, Coord from) {
      if (blocked != null && goal != null) {
         if (goal.x >= 0 && goal.y >= 0 && goal.x < w && goal.y < h) {
            if (!blocked[goal.y * w + goal.x]) {
               return goal;
            } else {
               Coord origin = from == null ? goal : from;

               for (int radius = 1; radius < Math.max(w, h); radius++) {
                  Coord best = null;
                  double bestD = Double.POSITIVE_INFINITY;

                  for (int y = goal.y - radius; y <= goal.y + radius; y++) {
                     for (int x = goal.x - radius; x <= goal.x + radius; x++) {
                        if (Math.max(Math.abs(x - goal.x), Math.abs(y - goal.y)) == radius && x >= 0 && y >= 0 && x < w && y < h && !blocked[y * w + x]) {
                           double d = (double)((x - origin.x) * (x - origin.x) + (y - origin.y) * (y - origin.y));
                           if (d < bestD) {
                              bestD = d;
                              best = Coord.of(x, y);
                           }
                        }
                     }
                  }

                  if (best != null) {
                     if (radius == 1) {
                        return standoff(blocked, w, h, goal, best);
                     }

                     return best;
                  }
               }

               return null;
            }
         } else {
            return null;
         }
      } else {
         return null;
      }
   }

   private static Coord standoff(boolean[] blocked, int w, int h, Coord goal, Coord at) {
      int sx = Integer.signum(at.x - goal.x);
      int sy = Integer.signum(at.y - goal.y);
      int nx = at.x + sx;
      int ny = at.y + sy;
      if (nx >= 0 && ny >= 0 && nx < w && ny < h && !blocked[ny * w + nx]) {
         return Coord.of(nx, ny);
      } else {
         if (sx != 0) {
            nx = at.x + sx;
            ny = at.y;
            if (nx >= 0 && ny >= 0 && nx < w && ny < h && !blocked[ny * w + nx]) {
               return Coord.of(nx, ny);
            }
         }

         if (sy != 0) {
            nx = at.x;
            ny = at.y + sy;
            if (nx >= 0 && ny >= 0 && nx < w && ny < h && !blocked[ny * w + nx]) {
               return Coord.of(nx, ny);
            }
         }

         return at;
      }
   }

   private static boolean[] rasterTerrain(GameUI gui, PrototypePathfinder.Grid grid) {
      return rasterTerrain(gui, grid, null);
   }

   private static boolean[] rasterTerrain(GameUI gui, PrototypePathfinder.Grid grid, String[] names) {
      boolean[] mask = new boolean[grid.blocked.length];

      for (int y = 0; y < grid.h; y++) {
         for (int x = 0; x < grid.w; x++) {
            Coord2d wc = grid.world(Coord.of(x, y));
            String name = null;

            try {
               Resource res = gui.ui.sess.glob.map.tilesetr(gui.ui.sess.glob.map.gettile(wc.floor(MCache.tilesz)));
               name = res == null ? "" : res.name;
               if (TerrainPolicy.terrainBlocks(name)) {
                  grid.block(x, y);
                  mask[y * grid.w + x] = true;
               }
            } catch (Loading var9) {
               name = "loading";
               grid.block(x, y);
               mask[y * grid.w + x] = true;
            }

            if (names != null) {
               names[y * grid.w + x] = name;
            }
         }
      }

      return mask;
   }

   private static int rasterGobs(GameUI gui, PrototypePathfinder.Grid grid, Gob player, List<Coord2d[]> debugPolys, List<Coord2d[]> body) {
      boolean inflate = body != null && !body.isEmpty();
      int count = 0;
      synchronized (gui.ui.sess.glob.oc) {
         for (Gob gob : gui.ui.sess.glob.oc) {
            if (gob != null && gob != player && gob.id >= 0L && gob.rc != null) {
               try {
                  if (!Hitbox.passable(gob)) {
                     String resid = gob.resid();
                     if (!inflate || !furnitureFootprint(resid)) {
                        List<Coord2d[]> polygons = collisionPolygons(gob);
                        double disk = solidFootprint(resid) ? MCache.tilesz.x * 3.0 : MCache.tilesz.x * 0.6;
                        if (rasterObstacle(grid, gob.rc, inflate, resid, polygons, disk, body)) {
                           count++;
                        }

                        if (!inflate && debugPolys != null && debugPolys.size() < 48 && polygons != null && !polygons.isEmpty()) {
                           if (!solidFootprint(resid) && !isHollowRing(polygons)) {
                              debugPolys.addAll(polygons);
                           } else {
                              debugPolys.add(aabbPolygon(polygons, OVERLAP));
                           }
                        }
                     }
                  }
               } catch (Loading var16) {
                  String resid = null;

                  try {
                     resid = gob.resid();
                  } catch (Loading var15) {
                  }

                  double diskx = solidFootprint(resid) ? MCache.tilesz.x * 3.0 : MCache.tilesz.x;
                  if (rasterObstacle(grid, gob.rc, inflate, resid, null, diskx, body)) {
                     count++;
                  }
               }
            }
         }

         return count;
      }
   }

   static List<Coord2d> movingHazards(GameUI gui, Gob player) {
      List<Coord2d> out = new ArrayList<>();
      if (gui != null && gui.ui != null && gui.ui.sess != null) {
         synchronized (gui.ui.sess.glob.oc) {
            for (Gob gob : gui.ui.sess.glob.oc) {
               if (gob != null && gob != player && gob.id >= 0L && gob.rc != null) {
                  try {
                     if (gob.getattr(Moving.class) != null) {
                        out.add(gob.rc);
                     }
                  } catch (Loading var4) {
                  }
               }
            }
         }
      }
      return out;
   }

   public static boolean rasterObstacle(
      boolean[] blocked,
      Coord2d origin,
      int w,
      int h,
      Coord2d center,
      boolean inflate,
      String resid,
      List<Coord2d[]> polygons,
      double diskFallback,
      List<Coord2d[]> body
   ) {
      PrototypePathfinder.Grid grid = new PrototypePathfinder.Grid(origin, w, h);
      int n = Math.min(blocked.length, grid.blocked.length);
      System.arraycopy(blocked, 0, grid.blocked, 0, n);
      boolean out = rasterObstacle(grid, center, inflate, resid, polygons, diskFallback, body);
      System.arraycopy(grid.blocked, 0, blocked, 0, n);
      return out;
   }

   private static boolean rasterObstacle(
      PrototypePathfinder.Grid grid, Coord2d center, boolean inflate, String resid, List<Coord2d[]> polygons, double diskFallback, List<Coord2d[]> body
   ) {
      if (inflate && furnitureFootprint(resid)) {
         return false;
      } else if (polygons != null && !polygons.isEmpty()) {
         if (inflate) {
            if (!solidFootprint(resid) && !isHollowRing(polygons)) {
               for (Coord2d[] polygon : polygons) {
                  rasterPolygon(grid, polygon, body);
               }
            } else {
               rasterAabb(grid, polygons, body);
            }

            return true;
         } else {
            if (!solidFootprint(resid) && !isHollowRing(polygons)) {
               for (Coord2d[] polygon : polygons) {
                  rasterPolygon(grid, polygon, OVERLAP);
               }
            } else {
               rasterAabb(grid, polygons, OVERLAP);
            }

            return true;
         }
      } else if (polygons != null && polygons.isEmpty() && furnitureFootprint(resid)) {
         return false;
      } else {
         double disk = diskFallback;
         if (inflate) {
            disk = diskFallback + boundingRadius(Coord2d.of(0.0, 0.0), body);
         }

         rasterDisk(grid, center, disk);
         return true;
      }
   }

   static void inflateMasked(PrototypePathfinder.Grid grid, boolean[] mask, List<Coord2d[]> body) {
      if (grid != null && mask != null && body != null && !body.isEmpty()) {
         int w = grid.w;
         int h = grid.h;

         for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
               if (y * w + x < mask.length && mask[y * w + x]) {
                  Coord2d[] sq = new Coord2d[]{
                     grid.origin.add((double)x * 2.75, (double)y * 2.75),
                     grid.origin.add((double)(x + 1) * 2.75, (double)y * 2.75),
                     grid.origin.add((double)(x + 1) * 2.75, (double)(y + 1) * 2.75),
                     grid.origin.add((double)x * 2.75, (double)(y + 1) * 2.75)
                  };
                  rasterPolygon(grid, sq, body);
               }
            }
         }
      }
   }

   private static void rasterAabb(PrototypePathfinder.Grid grid, List<Coord2d[]> polygons, List<Coord2d[]> body) {
      double[] box = aabb(polygons);
      if (box != null) {
         Coord2d[] rect = new Coord2d[]{Coord2d.of(box[0], box[1]), Coord2d.of(box[2], box[1]), Coord2d.of(box[2], box[3]), Coord2d.of(box[0], box[3])};
         rasterAabb(grid, polygons, OVERLAP);
         rasterPolygon(grid, rect, body);
      }
   }

   private static List<Coord2d[]> collisionPolygons(Gob gob) {
      String resid = gob.resid();
      if (furnitureFootprint(resid)) {
         return Hitbox.obstaclePolygons(gob);
      } else {
         List<Coord2d[]> polygons = Hitbox.movementPolygons(gob);
         if (solidFootprint(resid)) {
            Coord2d[] known = knownBuildingFootprint(resid, gob.rc, gob.a);
            if (known != null) {
               polygons = new ArrayList<>(polygons);
               polygons.add(known);
            }
         }

         return polygons;
      }
   }

   public static String baseResid(String resid) {
      if (resid != null && !resid.isEmpty()) {
         int bracket = resid.indexOf(91);
         return bracket > 0 ? resid.substring(0, bracket) : resid;
      } else {
         return resid;
      }
   }

   public static boolean solidFootprint(String resid) {
      String name = baseResid(resid);
      return name == null ? false : BUILDING_HALF.containsKey(name);
   }

   public static boolean furnitureFootprint(String resid) {
      String name = baseResid(resid);
      return name != null && FURNITURE_HALF.containsKey(name);
   }

   public static boolean wallClearance(String resid) {
      String name = baseResid(resid);
      if (name == null) {
         return false;
      } else if (!name.startsWith("gfx/terobjs/arch/")) {
         return false;
      } else if (name.contains("palisade")) {
         return true;
      } else if (name.contains("brickwall") || name.contains("brickbig")) {
         return true;
      } else if (name.contains("drystone")) {
         return true;
      } else {
         return !name.contains("poleseg") && !name.contains("polecp") && !name.contains("polegate") && !name.contains("polebig")
            ? name.endsWith("seg") || name.endsWith("cp")
            : true;
      }
   }

   public static Coord2d[] knownBuildingFootprint(String resid, Coord2d rc, double a) {
      if (rc == null) {
         return null;
      } else {
         Coord2d half = BUILDING_HALF.get(baseResid(resid));
         if (half == null && solidFootprint(resid)) {
            half = Coord2d.of(40.0, 30.0);
         }

         if (half == null) {
            return null;
         } else {
            double cs = Math.cos(a);
            double sn = Math.sin(a);
            Coord2d[] local = new Coord2d[]{Coord2d.of(-half.x, -half.y), Coord2d.of(half.x, -half.y), Coord2d.of(half.x, half.y), Coord2d.of(-half.x, half.y)};
            Coord2d[] world = new Coord2d[4];

            for (int i = 0; i < 4; i++) {
               Coord2d p = local[i];
               world[i] = Coord2d.of(rc.x + p.x * cs - p.y * sn, rc.y + p.x * sn + p.y * cs);
            }

            return world;
         }
      }
   }

   public static Coord2d[] knownFurnitureFootprint(String resid, Coord2d rc, double a) {
      if (rc == null) {
         return null;
      } else {
         Coord2d half = FURNITURE_HALF.get(baseResid(resid));
         if (half == null) {
            return null;
         } else {
            double cs = Math.cos(a);
            double sn = Math.sin(a);
            Coord2d[] local = new Coord2d[]{Coord2d.of(-half.x, -half.y), Coord2d.of(half.x, -half.y), Coord2d.of(half.x, half.y), Coord2d.of(-half.x, half.y)};
            Coord2d[] world = new Coord2d[4];

            for (int i = 0; i < 4; i++) {
               Coord2d p = local[i];
               world[i] = Coord2d.of(rc.x + p.x * cs - p.y * sn, rc.y + p.x * sn + p.y * cs);
            }

            return world;
         }
      }
   }

   public static boolean isHollowRing(List<Coord2d[]> polygons) {
      if (polygons != null && polygons.size() >= 2) {
         double[] union = aabb(polygons);
         if (union == null) {
            return false;
         } else {
            double unionArea = (union[2] - union[0]) * (union[3] - union[1]);
            double largest = 0.0;

            for (Coord2d[] polygon : polygons) {
               double[] box = aabb(Collections.singletonList(polygon));
               if (box != null) {
                  largest = Math.max(largest, (box[2] - box[0]) * (box[3] - box[1]));
               }
            }

            return unionArea > 4.0 * Math.max(largest, 1.0);
         }
      } else {
         return false;
      }
   }

   private static double[] aabb(List<Coord2d[]> polygons) {
      double minx = Double.POSITIVE_INFINITY;
      double miny = Double.POSITIVE_INFINITY;
      double maxx = Double.NEGATIVE_INFINITY;
      double maxy = Double.NEGATIVE_INFINITY;
      boolean any = false;
      if (polygons == null) {
         return null;
      } else {
         for (Coord2d[] polygon : polygons) {
            if (polygon != null) {
               for (Coord2d p : polygon) {
                  if (p != null) {
                     any = true;
                     minx = Math.min(minx, p.x);
                     miny = Math.min(miny, p.y);
                     maxx = Math.max(maxx, p.x);
                     maxy = Math.max(maxy, p.y);
                  }
               }
            }
         }

         return any ? new double[]{minx, miny, maxx, maxy} : null;
      }
   }

   private static Coord2d[] aabbPolygon(List<Coord2d[]> polygons) {
      return aabbPolygon(polygons, 0.0);
   }

   private static Coord2d[] aabbPolygon(List<Coord2d[]> polygons, double pad) {
      double[] box = aabb(polygons);
      return box == null
         ? new Coord2d[0]
         : new Coord2d[]{
            Coord2d.of(box[0] - pad, box[1] - pad),
            Coord2d.of(box[2] + pad, box[1] - pad),
            Coord2d.of(box[2] + pad, box[3] + pad),
            Coord2d.of(box[0] - pad, box[3] + pad)
         };
   }

   private static void rasterAabb(PrototypePathfinder.Grid grid, List<Coord2d[]> polygons) {
      rasterAabb(grid, polygons, OVERLAP);
   }

   private static void rasterAabb(PrototypePathfinder.Grid grid, List<Coord2d[]> polygons, double pad) {
      double[] box = aabb(polygons);
      if (box != null) {
         Coord lo = grid.cell(Coord2d.of(box[0] - pad, box[1] - pad));
         Coord hi = grid.cell(Coord2d.of(box[2] + pad, box[3] + pad));

         for (int y = Math.max(0, lo.y); y <= Math.min(grid.h - 1, hi.y); y++) {
            for (int x = Math.max(0, lo.x); x <= Math.min(grid.w - 1, hi.x); x++) {
               grid.block(x, y);
            }
         }
      }
   }

   private static void rasterDisk(PrototypePathfinder.Grid grid, Coord2d center, double radius) {
      if (center != null && !(radius <= 0.0)) {
         Coord lo = grid.cell(Coord2d.of(center.x - radius, center.y - radius));
         Coord hi = grid.cell(Coord2d.of(center.x + radius, center.y + radius));
         double r2 = radius * radius;

         for (int y = Math.max(0, lo.y); y <= Math.min(grid.h - 1, hi.y); y++) {
            for (int x = Math.max(0, lo.x); x <= Math.min(grid.w - 1, hi.x); x++) {
               Coord2d at = grid.world(Coord.of(x, y));
               if ((at.x - center.x) * (at.x - center.x) + (at.y - center.y) * (at.y - center.y) <= r2) {
                  grid.block(x, y);
               }
            }
         }
      }
   }

   public static double boundingRadius(Coord2d origin, List<Coord2d[]> polygons) {
      double best = 0.0;
      if (origin != null && polygons != null) {
         for (Coord2d[] polygon : polygons) {
            if (polygon != null) {
               for (Coord2d p : polygon) {
                  if (p != null) {
                     best = Math.max(best, p.dist(origin));
                  }
               }
            }
         }

         return best;
      } else {
         return 0.0;
      }
   }

   public static Coord worldCell(Coord2d origin, Coord2d p) {
      return origin != null && p != null ? Coord.of((int)Math.floor((p.x - origin.x) / 2.75), (int)Math.floor((p.y - origin.y) / 2.75)) : Coord.z;
   }

   public static int dilationCells(double agentRadius) {
      return Math.max(1, (int)Math.ceil(Math.max(agentRadius, 0.0) / 2.75));
   }

   public static void applyClearanceCost(double[] cost, boolean[] solid, int w, int h) {
      if (cost != null && solid != null && w > 0 && h > 0) {
         int n = w * h;
         Arrays.fill(cost, 1.0);
         int[] dist = new int[n];
         Arrays.fill(dist, 536870911);
         ArrayDeque<Integer> q = new ArrayDeque<>();

         for (int i = 0; i < n && i < solid.length; i++) {
            if (solid[i]) {
               dist[i] = 0;
               q.add(i);
            }
         }

         int[] dx = new int[]{1, 1, 0, -1, -1, -1, 0, 1};
         int[] dy = new int[]{0, 1, 1, 1, 0, -1, -1, -1};

         while (!q.isEmpty()) {
            int cur = q.poll();
            int x = cur % w;
            int y = cur / w;
            int nd = dist[cur] + 1;

            for (int d = 0; d < dx.length; d++) {
               int nx = x + dx[d];
               int ny = y + dy[d];
               if (nx >= 0 && ny >= 0 && nx < w && ny < h) {
                  int ni = ny * w + nx;
                  if (nd < dist[ni]) {
                     dist[ni] = nd;
                     q.add(ni);
                  }
               }
            }
         }

         int lim = Math.min(n, Math.min(cost.length, dist.length));

         for (int ix = 0; ix < lim; ix++) {
            if (ix >= solid.length || !solid[ix]) {
               if (dist[ix] <= 1) {
                  cost[ix] = 4.5;
               } else if (dist[ix] == 2) {
                  cost[ix] = 1.8;
               }
            }
         }
      }
   }

   public static Coord2d approach(Coord2d from, Coord2d dest, double dist) {
      if (dest == null) {
         return from;
      } else if (from == null) {
         return dest;
      } else {
         double d = from.dist(dest);
         return !(d < 1.0E-6) && !(d <= dist) ? dest.add(from.sub(dest).norm().mul(dist)) : from;
      }
   }

   public static void dilate(boolean[] blocked, int w, int h, int radiusCells) {
      if (blocked != null && radiusCells > 0 && w > 0 && h > 0) {
         boolean[] src = Arrays.copyOf(blocked, blocked.length);

         for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
               if (src[y * w + x]) {
                  for (int dy = -radiusCells; dy <= radiusCells; dy++) {
                     for (int dx = -radiusCells; dx <= radiusCells; dx++) {
                        if (Math.max(Math.abs(dx), Math.abs(dy)) <= radiusCells) {
                           int nx = x + dx;
                           int ny = y + dy;
                           if (nx >= 0 && ny >= 0 && nx < w && ny < h) {
                              blocked[ny * w + nx] = true;
                           }
                        }
                     }
                  }
               }
            }
         }
      }
   }

   public static double agentRadius(Gob player) {
      try {
         double r = boundingRadius(player.rc, Hitbox.worldPolygons(player));
         if (r > 0.5) {
            return r;
         }
      } catch (Loading var3) {
      }

      return 4.5;
   }

   public static void rasterPolygon(boolean[] blocked, Coord2d origin, int w, int h, Coord2d[] polygon, double pad) {
      if (blocked != null && origin != null && w > 0 && h > 0) {
         PrototypePathfinder.Grid grid = new PrototypePathfinder.Grid(origin, w, h);
         int n = Math.min(blocked.length, grid.blocked.length);
         System.arraycopy(blocked, 0, grid.blocked, 0, n);
         rasterPolygon(grid, polygon, pad);
         System.arraycopy(grid.blocked, 0, blocked, 0, n);
      }
   }

   public static void rasterPolygon(boolean[] blocked, Coord2d origin, int w, int h, Coord2d[] polygon, List<Coord2d[]> body) {
      if (blocked != null && origin != null && w > 0 && h > 0) {
         PrototypePathfinder.Grid grid = new PrototypePathfinder.Grid(origin, w, h);
         int n = Math.min(blocked.length, grid.blocked.length);
         System.arraycopy(blocked, 0, grid.blocked, 0, n);
         rasterPolygon(grid, polygon, body);
         System.arraycopy(grid.blocked, 0, blocked, 0, n);
      }
   }

   private static void rasterPolygon(PrototypePathfinder.Grid grid, Coord2d[] polygon, double pad) {
      if (polygon != null && polygon.length >= 2) {
         double minx = Double.POSITIVE_INFINITY;
         double miny = Double.POSITIVE_INFINITY;
         double maxx = Double.NEGATIVE_INFINITY;
         double maxy = Double.NEGATIVE_INFINITY;

         for (Coord2d p : polygon) {
            minx = Math.min(minx, p.x);
            miny = Math.min(miny, p.y);
            maxx = Math.max(maxx, p.x);
            maxy = Math.max(maxy, p.y);
         }

         Coord lo = grid.cell(Coord2d.of(minx - pad, miny - pad));
         Coord hi = grid.cell(Coord2d.of(maxx + pad, maxy + pad));

         for (int y = Math.max(0, lo.y); y <= Math.min(grid.h - 1, hi.y); y++) {
            for (int x = Math.max(0, lo.x); x <= Math.min(grid.w - 1, hi.x); x++) {
               Coord2d p = grid.world(Coord.of(x, y));
               if (pointInside(p, polygon) || edgeDistance(p, polygon) <= pad) {
                  grid.block(x, y);
               }
            }
         }
      }
   }

   private static void rasterPolygon(PrototypePathfinder.Grid grid, Coord2d[] polygon, List<Coord2d[]> body) {
      if (polygon != null && polygon.length >= 2 && body != null && !body.isEmpty()) {
         double hx = 0.0;
         double hy = 0.0;

         for (Coord2d[] b : body) {
            if (b != null) {
               for (Coord2d p : b) {
                  if (p != null) {
                     hx = Math.max(hx, Math.abs(p.x));
                     hy = Math.max(hy, Math.abs(p.y));
                  }
               }
            }
         }

         double minx = Double.POSITIVE_INFINITY;
         double miny = Double.POSITIVE_INFINITY;
         double maxx = Double.NEGATIVE_INFINITY;
         double maxy = Double.NEGATIVE_INFINITY;

         for (Coord2d px : polygon) {
            minx = Math.min(minx, px.x);
            miny = Math.min(miny, px.y);
            maxx = Math.max(maxx, px.x);
            maxy = Math.max(maxy, px.y);
         }

         Coord lo = grid.cell(Coord2d.of(minx - hx, miny - hy));
         Coord hi = grid.cell(Coord2d.of(maxx + hx, maxy + hy));

         for (int y = Math.max(0, lo.y); y <= Math.min(grid.h - 1, hi.y); y++) {
            for (int x = Math.max(0, lo.x); x <= Math.min(grid.w - 1, hi.x); x++) {
               if (bodyHits(grid.world(Coord.of(x, y)), body, polygon)) {
                  grid.block(x, y);
               }
            }
         }
      }
   }

   public static List<Coord2d[]> playerBodyOrigin(Gob player) {
      if (player != null && player.rc != null) {
         List<Coord2d[]> world;
         try {
            world = Hitbox.worldPolygons(player);
         } catch (Loading var7) {
            return Collections.emptyList();
         }

         List<Coord2d[]> out = new ArrayList<>();

         for (Coord2d[] poly : world) {
            if (poly != null && poly.length >= 2) {
               Coord2d[] rel = new Coord2d[poly.length];

               for (int i = 0; i < poly.length; i++) {
                  rel[i] = poly[i] == null ? Coord2d.of(0.0, 0.0) : poly[i].sub(player.rc);
               }

               out.add(rel);
            }
         }

         return out;
      } else {
         return Collections.emptyList();
      }
   }

   public static boolean bodyHits(Coord2d at, List<Coord2d[]> body, Coord2d[] obstacle) {
      if (at != null && body != null && obstacle != null && obstacle.length >= 2) {
         for (Coord2d[] b : body) {
            if (b != null && b.length >= 2) {
               Coord2d[] placed = new Coord2d[b.length];

               for (int i = 0; i < b.length; i++) {
                  placed[i] = at.add(b[i] == null ? Coord2d.of(0.0, 0.0) : b[i]);
               }

               if (polygonsOverlap(placed, obstacle)) {
                  return true;
               }
            }
         }

         return false;
      } else {
         return false;
      }
   }

   public static boolean polygonsOverlap(Coord2d[] a, Coord2d[] b) {
      if (a != null && b != null && a.length >= 2 && b.length >= 2) {
         for (Coord2d p : a) {
            if (p != null && (pointInside(p, b) || edgeDistance(p, b) <= 0.05)) {
               return true;
            }
         }

         for (Coord2d px : b) {
            if (px != null && (pointInside(px, a) || edgeDistance(px, a) <= 0.05)) {
               return true;
            }
         }

         for (int i = 0; i < a.length; i++) {
            Coord2d a0 = a[i];
            Coord2d a1 = a[(i + 1) % a.length];

            for (int j = 0; j < b.length; j++) {
               if (segmentsCross(a0, a1, b[j], b[(j + 1) % b.length])) {
                  return true;
               }
            }
         }

         return false;
      } else {
         return false;
      }
   }

   public static boolean pointInside(Coord2d p, Coord2d[] poly) {
      boolean in = false;
      int i = 0;

      for (int j = poly.length - 1; i < poly.length; j = i++) {
         Coord2d a = poly[i];
         Coord2d b = poly[j];
         if (a.y > p.y != b.y > p.y && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) {
            in = !in;
         }
      }

      return in;
   }

   public static double edgeDistance(Coord2d p, Coord2d[] poly) {
      double best = Double.POSITIVE_INFINITY;

      for (int i = 0; i < poly.length; i++) {
         Coord2d a = poly[i];
         Coord2d b = poly[(i + 1) % poly.length];
         Coord2d ab = b.sub(a);
         Coord2d ap = p.sub(a);
         double d2 = ab.x * ab.x + ab.y * ab.y;
         double t = d2 == 0.0 ? 0.0 : Math.max(0.0, Math.min(1.0, (ap.x * ab.x + ap.y * ab.y) / d2));
         best = Math.min(best, p.dist(a.add(ab.mul(t))));
      }

      return best;
   }

   private static boolean parseBool(String s, Boolean current) {
      String t = s.trim().toLowerCase();
      switch (t) {
         case "true":
         case "on":
         case "1":
         case "yes":
            return true;
         case "false":
         case "off":
         case "0":
         case "no":
            return false;
         case "toggle":
         case "t":
            return current == null || !current;
         default:
            return current == null || !current;
      }
   }

   private static List<String> nearBlockers(GameUI gui, Gob player, Coord2d start) {
      List<String> out = new ArrayList<>();
      if (gui != null && gui.ui != null && gui.ui.sess != null) {
         try {
            synchronized (gui.ui.sess.glob.oc) {
               for (Gob gob : gui.ui.sess.glob.oc) {
                  if (gob != null && gob != player && !gob.virtual && gob.id >= 0L && gob.rc != null) {
                     try {
                        if (!Hitbox.passable(gob)) {
                           List<Coord2d[]> polys = collisionPolygons(gob);
                           if (polys != null && !polys.isEmpty()) {
                              double best = Double.POSITIVE_INFINITY;
                              Iterator var10 = polys.iterator();

                              while (true) {
                                 if (var10.hasNext()) {
                                    Coord2d[] poly = (Coord2d[])var10.next();
                                    if (poly == null || poly.length < 2) {
                                       continue;
                                    }

                                    if (!pointInside(start, poly)) {
                                       best = Math.min(best, edgeDistance(start, poly));
                                       continue;
                                    }

                                    best = 0.0;
                                 }

                                 if (!(best > 16.0)) {
                                    out.add(String.format("%s %.1ft", displayName(gob), best / MCache.tilesz.x));
                                    if (out.size() >= 6) {
                                       return out;
                                    }
                                 }
                                 break;
                              }
                           }
                        }
                     } catch (Loading var13) {
                     }
                  }
               }
            }
         } catch (Loading var15) {
         }

         return out;
      } else {
         return out;
      }
   }

   private static String clipAlong(GameUI gui, Gob player, List<Coord2d> path) {
      if (gui != null && gui.ui != null && path != null && path.size() >= 2) {
         try {
            synchronized (gui.ui.sess.glob.oc) {
               for (Gob gob : gui.ui.sess.glob.oc) {
                  if (gob != null && gob != player && !gob.virtual && gob.id >= 0L && gob.rc != null) {
                     try {
                        if (!Hitbox.passable(gob)) {
                           List<Coord2d[]> polys = collisionPolygons(gob);
                           if (solidFootprint(gob.resid()) || isHollowRing(polys) || furnitureFootprint(gob.resid())) {
                              double pad = solidFootprint(gob.resid()) ? 2.0 : 1.0;
                              Coord2d[] box = aabbPolygon(polys, pad);

                              for (int i = 0; i < path.size() - 1; i++) {
                                 if (segmentHitsPolygon(path.get(i), path.get(i + 1), box, 1.0)) {
                                    return displayName(gob);
                                 }
                              }
                           } else {
                              for (Coord2d[] poly : polys) {
                                 if (poly != null && poly.length >= 2) {
                                    double clipPad = wallClearance(gob.resid()) ? 2.0 : 1.0;

                                    for (int ix = 0; ix < path.size() - 1; ix++) {
                                       if (segmentHitsPolygon(path.get(ix), path.get(ix + 1), poly, clipPad)) {
                                          return displayName(gob);
                                       }
                                    }
                                 }
                              }
                           }
                        }
                     } catch (Loading var13) {
                     }
                  }
               }
            }
         } catch (Loading var15) {
         }

         return "";
      } else {
         return "";
      }
   }

   private static List<Coord2d> smooth(List<Coord2d> raw, PrototypePathfinder.Grid grid, boolean[] los) {
      if (raw.size() < 3) {
         return raw;
      } else {
         List<Coord2d> ret = new ArrayList<>();
         int at = 0;
         ret.add(raw.get(0));

         while (at < raw.size() - 1) {
            int next = raw.size() - 1;

            while (next > at + 1 && !canSmooth(raw.get(at), raw.get(next), grid, los)) {
               next--;
            }

            ret.add(raw.get(next));
            at = next;
         }

         return ret;
      }
   }

   private static boolean canSmooth(Coord2d a, Coord2d b, PrototypePathfinder.Grid grid, boolean[] los) {
      if (!clear(a, b, grid, los)) {
         return false;
      } else {
         Coord ca = grid.cell(a);
         Coord cb = grid.cell(b);
         return !besideLos(grid, los, ca.x, ca.y) && !besideLos(grid, los, cb.x, cb.y) ? true : ca.x == cb.x || ca.y == cb.y;
      }
   }

   private static boolean besideLos(PrototypePathfinder.Grid grid, boolean[] blocked, int x, int y) {
      if (blocked == null) {
         return false;
      } else {
         for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
               if (dx != 0 || dy != 0) {
                  int nx = x + dx;
                  int ny = y + dy;
                  if (nx >= 0 && ny >= 0 && nx < grid.w && ny < grid.h && blocked[ny * grid.w + nx]) {
                     return true;
                  }
               }
            }
         }

         return false;
      }
   }

   private static boolean segmentHitsPolygon(Coord2d a, Coord2d b, Coord2d[] poly, double pad) {
      if (a == null || b == null || poly == null || poly.length < 2) {
         return false;
      } else if (!pointInside(a, poly) && !pointInside(b, poly)) {
         if (!(edgeDistance(a, poly) <= pad) && !(edgeDistance(b, poly) <= pad)) {
            for (int i = 0; i < poly.length; i++) {
               Coord2d c = poly[i];
               Coord2d d = poly[(i + 1) % poly.length];
               if (segmentsCross(a, b, c, d)) {
                  return true;
               }
            }

            return false;
         } else {
            return true;
         }
      } else {
         return true;
      }
   }

   private static boolean segmentsCross(Coord2d a, Coord2d b, Coord2d c, Coord2d d) {
      double abx = b.x - a.x;
      double aby = b.y - a.y;
      double acx = c.x - a.x;
      double acy = c.y - a.y;
      double adx = d.x - a.x;
      double ady = d.y - a.y;
      double cdx = d.x - c.x;
      double cdy = d.y - c.y;
      double cax = a.x - c.x;
      double cay = a.y - c.y;
      double cbx = b.x - c.x;
      double cby = b.y - c.y;
      double abac = abx * acy - aby * acx;
      double abad = abx * ady - aby * adx;
      double cdca = cdx * cay - cdy * cax;
      double cdcb = cdx * cby - cdy * cbx;
      return abac > 0.0 != abad > 0.0 && cdca > 0.0 != cdcb > 0.0;
   }

   public static boolean segmentHitsSquare(Coord2d a, Coord2d b, double minx, double miny, double maxx, double maxy) {
      if (a != null && b != null) {
         double x0 = a.x;
         double y0 = a.y;
         double x1 = b.x;
         double y1 = b.y;
         double dx = x1 - x0;
         double dy = y1 - y0;
         double t0 = 0.0;
         double t1 = 1.0;
         double[] p = new double[]{-dx, dx, -dy, dy};
         double[] q = new double[]{x0 - minx, maxx - x0, y0 - miny, maxy - y0};

         for (int i = 0; i < 4; i++) {
            if (p[i] == 0.0) {
               if (q[i] < 0.0) {
                  return false;
               }
            } else {
               double r = q[i] / p[i];
               if (p[i] < 0.0) {
                  if (r > t1) {
                     return false;
                  }

                  if (r > t0) {
                     t0 = r;
                  }
               } else {
                  if (r < t0) {
                     return false;
                  }

                  if (r < t1) {
                     t1 = r;
                  }
               }
            }
         }

         return t0 <= t1;
      } else {
         return false;
      }
   }

   public static boolean lineOfSightClear(Coord2d origin, int w, int h, boolean[] blocked, Coord2d a, Coord2d b) {
      return clear(a, b, new PrototypePathfinder.Grid(origin, w, h), blocked);
   }

   private static boolean clear(Coord2d a, Coord2d b, PrototypePathfinder.Grid grid, boolean[] blocked) {
      Coord ca = grid.cell(a);
      Coord cb = grid.cell(b);
      int minx = Math.min(ca.x, cb.x);
      int maxx = Math.max(ca.x, cb.x);
      int miny = Math.min(ca.y, cb.y);
      int maxy = Math.max(ca.y, cb.y);
      minx = Math.max(0, minx);
      miny = Math.max(0, miny);
      maxx = Math.min(grid.w - 1, maxx);
      maxy = Math.min(grid.h - 1, maxy);

      for (int y = miny; y <= maxy; y++) {
         for (int x = minx; x <= maxx; x++) {
            if (blocked[y * grid.w + x]) {
               double x0 = grid.origin.x + (double)x * 2.75;
               double y0 = grid.origin.y + (double)y * 2.75;
               if (segmentHitsSquare(a, b, x0, y0, x0 + 2.75, y0 + 2.75)) {
                  return false;
               }
            }
         }
      }

      return true;
   }

   static {
      BUILDING_HALF.put("gfx/terobjs/arch/logcabin", Coord2d.of(24.0, 18.0));
      BUILDING_HALF.put("gfx/terobjs/arch/timberhouse", Coord2d.of(36.0, 26.0));
      BUILDING_HALF.put("gfx/terobjs/arch/stonestead", Coord2d.of(46.0, 34.0));
      BUILDING_HALF.put("gfx/terobjs/arch/stonemansion", Coord2d.of(50.0, 38.0));
      BUILDING_HALF.put("gfx/terobjs/arch/greathall", Coord2d.of(80.0, 40.0));
      BUILDING_HALF.put("gfx/terobjs/arch/greathall-door", Coord2d.of(8.0, 36.0));
      BUILDING_HALF.put("gfx/terobjs/arch/stonetower", Coord2d.of(38.0, 38.0));
      BUILDING_HALF.put("gfx/terobjs/arch/windmill", Coord2d.of(30.0, 30.0));
      BUILDING_HALF.put("gfx/terobjs/arch/greenhouse", Coord2d.of(24.0, 18.0));
      BUILDING_HALF.put("gfx/terobjs/arch/stonehut", Coord2d.of(22.0, 16.0));
      FURNITURE_HALF.put("gfx/terobjs/cupboard", Coord2d.of(5.0, 5.0));
   }

   static final class ClipResult {
      final List<Coord2d> targets = new ArrayList<>();
      final List<Boolean> clipped = new ArrayList<>();
   }

   public static final class GobGeom {
      public long id;
      public String name = "";
      public String resid = "";
      public Coord2d rc;
      public double a;
      public double gobDist;
      public double polyDist = Double.POSITIVE_INFINITY;
      public double hitboxDist = Double.POSITIVE_INFINITY;
      public boolean cupboard;
      public boolean boulder;
      public boolean caveTransition;
      public boolean doorGate;
      public boolean visitorGate;
      public boolean passable;
      public int gateState = -1;
      public List<Coord2d[]> movement = Collections.emptyList();
      public List<Coord2d[]> hitbox = Collections.emptyList();
   }

   static final class Grid implements GridAStar.Grid {
      final Coord2d origin;
      final int w;
      final int h;
      final boolean[] blocked;
      final double[] cost;

      Grid(Coord2d origin, int w, int h) {
         this.origin = origin;
         this.w = w;
         this.h = h;
         this.blocked = new boolean[w * h];
         this.cost = new double[w * h];
         Arrays.fill(this.cost, 1.0);
      }

      @Override
      public int width() {
         return this.w;
      }

      @Override
      public int height() {
         return this.h;
      }

      @Override
      public boolean blocked(int x, int y) {
         return this.blocked[y * this.w + x];
      }

      @Override
      public double cost(int x, int y) {
         return x >= 0 && y >= 0 && x < this.w && y < this.h ? this.cost[y * this.w + x] : 1.0;
      }

      void block(int x, int y) {
         if (x >= 0 && y >= 0 && x < this.w && y < this.h) {
            this.blocked[y * this.w + x] = true;
         }
      }

      Coord cell(Coord2d p) {
         return Coord.of((int)Math.floor((p.x - this.origin.x) / 2.75), (int)Math.floor((p.y - this.origin.y) / 2.75));
      }

      Coord2d world(Coord c) {
         return this.origin.add(((double)c.x + 0.5) * 2.75, ((double)c.y + 0.5) * 2.75);
      }
   }

   public static final class NearbyGob {
      public final long id;
      public final String name;
      public final String resid;
      public final double dist;
      public final Coord2d rc;

      private NearbyGob(long id, String name, String resid, double dist, Coord2d rc) {
         this.id = id;
         this.name = name;
         this.resid = resid;
         this.dist = dist;
         this.rc = rc;
      }

      public String label() {
         return String.format("%s  %.1ft", this.name, this.dist / MCache.tilesz.x);
      }
   }

   private static final class OccupancyBuild {
      final PrototypePathfinder.Grid grid;
      final boolean[] terrain;
      final String[] terrainNames;
      final boolean[] solid;
      final boolean[] dilated;
      final int obstacles;
      final List<Coord2d> hazards;

      private OccupancyBuild(
         PrototypePathfinder.Grid grid, boolean[] terrain, String[] terrainNames, boolean[] solid, boolean[] dilated, int obstacles, List<Coord2d> hazards
      ) {
         this.grid = grid;
         this.terrain = terrain;
         this.terrainNames = terrainNames;
         this.solid = solid;
         this.dilated = dilated;
         this.obstacles = obstacles;
         this.hazards = hazards == null ? Collections.emptyList() : hazards;
      }

      static PrototypePathfinder.OccupancyBuild build(GameUI gui, PrototypePathfinder.Grid grid, Gob player, List<Coord2d[]> debugPolys) {
         String[] terrainNames = new String[grid.blocked.length];
         boolean[] terrain = PrototypePathfinder.rasterTerrain(gui, grid, terrainNames);
         int obstacles = PrototypePathfinder.rasterGobs(gui, grid, player, debugPolys, null);
         boolean[] solid = Arrays.copyOf(grid.blocked, grid.blocked.length);
         List<Coord2d[]> body = PrototypePathfinder.playerBodyOrigin(player);
         PrototypePathfinder.rasterGobs(gui, grid, player, null, body);
         PrototypePathfinder.inflateMasked(grid, terrain, body);
         boolean[] dilated = Arrays.copyOf(grid.blocked, grid.blocked.length);
         return new PrototypePathfinder.OccupancyBuild(grid, terrain, terrainNames, solid, dilated, obstacles, movingHazards(gui, player));
      }
   }

   public static final class Plan {
      public final List<Coord2d> waypoints;
      public final boolean complete;
      public final boolean snapped;
      public final int expanded;
      public final int obstacles;
      public final PrototypePathfinder.Plan.Status status;

      private Plan(List<Coord2d> waypoints, boolean complete, boolean snapped, int expanded, int obstacles, PrototypePathfinder.Plan.Status status) {
         this.waypoints = waypoints;
         this.complete = complete;
         this.snapped = snapped;
         this.expanded = expanded;
         this.obstacles = obstacles;
         this.status = status;
      }

      private Plan(List<Coord2d> waypoints, boolean complete, int expanded, int obstacles, PrototypePathfinder.Plan.Status status) {
         this(waypoints, complete, false, expanded, obstacles, status);
      }

      public static PrototypePathfinder.Plan direct(Coord2d from, Coord2d dest) {
         List<Coord2d> waypoints = new ArrayList<>();
         if (from != null) {
            waypoints.add(from);
         }

         if (dest != null) {
            waypoints.add(dest);
         }

         return new PrototypePathfinder.Plan(waypoints, true, false, 0, 0, PrototypePathfinder.Plan.Status.REACHED);
      }

      public static PrototypePathfinder.Plan of(List<Coord2d> waypoints) {
         if (waypoints == null) {
            waypoints = Collections.emptyList();
         }

         return new PrototypePathfinder.Plan(new ArrayList<>(waypoints), true, false, 0, 0, PrototypePathfinder.Plan.Status.REACHED);
      }

      public static PrototypePathfinder.Plan trimEnd(PrototypePathfinder.Plan plan, Coord2d avoid, double minDist) {
         if (plan != null && avoid != null) {
            List<Coord2d> waypoints = new ArrayList<>(plan.waypoints);

            while (waypoints.size() >= 2 && waypoints.get(waypoints.size() - 1).dist(avoid) < minDist) {
               waypoints.remove(waypoints.size() - 1);
            }

            return new PrototypePathfinder.Plan(waypoints, plan.complete, plan.snapped, plan.expanded, plan.obstacles, plan.status);
         } else {
            return plan;
         }
      }

      static PrototypePathfinder.Plan fabricated(
         List<Coord2d> waypoints, boolean complete, boolean snapped, int expanded, int obstacles, PrototypePathfinder.Plan.Status status
      ) {
         return new PrototypePathfinder.Plan(new ArrayList<>(waypoints), complete, snapped, expanded, obstacles, status);
      }

      public static enum Status {
         REACHED,
         CLIPPED,
         SNAPPED,
         PARTIAL,
         FAILED;
      }
   }

   public static final class Scene {
      public Coord2d origin;
      public int w;
      public int h;
      public double cell = 2.75;
      public double radius;
      public Coord2d player;
      public Coord playerCell;
      public boolean moving;
      public boolean playerInSolid;
      public boolean playerInDilated;
      public String terrain = "";
      public String[] terrainCells;
      public int solidCount;
      public int dilatedCount;
      public int obstacles;
      public PathfinderLog.Occupancy occupancy;
      public List<PrototypePathfinder.GobGeom> gobs = new ArrayList<>();

      public Coord cellOf(Coord2d p) {
         return p != null && this.origin != null
            ? Coord.of((int)Math.floor((p.x - this.origin.x) / this.cell), (int)Math.floor((p.y - this.origin.y) / this.cell))
            : null;
      }

      public byte at(Coord2d p) {
         Coord c = this.cellOf(p);
         return this.occupancy != null && c != null ? this.occupancy.at(c.x, c.y) : 1;
      }

      public boolean bodyFree(Coord2d p) {
         byte v = this.at(p);
         return v == 0 || v == 3;
      }

      public boolean standable(Coord2d p) {
         return this.at(p) != 1;
      }

      public boolean aisleStand(Coord2d p) {
         if (!this.standable(p)) {
            return false;
         } else if (this.bodyFree(p)) {
            return true;
         } else {
            Coord c = this.cellOf(p);
            if (this.occupancy != null && c != null) {
               int[] dxs = new int[]{1, -1, 0, 0};
               int[] dys = new int[]{0, 0, 1, -1};

               for (int i = 0; i < 4; i++) {
                  byte v = this.occupancy.at(c.x + dxs[i], c.y + dys[i]);
                  if (v == 0 || v == 3) {
                     return true;
                  }
               }

               return false;
            } else {
               return true;
            }
         }
      }

      public List<Coord2d> approachStands(Coord2d gobRc, double boxHalf) {
         List<Coord2d> out = new ArrayList<>();
         if (gobRc != null && this.occupancy != null && this.origin != null) {
            Coord gc = this.cellOf(gobRc);
            if (gc == null) {
               return out;
            } else {
               int reach = (int)Math.ceil(boxHalf / this.cell) + 2;
               int[] dx = new int[]{0, 1, 0, -1};
               int[] dy = new int[]{-1, 0, 1, 0};
               Set<String> seen = new HashSet<>();

               for (int y = gc.y - reach; y <= gc.y + reach; y++) {
                  for (int x = gc.x - reach; x <= gc.x + reach; x++) {
                     Coord2d wc = this.origin.add(((double)x + 0.5) * this.cell, ((double)y + 0.5) * this.cell);
                     if (!(Math.abs(wc.x - gobRc.x) > boxHalf) && !(Math.abs(wc.y - gobRc.y) > boxHalf)) {
                        for (int d = 0; d < 4; d++) {
                           int nx = x + dx[d];
                           int ny = y + dy[d];
                           Coord2d np = this.origin.add(((double)nx + 0.5) * this.cell, ((double)ny + 0.5) * this.cell);
                           if ((!(Math.abs(np.x - gobRc.x) <= boxHalf) || !(Math.abs(np.y - gobRc.y) <= boxHalf)) && this.aisleStand(np)) {
                              String key = nx + "," + ny;
                              if (seen.add(key)) {
                                 out.add(np);
                              }
                           }
                        }
                     }
                  }
               }

               return out;
            }
         } else {
            return out;
         }
      }
   }
}
