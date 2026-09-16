package haven.pathfinding;

import haven.Coord2d;
import haven.layout.LayoutFootprint;
import haven.layout.LayoutPlacement;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class StockpilePlacementTest {
   @Test
   void executionKeepsPlannersIntermediateWaypoints() {
      Coord2d oldStart = Coord2d.of(0, 0);
      Coord2d bend = Coord2d.of(11, 22);
      Coord2d stand = Coord2d.of(33, 22);
      LayoutPlacement placement = new LayoutPlacement(0, 0, 0, Coord2d.of(22, 22),
         LayoutFootprint.rect(1, 1), stand, Arrays.asList(oldStart, bend, stand));

      Coord2d liveStart = Coord2d.of(1, 0);
      List<Coord2d> route = StockpilePlacement.executionRoute(liveStart, placement);
      Assertions.assertEquals(Arrays.asList(liveStart, bend, stand), route);
   }
}
