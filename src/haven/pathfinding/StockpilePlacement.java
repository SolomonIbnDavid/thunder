package haven.pathfinding;

import auto.Bot;
import haven.Area;
import haven.Coord2d;
import haven.Following;
import haven.GameUI;
import haven.Gob;
import haven.MCache;
import haven.layout.LayoutFootprint;
import haven.layout.LayoutPlanResult;
import haven.layout.LayoutPlacement;
import haven.layout.LayoutRequest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Comparator;

/** Shared footprint-aware planning and wire protocol for stockpile creation. */
public final class StockpilePlacement {
   private StockpilePlacement() {}

   /** Exact, front-to-back stockpile planner. Angle zero preserves the existing stockpile protocol. */
   public static ObjectOrganizer.ExactPlan planExact(GameUI gui, String stockpileRes, Area area, int count) {
      if (gui == null || gui.map == null || gui.map.player() == null ||
          gui.map.player().rc == null || area == null || !area.positive())
         return ObjectOrganizer.planExact((ExactPlacementPlanner.Shape)null, area, Coord2d.z,
            count, 0.0, 0.0, null, "invalid");
      List<ExactPlacementPlanner.Shape> obstacles = placementObstacles(gui, area, 0L);
      return ObjectOrganizer.planExact(stockpileRes, area, gui.map.player().rc,
         count, 0.0, obstacles);
   }

   public static Result createHeld(GameUI gui, Bot bot, String stockpileRes, Area area,
                                   ObjectOrganizer.ExactPlacement placement, long walkTimeoutMs,
                                   long stepTimeoutMs, MovementListener listener)
      throws InterruptedException {
      if (gui == null || gui.map == null || bot == null || area == null || placement == null)
         return Result.failed("invalid placement request", null);
      if (gui.hand() == null || gui.hand().item == null)
         return Result.failed("held item lost before placement", placement.anchor);
      Set<Long> before = pileIds(gui, stockpileRes, placement.anchor);
      PlacementExecutor.Result commit = PlacementExecutor.commitStockpile(
         gui, bot, area, placement.anchor, placement.angle, placement.shape,
         walkTimeoutMs, stepTimeoutMs, listener);
      if (!commit.sent()) return Result.failed(commit.detail, placement.anchor);

      final Gob[] created = new Gob[1];
      if (!waitFor(gui, bot, Math.max(stepTimeoutMs, 6000L), () -> {
         WorldObjectRegistry.Snapshot world = WorldObjectRegistry.snapshot(gui, gui.map.player(), 0);
         for (WorldObjectRegistry.Entry entry : world.objects) {
            if (entry.category != WorldObjectRegistry.Category.STOCKPILE || before.contains(entry.id) ||
                !stockpileRes.equals(entry.resource) || entry.position.dist(placement.anchor) > MCache.tilesz.x * 2.5)
               continue;
            created[0] = entry.gob;
            return true;
         }
         return false;
      })) return Result.failed("placement was not acknowledged", placement.anchor);
      ExactPlacementPlanner.Shape actual = PlacementGeometry.world(created[0]);
      if (actual == null || !actual.inside(area, MCache.tilesz, 0.0,
                                           PlacementGeometry.SERVER_POSITION_TOLERANCE))
         return Result.failed("created stockpile geometry is outside the selected area", placement.anchor);
      return Result.created(created[0], placement.anchor);
   }

   private static List<ExactPlacementPlanner.Shape> placementObstacles(GameUI gui, Area area, long ignoreId) {
      List<ExactPlacementPlanner.Shape> out = new ArrayList<ExactPlacementPlanner.Shape>();
      Gob player = gui.map.player();
      synchronized (gui.ui.sess.glob.oc) {
         for (Gob gob : gui.ui.sess.glob.oc) {
            if (gob == null || gob == player || gob.id == ignoreId || gob.rc == null || gob.disposed() ||
                gob.getattr(Following.class) != null) continue;
            ExactPlacementPlanner.Shape shape = PlacementGeometry.worldObstacle(gob);
            if (shape != null && touches(shape.bounds, area)) out.add(shape);
         }
      }
      return out;
   }

