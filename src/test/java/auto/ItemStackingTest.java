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
    void failedEqualPilesAreBothKnownFull() {
	assertTrue(ItemStacking.failedSourceIsAlsoFull(4, 4));
	assertFalse(ItemStacking.failedSourceIsAlsoFull(2, 4));
    }

    @Test
    void plansTheLargestInPlaceQualitySwap() {
	assertArrayEquals(new int[] {0, 1, 2, 0},
	    ItemStacking.nextQualitySwap(new double[][] {
		{10, 40},
		{20, 21},
		{15, 30}
	    }));
	assertNull(ItemStacking.nextQualitySwap(new double[][] {
		{10, 20},
		{20, 30},
		{31, 40}
	    }));
    }

    @Test
    void qualitySwapIgnoresUnknownQualities() {
	assertArrayEquals(new int[] {0, 1, 1, 1},
	    ItemStacking.nextQualitySwap(new double[][] {
		{Double.NaN, 30},
		{Double.NaN, 20}
	    }));
	assertNull(ItemStacking.nextQualitySwap(new double[][] {
		{Double.NaN},
		{Double.NaN}
	    }));
    }

    @Test
    void repeatedQualitySwapsConvergeToSeparateBands() {
	double[][] qualities = {
	    {10, 60, 30, 40},
	    {55, 15, 45, 25},
	    {20, 50, 35, 65}
	};
	int swaps = 0;
	int[] move;
	while((move = ItemStacking.nextQualitySwap(qualities)) != null) {
	    double held = qualities[move[0]][move[1]];
	    qualities[move[0]][move[1]] = qualities[move[2]][move[3]];
	    qualities[move[2]][move[3]] = held;
	    assertTrue(++swaps < 50, "quality planner did not converge");
	}
	for(int lower = 0; lower < qualities.length; lower++) {
	    for(int higher = lower + 1; higher < qualities.length; higher++) {
		for(double lowBand : qualities[lower])
		    for(double highBand : qualities[higher])
			assertTrue(lowBand <= highBand);
	    }
	}
    }
}
