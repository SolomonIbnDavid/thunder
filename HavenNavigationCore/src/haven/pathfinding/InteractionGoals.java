package haven.pathfinding;

import haven.Coord;
import haven.Coord2d;
import haven.nav.InteractionSpec;
import haven.nav.NavPlan;
import haven.nav.NavPlanStatus;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Legal stand poses around an interaction footprint. The target stays
 * blocked; this class never mutates occupancy or paths to the object center.
 */
public final class InteractionGoals {
   public static final String NO_POSE = "no_interaction_pose";
   private static final int MAX_PLAN = 128;
   private static final double FACE_CONE = Math.PI / 4.0;

   private InteractionGoals() {
   }

   public static final class Candidate {
      public final Coord cell;
      public final Coord2d world;
      public final int side;
      public final double dist;
      public final int clearance;
      public final boolean reachable;
      public final double routeCost;
      public final String reject;
      public final NavPlan plan;

      Candidate(Coord cell, Coord2d world, int side, double dist, int clearance, boolean reachable, double routeCost, String reject, NavPlan plan) {
         this.cell = cell;
         this.world = world;
         this.side = side;
         this.dist = dist;
         this.clearance = clearance;
         this.reachable = reachable;
         this.routeCost = routeCost;
         this.reject = reject;
         this.plan = plan;
      }
   }

   public static final class Result {
      public final InteractionSpec spec;
      public final Candidate selected;
      public final List<Candidate> considered;
      public final NavPlan plan;
      public final String reason;

      Result(InteractionSpec spec, Candidate selected, List<Candidate> considered, NavPlan plan, String reason) {
         this.spec = spec;
         this.selected = selected;
         this.considered = considered == null ? Collections.emptyList() : Collections.unmodifiableList(considered);
         this.plan = plan;
         this.reason = reason == null ? "" : reason;
      }

      public boolean ok() {
         return this.selected != null && this.plan != null && this.plan.status != NavPlanStatus.FAILED;
      }
   }

   public static Result select(Coord2d from, InteractionSpec spec, OccupancyGrid occ) {
      if (spec == null || occ == null || from == null) {
         return fail(spec, "no_interaction_pose");
      }
      Coord originCell = occ.cellOf(spec.origin);
      if (originCell == null) {
         return fail(spec, "no_interaction_pose");
      }
      int[] clr = SurfaceStream.clearance(occ);
      int pad = (int) Math.ceil((Math.max(spec.half.x, spec.half.y) + spec.maxDist) / occ.cell) + 2;
      int x0 = Math.max(0, originCell.x - pad);
      int x1 = Math.min(occ.w - 1, originCell.x + pad);
      int y0 = Math.max(0, originCell.y - pad);
      int y1 = Math.min(occ.h - 1, originCell.y + pad);
      List<Candidate> considered = new ArrayList<Candidate>();
      List<Candidate> legal = new ArrayList<Candidate>();
      for (int y = y0; y <= y1; y++) {
         for (int x = x0; x <= x1; x++) {
            Coord2d world = occ.world(x, y);
            Coord cell = Coord.of(x, y);
            int side = sideOf(spec.origin, world);
            double dist = distanceToFootprint(world, spec);
            int clearance = clr[y * occ.w + x];
            String reject = rejectReason(occ, spec, x, y, world, side, dist, clearance);
            Candidate c = new Candidate(cell, world, side, dist, clearance, false, Double.POSITIVE_INFINITY, reject, null);
            considered.add(c);
            if (reject == null) {
               legal.add(c);
            }
         }
      }
      Collections.sort(legal, new Comparator<Candidate>() {
         @Override
         public int compare(Candidate a, Candidate b) {
            int c = Integer.compare(b.clearance, a.clearance);
            if (c != 0) {
               return c;
            }
            c = Integer.compare(a.cell.x, b.cell.x);
            if (c != 0) {
               return c;
            }
            return Integer.compare(a.cell.y, b.cell.y);
         }
      });
      List<Candidate> reachable = new ArrayList<Candidate>();
      int planned = 0;
      for (int i = 0; i < legal.size() && planned < MAX_PLAN; i++) {
         Candidate c = legal.get(i);
         planned++;
         NavPlan plan = planTo(from, c.world, spec, occ);
         boolean ok = poseReached(plan, c.world, spec, occ);
         Candidate scored = new Candidate(c.cell, c.world, c.side, c.dist, c.clearance, ok, ok ? plan.cost : Double.POSITIVE_INFINITY, ok ? null : "unreachable", plan);
         replace(considered, c, scored);
         if (ok) {
            reachable.add(scored);
         }
      }
      for (int i = 0; i < considered.size(); i++) {
         Candidate c = considered.get(i);
         if (c.reject == null && !c.reachable) {
            considered.set(i, new Candidate(c.cell, c.world, c.side, c.dist, c.clearance, false, Double.POSITIVE_INFINITY, "unplanned", null));
         }
      }
      if (reachable.isEmpty()) {
         return new Result(spec, null, considered, NavPlan.failed(0, NO_POSE), NO_POSE);
      }
      Collections.sort(reachable, new Comparator<Candidate>() {
         @Override
         public int compare(Candidate a, Candidate b) {
            int c = Integer.compare(b.clearance, a.clearance);
            if (c != 0) {
               return c;
            }
            c = Double.compare(a.routeCost, b.routeCost);
            if (c != 0) {
               return c;
            }
            c = Integer.compare(a.cell.x, b.cell.x);
            if (c != 0) {
               return c;
            }
            return Integer.compare(a.cell.y, b.cell.y);
         }
      });
      Candidate best = reachable.get(0);
      NavPlan chosen = withSelected(best.plan, best.world);
      return new Result(spec, best, considered, chosen, "");
   }