   private static boolean touches(ExactPlacementPlanner.Rect bounds, Area area) {
      if (bounds == null || area == null) return false;
      double x0 = area.ul.x * MCache.tilesz.x, y0 = area.ul.y * MCache.tilesz.y;
      double x1 = area.br.x * MCache.tilesz.x, y1 = area.br.y * MCache.tilesz.y;
      return bounds.maxX >= x0 && bounds.maxY >= y0 && bounds.minX <= x1 && bounds.minY <= y1;
   }

   /** @deprecated Tile-cell placement has no live callers; use {@link #planExact}. */
   @Deprecated
   public static LayoutPlanResult plan(GameUI gui, String stockpileRes, Coord2d areaMin, Coord2d areaMax, int count) {
      if (gui == null || gui.map == null || gui.map.player() == null || gui.map.player().rc == null)
         return null;
      MovementScene.Scene scene = MovementScene.observe(gui, false);
      if (scene == null || scene.occupancy == null) return null;
      LayoutFootprint footprint = StockpileOrganizer.stockpileFootprint(stockpileRes);
      LayoutRequest request = LayoutRequest.builder(scene.occupancy, footprint, gui.map.player().rc, count)
         .area(areaMin, areaMax).pitch(MCache.tilesz.x).build();
      return haven.layout.LayoutPlanner.plan(request);
   }

   /** @deprecated Center conversion for the retired tile-cell placement API. */
   @Deprecated
   public static Coord2d center(LayoutPlacement placement) {
      return placement.world.add(
         placement.footprint.bboxW() * MCache.tilesz.x / 2.0,
         placement.footprint.bboxH() * MCache.tilesz.y / 2.0);
   }

   /** @deprecated Use the exact-Area overload with {@link ObjectOrganizer.ExactPlacement}. */
   @Deprecated
   public static Result createHeld(GameUI gui, Bot bot, String stockpileRes, LayoutPlacement placement,
                                   int modflags, long walkTimeoutMs, long stepTimeoutMs,
                                   MovementListener listener)
      throws InterruptedException {
      if (gui == null || gui.map == null || bot == null || placement == null)
         return Result.failed("invalid placement request", null);
      if (gui.map.player() == null || gui.map.player().rc == null)
         return Result.failed("player position unavailable", null);

      // Include anything that appeared since planning before executing the walk.
      MovementScene.observe(gui, false);
      // LayoutPlanner already proved this route against the same occupancy that
      // selected the placement. Preserve its intermediate waypoints; replacing
      // it with a straight player->stand segment can cut directly through an
      // existing row of stockpiles and be rejected by the walker.
      List<Coord2d> route = executionRoute(gui.map.player().rc, placement);
      ConfirmedRouteRunner.Result walked = ConfirmedRouteRunner.execute(
         gui, bot, route, placement.stand, walkTimeoutMs, ConfirmedRouteRunner.Params.LAND,
         listener == null ? MovementListeners.NOOP : listener);
      if (!walked.arrived())
         return Result.failed("could not reach planned stand (" + walked.status + ")", null);
      if (playerOverlapsPlacement(gui, placement) && !retreatFromPlacement(gui, bot, placement, walkTimeoutMs, listener))
         return Result.failed("player still overlaps the planned footprint", center(placement));
      if (gui.hand() == null || gui.hand().item == null)
         return Result.failed("held item lost before placement", null);

      Coord2d center = center(placement);
      Set<Long> before = pileIds(gui, stockpileRes, center);
      if (!gui.map.itemactAt(center, modflags)) return Result.failed("itemact could not be sent", center);
      if (!waitFor(gui, bot, stepTimeoutMs, () -> gui.map.isPlacing()))
         return Result.failed("server did not enter the placer", center);
      if (!gui.map.placeAt(center, 1, modflags)) return Result.failed("place could not be sent", center);

      final Gob[] created = new Gob[1];
      if (!waitFor(gui, bot, stepTimeoutMs, () -> {
         WorldObjectRegistry.Snapshot world = WorldObjectRegistry.snapshot(gui, gui.map.player(), 0);
         for (WorldObjectRegistry.Entry entry : world.objects) {
            if (entry.category != WorldObjectRegistry.Category.STOCKPILE || before.contains(entry.id) || entry.position.dist(center) > MCache.tilesz.x * 2.5) continue;
            if (stockpileRes.equals(entry.resource)) { created[0] = entry.gob; return true; }
         }
         return false;
      })) return Result.failed("placement was not acknowledged", center);
      return Result.created(created[0], center);
   }

