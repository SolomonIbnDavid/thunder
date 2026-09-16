package haven.pathfinding;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class PathfinderNearbyTest {
   @Test
   void emptyQueryMatchesEverything() {
      Assertions.assertTrue(PathfinderCommands.matches("", 12L, "Maple Tree", "gfx/terobjs/trees/maple"));
      Assertions.assertTrue(PathfinderCommands.matches("  ", 12L, "Maple Tree", "gfx/terobjs/trees/maple"));
      Assertions.assertTrue(PathfinderCommands.matches(null, 12L, "Maple Tree", "gfx/terobjs/trees/maple"));
   }

   @Test
   void matchesPrettyNameResidAndId() {
      Assertions.assertTrue(PathfinderCommands.matches("maple", 99L, "Maple Tree", "gfx/terobjs/trees/maple"));
      Assertions.assertTrue(PathfinderCommands.matches("terobjs/trees", 99L, "Maple Tree", "gfx/terobjs/trees/maple"));
      Assertions.assertTrue(PathfinderCommands.matches("99", 99L, "Maple Tree", "gfx/terobjs/trees/maple"));
      Assertions.assertFalse(PathfinderCommands.matches("birch", 99L, "Maple Tree", "gfx/terobjs/trees/maple"));
   }
}
