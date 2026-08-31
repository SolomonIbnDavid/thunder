package haven.pathfinding;

import haven.Coord2d;
import haven.nav.MobilityProfile;
import haven.nav.NavSnapshot;
import java.util.ArrayList;
import java.util.List;

/** Converts live Thunder scene state into HavenNavigationCore snapshots. */
public final class ThunderNavAdapter {
   private ThunderNavAdapter() {
   }

   public static NavSnapshot snapshot(PrototypePathfinder.Scene scene) {
      if (scene == null) {
         return new NavSnapshot(
            System.currentTimeMillis(), "world", null, null, null, PathfinderLog.lastHazards(), null, null, LocalPlanner.DEFAULT_AGENT_RADIUS, false, MobilityProfile.land()
         );
      }
      List<Coord2d[]> obstacles = new ArrayList<>();
      if (scene.gobs != null) {
         for (PrototypePathfinder.GobGeom g : scene.gobs) {
            if (g != null && g.movement != null) {
               obstacles.addAll(g.movement);
            }
         }
      }
      return new NavSnapshot(
         System.currentTimeMillis(),
         "world",
         scene.occupancy,
         scene.terrainCells,
         obstacles,
         PathfinderLog.lastHazards(),
         scene.player,
         scene.playerCell,
         scene.radius,
         scene.moving,
         MobilityProfile.land()
      );
   }
}
