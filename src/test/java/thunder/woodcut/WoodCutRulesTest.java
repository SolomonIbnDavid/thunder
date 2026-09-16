package thunder.woodcut;

import haven.Coord2d;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class WoodCutRulesTest {
    @Test public void recognizesLogsWithoutMatchingLivingTrees() {
        Assertions.assertTrue(WoodCutRules.isLog("gfx/terobjs/trees/yewlog"));
        Assertions.assertTrue(WoodCutRules.isLog("gfx/terobjs/trees/oakoldtrunk"));
        Assertions.assertFalse(WoodCutRules.isLog("gfx/terobjs/trees/yew"));
    }

    @Test public void recognizesCartAndProducts() {
        Assertions.assertTrue(WoodCutRules.isCart("gfx/terobjs/vehicle/cart"));
        Assertions.assertTrue(WoodCutRules.isProduct("gfx/invobjs/board-yew", WoodCutRules.Product.BOARDS));
        Assertions.assertTrue(WoodCutRules.isProduct("gfx/invobjs/wblock-yew", WoodCutRules.Product.BLOCKS));
        Assertions.assertFalse(WoodCutRules.isProduct("gfx/invobjs/wblock-yew", WoodCutRules.Product.BOARDS));
    }

    @Test public void usesObservedStockpileResources() {
        Assertions.assertEquals("gfx/terobjs/stockpile-board", WoodCutRules.Product.BOARDS.stockpile);
        Assertions.assertEquals("gfx/terobjs/stockpile-wblock", WoodCutRules.Product.BLOCKS.stockpile);
        Assertions.assertEquals(4, WoodCutRules.Product.BOARDS.inventoryCells);
        Assertions.assertEquals(2, WoodCutRules.Product.BLOCKS.inventoryCells);
    }

    @Test public void decodesOnlyOccupiedCartCargoSlots() {
        Assertions.assertTrue(WoodCutRules.occupiedCartSlots(0).isEmpty());
        Assertions.assertEquals(java.util.Arrays.asList(2, 4, 7),
            WoodCutRules.occupiedCartSlots(4 | 16 | 128));
        Assertions.assertTrue(WoodCutRules.occupiedCartSlots(-1).isEmpty());
    }

    @Test public void stockpileFootprintsRejectOverlapAndTouchingEdges() {
        Coord2d existing = Coord2d.of(0, 0);
        Assertions.assertTrue(WoodCutBot.pileFootprintsConflict(Coord2d.of(0, 0), existing, 22));
        Assertions.assertTrue(WoodCutBot.pileFootprintsConflict(Coord2d.of(22, 0), existing, 22));
        Assertions.assertTrue(WoodCutBot.pileFootprintsConflict(Coord2d.of(22.005, 0), existing, 22));
        Assertions.assertFalse(WoodCutBot.pileFootprintsConflict(Coord2d.of(33, 0), existing, 22));
    }
}
