package haven.nav;

import haven.Coord2d;
import java.util.Collections;
import java.util.List;

public final class NavGoal {
   public enum Kind {
      POINT,
      AREA,
      INTERACTION,
      TRANSITION,
      EXPLORE_FRONTIER;
   }

   public final Kind kind;
   public final Coord2d position;
   public final List<Coord2d> region;
   public final String targetId;

   public NavGoal(Kind kind, Coord2d position, List<Coord2d> region, String targetId) {
      if (kind == null) {
         throw new IllegalArgumentException("kind");
      }
      this.kind = kind;
      this.position = position;
      this.region = region == null ? Collections.emptyList() : Collections.unmodifiableList(region);
      this.targetId = targetId;
   }

   public static NavGoal point(Coord2d position) {
      return new NavGoal(Kind.POINT, position, Collections.emptyList(), null);
   }

   public static NavGoal area(List<Coord2d> region) {
      return new NavGoal(Kind.AREA, region == null || region.isEmpty() ? null : (Coord2d) region.get(0), region, null);
   }
}
