package haven.pathfinding;

import haven.nav.GraphEdge;
import haven.nav.GraphNode;
import haven.nav.MobilityProfile;

/** Thunder-only fixture/resid conversion and authoritative transition detection. */
public final class TransitionAdapter {
   private TransitionAdapter() {
   }

   public static GraphEdge.Kind kind(String resid) {
      TransitionApproachSelector.TransitionKind ck = TransitionApproachSelector.caveTransitionKind(resid);
      if (ck == TransitionApproachSelector.TransitionKind.MINEHOLE) {
         return GraphEdge.Kind.MINEHOLE;
      }
      if (ck == TransitionApproachSelector.TransitionKind.LADDER) {
         return GraphEdge.Kind.LADDER;
      }
      if (ck == TransitionApproachSelector.TransitionKind.CELLAR_DOOR || ck == TransitionApproachSelector.TransitionKind.CELLAR_STAIRS) {
         return GraphEdge.Kind.CELLAR_STAIRS;
      }
      if (TransitionApproachSelector.isDoorGateResid(resid)) {
         return GraphEdge.Kind.DOOR_GATE;
      }
      if (isBoatResid(resid)) {
         return GraphEdge.Kind.BOAT;
      }
      if (isCartResid(resid)) {
         return GraphEdge.Kind.VEHICLE;
      }
      if (isHearthResid(resid)) {
         return GraphEdge.Kind.HEARTH;
      }
      if (isCaveResid(resid)) {
         return GraphEdge.Kind.CAVE;
      }
      return null;
   }

   public static boolean isBoatResid(String resid) {
      String n = PrototypePathfinder.baseResid(resid);
      return n != null
         && (n.contains("/vehicle/rowboat")
            || n.contains("/vehicle/snekkja")
            || n.contains("/vehicle/knarr")
            || n.contains("/vehicle/spark"));
   }

   public static boolean isCartResid(String resid) {
      String n = PrototypePathfinder.baseResid(resid);
      return n != null && (n.contains("/vehicle/wagon") || n.contains("/vehicle/cart"));
   }

   public static boolean isHearthResid(String resid) {
      String n = PrototypePathfinder.baseResid(resid);
      return n != null && (n.contains("hearth") || n.endsWith("/pow"));
   }

   public static boolean isCaveResid(String resid) {
      String n = PrototypePathfinder.baseResid(resid);
      return n != null && n.contains("cave") && !n.contains("cellar");
   }

   public static boolean topologyChanged(GraphNode before, GraphNode after) {
      return before != null && after != null && !before.equals(after);
   }

   public static boolean boarded(long vehicleBefore, long vehicleAfter) {
      return vehicleBefore == 0L && vehicleAfter != 0L;
   }

   public static boolean disembarked(long vehicleBefore, long vehicleAfter) {
      return vehicleBefore != 0L && vehicleAfter == 0L;
   }

   public static boolean doorStateChanged(int sdtBefore, int sdtAfter) {
      return sdtBefore != sdtAfter;
   }

   public static boolean waterLegal(MobilityProfile mob) {
      return TerrainPolicy.waterTravelLegal(mob);
   }
}
