package haven.pathfinding;

import haven.Area;
import haven.Coord2d;
import haven.Gob;
import haven.MCache;
import haven.layout.LayoutFootprint;
import haven.layout.LayoutPlanResult;
import haven.layout.LayoutPlanner;
import haven.layout.LayoutRequest;
import haven.layout.LayoutShape;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Generic exact-placement planner for lifted objects. Physical placement geometry
 * is kept separate from navigation clearance; execution is owned by
 * {@link PlacementExecutor}.
 */
public final class ObjectOrganizer {
   private ObjectOrganizer() {}

   public static final class ExactPlacement {
      public final int index;
      public final Coord2d anchor;
      public final double angle;
      public final ExactPlacementPlanner.Shape shape;

      ExactPlacement(int index, Coord2d anchor, double angle,
                     ExactPlacementPlanner.Shape shape) {
         this.index = index;
         this.anchor = anchor;
         this.angle = angle;
         this.shape = shape;
      }
   }

   public static final class ExactPlan {
      public final List<ExactPlacement> placements;
      public final ExactPlacementPlanner.Shape footprint;
      public final double gap;
      public final String geometrySource;
      public final String reason;

      ExactPlan(List<ExactPlacement> placements, ExactPlacementPlanner.Shape footprint,
                double gap, String geometrySource, String reason) {
         this.placements = Collections.unmodifiableList(placements);
         this.footprint = footprint;
         this.gap = gap;
         this.geometrySource = geometrySource == null ? "" : geometrySource;
         this.reason = reason == null ? "" : reason;
      }
   }

   /** Plans copies of a live object's physical shape at the requested angle. */
   public static ExactPlan planExact(Gob object, Area area, Coord2d player,
                                     int count, double angle,
                                     List<ExactPlacementPlanner.Shape> occupied) {
      if(object == null)
         return failed("live object unavailable", 0.0, "live-placement");
      String resource;
      try {resource = object.resid();}
      catch(RuntimeException loading) {resource = null;}
      ObjectSpatialProfile profile = ObjectSpatialProfiles.resolve(resource);
      ExactPlacementPlanner.Shape footprint = PlacementGeometry.relative(object, angle);
      return planExact(footprint, area, player, count, angle, profile.placementGap,
         occupied, profile.placementSource);
   }

   /** Plans a catalog-backed resource, such as a stockpile or confirmed log family. */
   public static ExactPlan planExact(String resource, Area area, Coord2d player,
                                     int count, double angle,
                                     List<ExactPlacementPlanner.Shape> occupied) {
      ObjectSpatialProfile profile = ObjectSpatialProfiles.resolve(resource);
      PlacementGeometry.Resolved physical = PlacementGeometry.resolve(resource, angle);
      return planExact(physical.shape, area, player, count, angle, profile.placementGap,
         occupied, physical.source);
   }

   /** Full exact form for callers that have already resolved physical geometry. */
   public static ExactPlan planExact(ExactPlacementPlanner.Shape footprint, Area area,
                                     Coord2d player, int count, double angle, double gap,
                                     List<ExactPlacementPlanner.Shape> occupied,
                                     String geometrySource) {
      return planExact(footprint, area, player, count, angle, gap, occupied,
         geometrySource, ExactPlacementPlanner.FillOrder.FRONT_EDGE);
   }

   public static ExactPlan planExact(ExactPlacementPlanner.Shape footprint, Area area,
                                     Coord2d player, int count, double angle, double gap,
                                     List<ExactPlacementPlanner.Shape> occupied,
                                     String geometrySource,
                                     ExactPlacementPlanner.FillOrder fillOrder) {
      if(footprint == null || footprint.bounds == null)
         return failed("physical placement geometry unavailable", gap, geometrySource);
      if(area == null || !area.positive() || count <= 0)
         return failed("invalid exact placement request", gap, geometrySource);
      List<Coord2d> anchors = ExactPlacementPlanner.planExact(
         area, MCache.tilesz, footprint, occupied, player, gap, count, fillOrder);
      List<ExactPlacement> placements = new ArrayList<ExactPlacement>();
      for(int i = 0; i < anchors.size(); i++) {
         Coord2d anchor = anchors.get(i);
         placements.add(new ExactPlacement(i, anchor, angle, footprint.move(anchor)));
      }
      String reason = placements.isEmpty() ? "no exact placement fits inside the selected area" : "";
      return new ExactPlan(placements, footprint, gap, geometrySource, reason);
   }

   private static ExactPlan failed(String reason, double gap, String source) {
      return new ExactPlan(new ArrayList<ExactPlacement>(), null, gap, source, reason);
   }

   /** Legacy tile footprint retained for compatibility with older integrations. */
   @Deprecated
   public static LayoutFootprint footprint(String resname) {
      return ObjectFootprints.footprintFor(resname);
   }

   /** Plan {@code count} placements of {@code resname} in {@code [areaMin, areaMax]}. */
   @Deprecated
   public static LayoutPlanResult plan(
      String resname, OccupancyGrid occ, Coord2d areaMin, Coord2d areaMax,
      Coord2d approachFrom, int count, double pitch
   ) {
      return plan(ObjectFootprints.footprintFor(resname), occ, areaMin, areaMax, approachFrom, count, pitch);
   }

   /** Plan {@code count} placements of an explicit footprint (no resource lookup). */
   @Deprecated
   public static LayoutPlanResult plan(
      LayoutFootprint fp, OccupancyGrid occ, Coord2d areaMin, Coord2d areaMax,
      Coord2d approachFrom, int count, double pitch
   ) {
      return plan(fp, occ, areaMin, areaMax, approachFrom, count, pitch, null, null);
   }

   /**
    * Full form: also preserve existing occupied/kept shapes (objects that must
    * stay in place). {@code pitch} is the placement grid pitch in world units
    * (each footprint cell is pitch x pitch).
    */
   @Deprecated
   public static LayoutPlanResult plan(
      LayoutFootprint fp, OccupancyGrid occ, Coord2d areaMin, Coord2d areaMax,
      Coord2d approachFrom, int count, double pitch,
      List<LayoutShape> keep, List<LayoutShape> occupied
   ) {
      LayoutRequest req = LayoutRequest.builder(occ, fp, approachFrom, count)
         .area(areaMin, areaMax)
         .pitch(pitch)
         .keep(keep)
         .occupied(occupied)
         .build();
      return LayoutPlanner.plan(req);
   }
}
