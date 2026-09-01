package haven.pathfinding;

import haven.Coord2d;
import haven.nav.InteractionSpec;
import java.util.List;

/**
 * Converts live gob geometry into a renderer-independent InteractionSpec.
 * Resource names stay in Thunder; HavenNavigationCore never sees them.
 */
public final class InteractionAdapter {
   private InteractionAdapter() {
   }

   public static InteractionSpec fromGob(
      PrototypePathfinder.GobGeom g,
      int allowedSides,
      double minDist,
      double maxDist,
      int requiredClearance,
      Double facing,
      String expectedResult
   ) {
      if (g == null || g.rc == null) {
         return null;
      }
      Coord2d origin = g.rc;
      Coord2d half = Coord2d.of(5.5, 5.5);
      Coord2d[] box = aabbBox(g.movement.isEmpty() ? g.hitbox : g.movement);
      if (box != null) {
         origin = Coord2d.of((box[0].x + box[1].x) * 0.5, (box[0].y + box[1].y) * 0.5);
         half = Coord2d.of(Math.max(0.5, (box[1].x - box[0].x) * 0.5), Math.max(0.5, (box[1].y - box[0].y) * 0.5));
      }
      return new InteractionSpec(Long.toString(g.id), origin, half, allowedSides, minDist, maxDist, requiredClearance, facing, expectedResult);
   }

   public static InteractionSpec fromFootprint(
      String targetId,
      Coord2d origin,
      Coord2d half,
      int allowedSides,
      double minDist,
      double maxDist,
      int requiredClearance,
      Double facing,
      String expectedResult
   ) {
      return new InteractionSpec(targetId, origin, half, allowedSides, minDist, maxDist, requiredClearance, facing, expectedResult);
   }

   static Coord2d[] aabbBox(List<Coord2d[]> polys) {
      if (polys == null || polys.isEmpty()) {
         return null;
      }
      double minx = Double.POSITIVE_INFINITY;
      double miny = Double.POSITIVE_INFINITY;
      double maxx = Double.NEGATIVE_INFINITY;
      double maxy = Double.NEGATIVE_INFINITY;
      boolean any = false;
      for (int i = 0; i < polys.size(); i++) {
         Coord2d[] poly = polys.get(i);
         if (poly == null) {
            continue;
         }
         for (int j = 0; j < poly.length; j++) {
            Coord2d p = poly[j];
            if (p == null) {
               continue;
            }
            any = true;
            minx = Math.min(minx, p.x);
            miny = Math.min(miny, p.y);
            maxx = Math.max(maxx, p.x);
            maxy = Math.max(maxy, p.y);
         }
      }
      return any ? new Coord2d[]{Coord2d.of(minx, miny), Coord2d.of(maxx, maxy)} : null;
   }
}
