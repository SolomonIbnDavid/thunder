package auto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MapHelperTest {
    @Test
    void minedFloorClassificationIsConservative() {
        assertTrue(MapHelper.isMinedFloorName("gfx/tiles/mine"));
        assertTrue(MapHelper.isMinedFloorName("gfx/tiles/cave"));
        assertFalse(MapHelper.isMinedFloorName("gfx/tiles/caveobsidian"));
        assertFalse(MapHelper.isMinedFloorName(null));
    }
}
