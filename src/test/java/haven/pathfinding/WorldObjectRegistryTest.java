package haven.pathfinding;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class WorldObjectRegistryTest {
   @Test void classifiesCommonSharedObjects() {
      Assertions.assertEquals(WorldObjectRegistry.Category.STOCKPILE,
         WorldObjectRegistry.category("gfx/terobjs/stockpile-wblock", false));
      Assertions.assertEquals(WorldObjectRegistry.Category.SMELTER,
         WorldObjectRegistry.category("gfx/terobjs/smelter", false));
      Assertions.assertEquals(WorldObjectRegistry.Category.SMELTER,
         WorldObjectRegistry.category("gfx/terobjs/oven", false));
      Assertions.assertEquals(WorldObjectRegistry.Category.SMELTER,
         WorldObjectRegistry.category("gfx/terobjs/primsmelter", false));
      Assertions.assertEquals(WorldObjectRegistry.Category.FURNITURE,
         WorldObjectRegistry.category("gfx/terobjs/dframe", false));
      Assertions.assertEquals(WorldObjectRegistry.Category.VEHICLE,
         WorldObjectRegistry.category("gfx/terobjs/vehicle/cart", false));
      Assertions.assertEquals(WorldObjectRegistry.Category.UNRESOLVED,
         WorldObjectRegistry.category("", true));
   }
}