   public static double distanceToFootprint(Coord2d p, InteractionSpec spec) {
      if (p == null || spec == null) {
         return Double.POSITIVE_INFINITY;
      }
      double dx = 0.0;
      if (p.x < spec.minX()) {
         dx = spec.minX() - p.x;
      } else if (p.x > spec.maxX()) {
         dx = p.x - spec.maxX();
      }
      double dy = 0.0;
      if (p.y < spec.minY()) {
         dy = spec.minY() - p.y;
      } else if (p.y > spec.maxY()) {
         dy = p.y - spec.maxY();
      }
      return Math.hypot(dx, dy);
   }

   public static boolean overlapsFootprint(Coord2d p, InteractionSpec spec) {
      return p != null && spec != null && p.x >= spec.minX() && p.x <= spec.maxX() && p.y >= spec.minY() && p.y <= spec.maxY();
   }

   public static int sideOf(Coord2d origin, Coord2d p) {
      double dx = p.x - origin.x;
      double dy = p.y - origin.y;
      if (Math.abs(dx) >= Math.abs(dy)) {
         return dx >= 0.0 ? InteractionSpec.SIDE_E : InteractionSpec.SIDE_W;
      }
      return dy >= 0.0 ? InteractionSpec.SIDE_S : InteractionSpec.SIDE_N;
   }

   public static boolean facingOk(Coord2d pose, Coord2d origin, Double facing) {
      if (facing == null || pose == null || origin == null) {
         return true;
      }
      double ang = Math.atan2(origin.y - pose.y, origin.x - pose.x);
      double d = ang - facing.doubleValue();
      while (d > Math.PI) {
         d -= 2.0 * Math.PI;
      }
      while (d < -Math.PI) {
         d += 2.0 * Math.PI;
      }
      return Math.abs(d) <= FACE_CONE;
   }

