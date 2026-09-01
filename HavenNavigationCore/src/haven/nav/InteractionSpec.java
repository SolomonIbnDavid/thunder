package haven.nav;

import haven.Coord2d;

/**
 * Renderer-independent interaction goal: target footprint plus approach
 * constraints. The target remains an obstacle; callers must not carve it.
 */
public final class InteractionSpec {
   public static final int SIDE_N = 1;
   public static final int SIDE_E = 2;
   public static final int SIDE_S = 4;
   public static final int SIDE_W = 8;
   public static final int ALL_SIDES = SIDE_N | SIDE_E | SIDE_S | SIDE_W;

   public final String targetId;
   public final Coord2d origin;
   public final Coord2d half;
   public final int allowedSides;
   public final double minDist;
   public final double maxDist;
   public final int requiredClearance;
   public final Double facing;
   public final String expectedResult;

   public InteractionSpec(
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
      if (origin == null) {
         throw new IllegalArgumentException("origin");
      }
      if (minDist < 0.0 || maxDist < minDist) {
         throw new IllegalArgumentException("distance");
      }
      this.targetId = targetId;
      this.origin = origin;
      this.half = half == null ? Coord2d.of(5.5, 5.5) : Coord2d.of(Math.max(0.0, half.x), Math.max(0.0, half.y));
      this.allowedSides = allowedSides;
      this.minDist = minDist;
      this.maxDist = maxDist;
      this.requiredClearance = Math.max(0, requiredClearance);
      this.facing = facing;
      this.expectedResult = expectedResult == null ? "" : expectedResult;
   }

   public double minX() {
      return this.origin.x - this.half.x;
   }

   public double maxX() {
      return this.origin.x + this.half.x;
   }

   public double minY() {
      return this.origin.y - this.half.y;
   }

   public double maxY() {
      return this.origin.y + this.half.y;
   }

   public static String sideName(int side) {
      if (side == SIDE_N) {
         return "N";
      }
      if (side == SIDE_E) {
         return "E";
      }
      if (side == SIDE_S) {
         return "S";
      }
      if (side == SIDE_W) {
         return "W";
      }
      return "NONE";
   }
}