   static List<Coord2d> executionRoute(Coord2d current, LayoutPlacement placement) {
      List<Coord2d> route = new ArrayList<Coord2d>();
      if (placement != null && placement.standRoute != null) route.addAll(placement.standRoute);
      if (route.isEmpty()) {
         route.add(current);
         route.add(placement.stand);
      } else {
         // Use the live start without throwing away the planner's proven bends.
         if (current.dist(route.get(0)) <= MCache.tilesz.x) route.set(0, current);
         else route.add(0, current);
         if (route.size() == 1) route.add(placement.stand);
      }
      return route;
   }

   private static boolean playerOverlapsPlacement(GameUI gui, LayoutPlacement placement) {
      Gob player = gui.map.player();
      if (player == null || player.rc == null) return true;
      double margin = MCache.tilesz.x * 0.5 + 0.75;
      double x0 = placement.world.x - margin;
      double y0 = placement.world.y - margin;
      double x1 = placement.world.x + placement.footprint.bboxW() * MCache.tilesz.x + margin;
      double y1 = placement.world.y + placement.footprint.bboxH() * MCache.tilesz.y + margin;
      return player.rc.x >= x0 && player.rc.x <= x1 && player.rc.y >= y0 && player.rc.y <= y1;
   }

   /** Move fully clear when arrival tolerance leaves the player touching the preview footprint. */
   private static boolean retreatFromPlacement(GameUI gui, Bot bot, LayoutPlacement placement,
                                                long timeoutMs, MovementListener listener)
      throws InterruptedException {
      Gob player = gui.map.player();
      if (player == null || player.rc == null) return false;
      double clearance = MCache.tilesz.x * 0.5 + 2.0;
      double x0 = placement.world.x;
      double y0 = placement.world.y;
      double x1 = x0 + placement.footprint.bboxW() * MCache.tilesz.x;
      double y1 = y0 + placement.footprint.bboxH() * MCache.tilesz.y;
      List<Coord2d> candidates = new ArrayList<Coord2d>();
      candidates.add(Coord2d.of(x0 - clearance, (y0 + y1) / 2.0));
      candidates.add(Coord2d.of(x1 + clearance, (y0 + y1) / 2.0));
      candidates.add(Coord2d.of((x0 + x1) / 2.0, y0 - clearance));
      candidates.add(Coord2d.of((x0 + x1) / 2.0, y1 + clearance));
      candidates.sort(Comparator.comparingDouble(player.rc::dist));
      MovementListener out = listener == null ? MovementListeners.NOOP : listener;
      for (Coord2d candidate : candidates) {
         BotMovement.Result walked = BotMovement.moveTo(gui, bot, candidate, BotMovement.Mode.LAND, timeoutMs);
         if (walked.status == BotMovement.Status.ARRIVED
             && !playerOverlapsPlacement(gui, placement)) return true;
         out.fail("placement retreat " + walked.status);
      }
      return false;
   }

   private static Set<Long> pileIds(GameUI gui, String res, Coord2d center) {
      Set<Long> ids = new HashSet<Long>();
      WorldObjectRegistry.Snapshot world = WorldObjectRegistry.snapshot(gui, gui.map.player(), 0);
      for (WorldObjectRegistry.Entry entry : world.objects) {
         if (entry.category == WorldObjectRegistry.Category.STOCKPILE && entry.position.dist(center) <= MCache.tilesz.x * 2.5 && res.equals(entry.resource)) ids.add(entry.id);
      }
      return ids;
   }

   private interface Check { boolean get(); }

   private static boolean waitFor(GameUI gui, Bot bot, long timeoutMs, Check check) throws InterruptedException {
      long end = System.currentTimeMillis() + timeoutMs;
      while (System.currentTimeMillis() < end) {
         bot.checkCancelled();
         if (check.get()) return true;
         Thread.sleep(50L);
      }
      return check.get();
   }

   public static final class Result {
      public final boolean success;
      public final String reason;
      public final Gob gob;
      public final Coord2d center;

      private Result(boolean success, String reason, Gob gob, Coord2d center) {
         this.success = success;
         this.reason = reason;
         this.gob = gob;
         this.center = center;
      }

      static Result created(Gob gob, Coord2d center) { return new Result(true, "", gob, center); }
      static Result failed(String reason, Coord2d center) { return new Result(false, reason, null, center); }
   }
}
