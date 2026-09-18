package auto;

import haven.Coord;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class MinerBotV3LogicTest {
    @Test
    void fixedLinesAndRightColumnsUseTheCurrentHeading() {
        Coord anchor = Coord.of(100, 200);
        assertLeg(anchor, MinerBotV3Logic.Direction.NORTH, Coord.of(100, 189), Coord.of(101, 189));
        assertLeg(anchor, MinerBotV3Logic.Direction.EAST, Coord.of(111, 200), Coord.of(111, 201));
        assertLeg(anchor, MinerBotV3Logic.Direction.SOUTH, Coord.of(100, 211), Coord.of(99, 211));
        assertLeg(anchor, MinerBotV3Logic.Direction.WEST, Coord.of(89, 200), Coord.of(89, 199));
    }

    @Test
    void redrawPreservesTheOriginalEndpoint() {
        Coord anchor = Coord.of(10, 10);
        MinerBotV3Logic.Line line = MinerBotV3Logic.remainingLine(
            anchor, MinerBotV3Logic.Direction.EAST, 6);
        assertEquals(Coord.of(17, 10), line.start);
        assertEquals(Coord.of(21, 10), line.end);
        assertEquals(6, MinerBotV3Logic.completedTiles(anchor,
            MinerBotV3Logic.Direction.EAST, Coord.of(16, 10)));
        assertEquals(0, MinerBotV3Logic.completedTiles(anchor,
            MinerBotV3Logic.Direction.EAST, Coord.of(16, 11)));
        MinerBotV3Logic.Line complete = MinerBotV3Logic.remainingLine(
            anchor, MinerBotV3Logic.Direction.EAST, MinerBotV3Logic.LEG_TILES);
        assertEquals(Coord.of(21, 10), complete.start);
        assertEquals(complete.start, complete.end);
    }

    @Test
    void doglegCandidatesHaveTheAgreedDeterministicOrder() {
        List<MinerBotV3Logic.DetourCandidate> candidates = MinerBotV3Logic.detourCandidates();
        assertEquals(8, candidates.size());
        assertCandidate(candidates.get(0), 1, MinerBotV3Logic.Side.RIGHT, 1);
        assertCandidate(candidates.get(1), 1, MinerBotV3Logic.Side.LEFT, 1);
        assertCandidate(candidates.get(2), 1, MinerBotV3Logic.Side.RIGHT, 2);
        assertCandidate(candidates.get(3), 1, MinerBotV3Logic.Side.LEFT, 2);
        assertCandidate(candidates.get(4), 2, MinerBotV3Logic.Side.RIGHT, 1);
        assertCandidate(candidates.get(5), 2, MinerBotV3Logic.Side.LEFT, 1);
        assertCandidate(candidates.get(6), 2, MinerBotV3Logic.Side.RIGHT, 2);
        assertCandidate(candidates.get(7), 2, MinerBotV3Logic.Side.LEFT, 2);
        assertEquals(MinerBotV3Logic.Direction.SOUTH,
            candidates.get(0).heading(MinerBotV3Logic.Direction.EAST));
        assertEquals(MinerBotV3Logic.Direction.NORTH,
            candidates.get(1).heading(MinerBotV3Logic.Direction.EAST));
    }

    @Test
    void miningMessagesAndEnergyUseTheV3Thresholds() {
        assertTrue(MinerBotV3Logic.tooHardMessage("This rock is much too hard for you to mine."));
        assertTrue(MinerBotV3Logic.tooHardMessage("TOO HARD"));
        assertFalse(MinerBotV3Logic.tooHardMessage("You are too tired."));
        assertTrue(MinerBotV3Logic.needsEnergy(0.249));
        assertFalse(MinerBotV3Logic.needsEnergy(0.25));
        assertTrue(MinerBotV3Logic.energyTargetReached(0.80));
        assertFalse(MinerBotV3Logic.needsBarRefill(10));
        assertFalse(MinerBotV3Logic.needsBarRefill(1));
        assertTrue(MinerBotV3Logic.needsBarRefill(0));
        assertTrue(MinerBotV3Logic.barBatchRestored(10, 10));
        assertFalse(MinerBotV3Logic.barBatchRestored(9, 10));
        assertTrue(MinerBotV3Logic.needsBarSupply(4, 10, false));
        assertFalse(MinerBotV3Logic.needsBarSupply(10, 10, false));
        assertFalse(MinerBotV3Logic.needsBarSupply(4, 10, true));
        assertTrue(MinerBotV3Logic.needsBarSupply(0, 10, true));
        assertTrue(MinerBotV3Logic.shouldCollectRouteStone(13, true));
        assertFalse(MinerBotV3Logic.shouldCollectRouteStone(13, false));
        assertTrue(MinerBotV3Logic.waterRefillSucceeded(false, true));
        assertFalse(MinerBotV3Logic.waterRefillSucceeded(true, false));
    }

    @Test
    void supplySourcesPreferTheRightContainerWithoutExcludingFallbacks() {
        int stonePile = MinerBotV3Logic.supplySourcePriority(
            MinerBotV3Logic.Supply.STONE, "gfx/terobjs/stockpile-stone", false, false, false);
        int fullChest = MinerBotV3Logic.supplySourcePriority(
            MinerBotV3Logic.Supply.STONE, "gfx/terobjs/lchest", false, false, true);
        int genericChest = MinerBotV3Logic.supplySourcePriority(
            MinerBotV3Logic.Supply.STONE, "gfx/terobjs/lchest", false, false, false);
        int emptyChest = MinerBotV3Logic.supplySourcePriority(
            MinerBotV3Logic.Supply.STONE, "gfx/terobjs/lchest", false, true, false);
        int water = MinerBotV3Logic.supplySourcePriority(
            MinerBotV3Logic.Supply.STONE, "gfx/terobjs/barrel", true, false, false);
        assertTrue(stonePile < fullChest);
        assertTrue(fullChest < genericChest);
        assertTrue(genericChest < emptyChest);
        assertTrue(emptyChest < water);

        int barsFullChest = MinerBotV3Logic.supplySourcePriority(
            MinerBotV3Logic.Supply.BARS, "gfx/terobjs/lchest", false, false, true);
        int barsGeneric = MinerBotV3Logic.supplySourcePriority(
            MinerBotV3Logic.Supply.BARS, "gfx/terobjs/crate", false, false, false);
        int barsStonePile = MinerBotV3Logic.supplySourcePriority(
            MinerBotV3Logic.Supply.BARS, "gfx/terobjs/stockpile-stone", false, false, false);
        assertTrue(barsFullChest < barsGeneric);
        assertTrue(barsGeneric < barsStonePile);
    }

    @Test
    void redrawFailureRequiresThreeConsecutiveNoProgressCycles() {
        MinerBotV3Logic.RedrawProgress progress = new MinerBotV3Logic.RedrawProgress(0);
        assertFalse(progress.failedAfter(4));
        assertFalse(progress.failedAfter(4));
        assertFalse(progress.failedAfter(7));
        assertFalse(progress.failedAfter(7));
        assertFalse(progress.failedAfter(7));
        assertTrue(progress.failedAfter(7));
        assertEquals(3, progress.noProgressCount());
    }

    @Test
    void trailStopsAreBoundedAndRestoreTheExactFrontier() {
        assertEquals(List.of(12, 4, 0), MinerBotV3Logic.trailStops(20, 0, 8));
        assertEquals(List.of(8, 16, 20), MinerBotV3Logic.trailStops(0, 20, 8));
        assertTrue(MinerBotV3Logic.trailStops(5, 5, 8).isEmpty());
    }

    private static void assertLeg(Coord anchor, MinerBotV3Logic.Direction direction,
                                  Coord endpoint, Coord column) {
        assertEquals(endpoint, MinerBotV3Logic.endpoint(anchor, direction));
        assertEquals(column, MinerBotV3Logic.columnTile(anchor, direction));
        MinerBotV3Logic.Line full = MinerBotV3Logic.remainingLine(anchor, direction, 0);
        assertEquals(anchor.add(direction.step()), full.start);
        assertEquals(endpoint, full.end);
        MinerBotV3Logic.Line partial = MinerBotV3Logic.remainingLine(anchor, direction, 6);
        assertEquals(anchor.add(direction.step().mul(7)), partial.start);
        assertEquals(endpoint, partial.end);
    }

    private static void assertCandidate(MinerBotV3Logic.DetourCandidate candidate,
                                        int back, MinerBotV3Logic.Side side, int legs) {
        assertEquals(back, candidate.retreatLegs);
        assertEquals(side, candidate.side);
        assertEquals(legs, candidate.sideLegs);
    }
}
