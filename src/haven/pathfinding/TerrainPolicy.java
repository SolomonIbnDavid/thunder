package haven.pathfinding;

public final class TerrainPolicy {
   private TerrainPolicy() {
   }

   public static boolean terrainBlocks(String tileName) {
      return tileName == null
         ? false
         : tileName.contains("tiles/nil") || tileName.contains("tiles/cave") || tileName.contains("tiles/deep") || tileName.contains("tiles/rocks/");
   }

   public static boolean isUnknownTile(String tileName) {
      return tileName == null ? true : tileName.contains("tiles/notile");
   }
}
