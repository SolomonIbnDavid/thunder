package thunder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TileQualityMiningLogTest {
    @Test
    void automaticGroundLabelsAndExplicitMapMarkersAreDistinct() {
        assertTrue(TileQuality.isGroundQualityMarkerName("[TQ] Quarry Quartz q87.4"));
        assertFalse(TileQuality.isGroundQualityMarkerName("[Mine] Quarry Quartz q87.4"));
        assertEquals("[Mine] Quarry Quartz q87.4",
            TileQuality.mapMarkerName("stone/quarry-quartz", (short)874));
    }

    @Test
    void miningResultGraceIsLongEnoughForDelayedGemInfoButBounded() {
        assertTrue(TileQuality.MINE_RESULT_GRACE_MS >= 3000);
        assertTrue(TileQuality.MINE_RESULT_GRACE_MS <= 10000);
        long now = System.currentTimeMillis();
        TileQuality.PendingAction lateGem = new TileQuality.PendingAction(
            TileQuality.GROUP_MINE, haven.Coord2d.of(10, 20), now + 1000);
        assertFalse(lateGem.expired());
        TileQuality.PendingAction stale = new TileQuality.PendingAction(
            TileQuality.GROUP_MINE, haven.Coord2d.of(10, 20), now - 1);
        assertTrue(stale.expired());
    }
}
