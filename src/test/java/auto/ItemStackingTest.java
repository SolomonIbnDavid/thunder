package auto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ItemStackingTest {
    @Test
    void groupsLooseAndStackedByStrippedName() {
	assertEquals("Fox Meat", ItemStacking.stackKey("Fox Meat"));
	assertEquals("Fox Meat", ItemStacking.stackKey("Fox Meat, stack of"));
	assertEquals("Board", ItemStacking.stackKey("Board, stack of"));
    }

    @Test
    void skipsRingsQuantityAndUnloaded() {
	assertNull(ItemStacking.stackKey("Gold Ring"));
	assertNull(ItemStacking.stackKey("Silver Ring, stack of"));
	assertNull(ItemStacking.stackKey("0.50 kg of Flour"));
	assertNull(ItemStacking.stackKey("0.25 l of Water"));
	assertNull(ItemStacking.stackKey("???"));
	assertNull(ItemStacking.stackKey(""));
	assertNull(ItemStacking.stackKey(null));
    }

    @Test
    void detectsStackNames() {
	assertTrue(ItemStacking.isStackName("Board, stack of"));
	assertFalse(ItemStacking.isStackName("Board"));
	assertFalse(ItemStacking.isStackName(null));
    }

    @Test
    void onlyProbesPlausibleStackItems() {
	assertTrue(ItemStacking.mayStack("Chives", "gfx/invobjs/chives", 1, 1));
	assertFalse(ItemStacking.mayStack("Waterskin", "gfx/invobjs/waterskin", 1, 1));
	assertFalse(ItemStacking.mayStack("Bucket", "gfx/invobjs/bucket-water", 1, 1));
	assertFalse(ItemStacking.mayStack("Large item", "gfx/invobjs/large", 2, 1));
	assertFalse(ItemStacking.mayStack("0.25 l of Water", "gfx/invobjs/water", 1, 1));
    }

    @Test
    void twoSmallestPicksLowestThenNext() {
	assertArrayEquals(new int[] {2, 0}, ItemStacking.twoSmallest(new int[] {5, 9, 1, 8}));
	assertArrayEquals(new int[] {0, 1}, ItemStacking.twoSmallest(new int[] {1, 1, 4}));
	assertNull(ItemStacking.twoSmallest(new int[] {3}));
	assertNull(ItemStacking.twoSmallest(new int[] {}));
    }

    @Test
    void stackedDetectsFullStackNoOp() {
	assertFalse(ItemStacking.stacked(false, 4, 4));
	assertTrue(ItemStacking.stacked(true, 4, 4));
	assertTrue(ItemStacking.stacked(false, 3, 4));
    }

    @Test
    void closestPairKeepsSimilarQualitiesTogether() {
	int[] pair = ItemStacking.closestQualityPair(
	    new double[] {12, 31.2, 25.2, 30},
	    new double[] {12, 31.2, 25.2, 30},
	    new int[] {1, 1, 1, 1});
	assertArrayEquals(new int[] {1, 3}, pair);
    }

    @Test
    void closestPairUsesWholeExistingStackRange() {
	int[] pair = ItemStacking.closestQualityPair(
	    new double[] {10, 19, 20},
	    new double[] {30, 21, 22},
	    new int[] {4, 2, 2});
	assertArrayEquals(new int[] {1, 2}, pair);
    }

    @Test
    void closestPairHoldsTheSmallerPile() {
	int[] pair = ItemStacking.closestQualityPair(
	    new double[] {20, 20},
	    new double[] {20, 20},
	    new int[] {4, 1});
	assertArrayEquals(new int[] {1, 0}, pair);
    }

    @Test
    void rebuildsOnlyOverlappingQualityRanges() {
	assertArrayEquals(new boolean[] {true, true, false},
	    ItemStacking.rangesNeedingRebuild(
		new double[] {10, 15, 31},
		new double[] {20, 25, 40}));
	assertArrayEquals(new boolean[] {false, false, false},
	    ItemStacking.rangesNeedingRebuild(
		new double[] {10, 20, 30},
		new double[] {20, 30, 40}));
    }

    @Test
    void failedEqualPilesAreBothKnownFull() {
	assertTrue(ItemStacking.failedSourceIsAlsoFull(4, 4));
	assertFalse(ItemStacking.failedSourceIsAlsoFull(2, 4));
    }
}
