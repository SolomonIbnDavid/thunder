package haven.pathfinding;

import haven.Coord;
import haven.nav.GraphEdge;
import haven.nav.GraphNode;
import haven.nav.MobilityProfile;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.json.JSONObject;

public class WorldGraphAdapterTest {
   @Test
   void nodeUsesSegmentAndAreaNotLayerOrdinals() {
      GraphNode a = WorldGraphAdapter.of("haven", 10L, "0", Coord.of(5, 5));
      GraphNode b = WorldGraphAdapter.of("haven", 10L, "1", Coord.of(5, 5));
      Assertions.assertEquals("haven", a.worldId);
      Assertions.assertEquals(Long.toUnsignedString(10L, 16), a.segmentId);
      Assertions.assertNotEquals(a, b);
      Assertions.assertFalse(a.sameWalkRegion(b));
      Assertions.assertEquals(WorldGraphAdapter.areaId(Coord.of(5, 5)), a.areaId);
   }

   @Test
   void mobilityFromVehicleState() {
      Assertions.assertFalse(WorldGraphAdapter.mobility(0L, false).vehicle());
      Assertions.assertTrue(WorldGraphAdapter.mobility(9L, false).boat);
      Assertions.assertTrue(WorldGraphAdapter.mobility(3L, true).cart);
      Assertions.assertTrue(TerrainPolicy.agentRadius(WorldGraphAdapter.mobility(9L, false), 4.5) > 4.5);
   }

   @Test
   void topologyAndVehicleDetection() {
      GraphNode src = GraphNode.of("w", "aa", "grid1", "0,0");
      GraphNode dst = GraphNode.of("w", "bb", "grid2", "0,0");
      Assertions.assertTrue(TransitionAdapter.topologyChanged(src, dst));
      Assertions.assertFalse(TransitionAdapter.topologyChanged(src, src));
      Assertions.assertTrue(TransitionAdapter.boarded(0L, 12L));
      Assertions.assertTrue(TransitionAdapter.disembarked(12L, 0L));
      Assertions.assertFalse(TransitionAdapter.boarded(4L, 4L));
      Assertions.assertTrue(TransitionAdapter.doorStateChanged(0, 1));
      Assertions.assertFalse(TransitionAdapter.waterLegal(MobilityProfile.land()));
      Assertions.assertTrue(TransitionAdapter.waterLegal(MobilityProfile.boat()));
   }

   @Test
   void fixtureConversionStaysInThunder() {
      Assertions.assertEquals(GraphEdge.Kind.DOOR_GATE, TransitionAdapter.kind("gfx/terobjs/arch/palisadegate"));
      Assertions.assertEquals(GraphEdge.Kind.CELLAR_STAIRS, TransitionAdapter.kind("gfx/terobjs/arch/cellarstairs"));
      Assertions.assertEquals(GraphEdge.Kind.MINEHOLE, TransitionAdapter.kind("gfx/terobjs/minehole"));
      Assertions.assertEquals(GraphEdge.Kind.LADDER, TransitionAdapter.kind("gfx/terobjs/ladder"));
      Assertions.assertEquals(GraphEdge.Kind.BOAT, TransitionAdapter.kind("gfx/terobjs/vehicle/rowboat"));
      Assertions.assertEquals(GraphEdge.Kind.VEHICLE, TransitionAdapter.kind("gfx/terobjs/vehicle/wagon"));
      Assertions.assertEquals(GraphEdge.Kind.HEARTH, TransitionAdapter.kind("gfx/terobjs/pow[hearth]"));
      Assertions.assertEquals(GraphEdge.Kind.CAVE, TransitionAdapter.kind("gfx/terobjs/cavein"));
      Assertions.assertNull(TransitionAdapter.kind("gfx/terobjs/cupboard"));
   }

   @Test
   void beforeAfterCaptureDoesNotGuessLanding() {
      GraphNode before = GraphNode.of("w", "s0", "L", "a");
      Assertions.assertFalse(TransitionAdapter.topologyChanged(before, before));
      Assertions.assertNull(TransitionAdapter.kind(null));
   }

   @Test
   void transitionScenariosFailClosedWithoutGame() throws Exception {
      for (TransitionScenario.Kind k : TransitionScenario.Kind.values()) {
         JSONObject r = new TransitionScenario(k).execute(new PfTestRunner.Run(k.name), null);
         Assertions.assertEquals("FAIL", r.getString("verdict"), k.name);
         Assertions.assertEquals("NO_GAME", r.getJSONObject("facts").getString("refusal"), k.name);
         Assertions.assertFalse(r.getJSONObject("facts").optBoolean("reached", true));
      }
   }
}