   private static String rejectReason(OccupancyGrid occ, InteractionSpec spec, int x, int y, Coord2d world, int side, double dist, int clearance) {
      byte v = occ.at(x, y);
      if (v == OccupancyGrid.SOLID) {
         return "solid";
      }
      if (overlapsFootprint(world, spec)) {
         return "overlap_target";
      }
      if (v == OccupancyGrid.DILATED && spec.requiredClearance > 0) {
         return "dilated";
      }
      if (v != OccupancyGrid.FREE && v != OccupancyGrid.CARVED && !(v == OccupancyGrid.DILATED && spec.requiredClearance == 0)) {
         return "occupancy";
      }
      if (dist + 1.0E-6 < spec.minDist) {
         return "too_close";
      }
      if (dist - 1.0E-6 > spec.maxDist) {
         return "too_far";
      }
      if ((spec.allowedSides & side) == 0) {
         return "disallowed_side";
      }
      if (clearance < spec.requiredClearance) {
         return "clearance";
      }
      if (!facingOk(world, spec.origin, spec.facing)) {
         return "facing";
      }
      return null;
   }

   private static NavPlan planTo(Coord2d from, Coord2d pose, InteractionSpec spec, OccupancyGrid occ) {
      Coord fc = occ.cellOf(from);
      Coord pc = occ.cellOf(pose);
      if (fc != null && pc != null && fc.x == pc.x && fc.y == pc.y) {
         List<Coord2d> route = new ArrayList<Coord2d>();
         route.add(from);
         route.add(pose);
         return NavPlan.create(NavPlanStatus.REACHED, route, route, true, false, 0, 0, "");
      }
      NavGrid grid = new NavGrid(occ.origin, occ.w, occ.h);
      boolean[] solid = new boolean[occ.w * occ.h];
      boolean[] dilated = new boolean[occ.w * occ.h];
      int n = Math.min(occ.occ.length, solid.length);
      for (int i = 0; i < n; i++) {
         byte v = occ.occ[i];
         if (v == OccupancyGrid.SOLID) {
            solid[i] = true;
            grid.blocked[i] = true;
         } else if (v == OccupancyGrid.DILATED || v == OccupancyGrid.CARVED) {
            dilated[i] = true;
            grid.blocked[i] = true;
         }
      }
      List<Coord2d> destinations = new ArrayList<Coord2d>();
      destinations.add(pose);
      LocalPlanner.ClipResult clip = LocalPlanner.clipToHorizon(from, destinations);
      return LocalPlanner.planCore(from, destinations, clip.targets, clip.clipped, false, LocalPlanner.DEFAULT_AGENT_RADIUS, grid, solid, dilated, 0, new PlanningTrace());
   }

   private static boolean poseReached(NavPlan plan, Coord2d pose, InteractionSpec spec, OccupancyGrid occ) {
      if (plan == null || pose == null || plan.status == NavPlanStatus.FAILED || plan.status == NavPlanStatus.PARTIAL || plan.status == NavPlanStatus.CLIPPED) {
         return false;
      }
      Coord2d end = plan.selectedGoal;
      if (end == null && plan.smoothedRoute != null && !plan.smoothedRoute.isEmpty()) {
         end = (Coord2d) plan.smoothedRoute.get(plan.smoothedRoute.size() - 1);
      }
      if (end == null || end.dist(pose) > occ.cell) {
         return false;
      }
      List<Coord2d> route = plan.smoothedRoute;
      if (route != null) {
         for (int i = 0; i < route.size(); i++) {
            Coord2d p = (Coord2d) route.get(i);
            if (overlapsFootprint(p, spec)) {
               return false;
            }
         }
      }
      return true;
   }

   private static NavPlan withSelected(NavPlan plan, Coord2d pose) {
      if (plan == null) {
         return NavPlan.failed(0, NO_POSE);
      }
      return new NavPlan(plan.status, plan.rawRoute, plan.smoothedRoute, pose, plan.cost, plan.expanded, plan.obstacles, plan.complete, plan.snapped, plan.reason);
   }

   private static void replace(List<Candidate> considered, Candidate old, Candidate neu) {
      for (int i = 0; i < considered.size(); i++) {
         if (considered.get(i) == old) {
            considered.set(i, neu);
            return;
         }
      }
   }

   private static Result fail(InteractionSpec spec, String reason) {
      return new Result(spec, null, Collections.emptyList(), NavPlan.failed(0, reason), reason);
   }
}
