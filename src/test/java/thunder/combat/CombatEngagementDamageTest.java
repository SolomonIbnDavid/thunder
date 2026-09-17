package thunder.combat;

import haven.GobDamageInfo;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CombatEngagementDamageTest {
    @Test void accumulatesEveryDamageChannelFromTheEngagementBaseline() {
        Harness harness = new Harness();
        harness.set(1, 100, 20, 5);
        harness.update(1L);
        assertDamage(harness.tracker.snapshot(1), 0, 0, 0);
        harness.set(1, 130, 27, 9);
        harness.update(1L);
        assertDamage(harness.tracker.snapshot(1), 30, 7, 4);
        assertEquals(41, harness.tracker.snapshot(1).total);
    }

    @Test void manualCounterClearingDoesNotEraseAccumulatedEngagementDamage() {
        Harness harness = new Harness();
        harness.set(1, 100, 10, 0);
        harness.update(1L);
        harness.set(1, 125, 30, 5);
        harness.update(1L);
        harness.set(1, 0, 0, 0);
        harness.update(1L);
        assertDamage(harness.tracker.snapshot(1), 25, 20, 5);
        harness.set(1, 7, 3, 2);
        harness.update(1L);
        assertDamage(harness.tracker.snapshot(1), 32, 23, 7);
    }

    @Test void targetSwitchingKeepsTotalsForEveryActiveRelation() {
        Harness harness = new Harness();
        harness.set(1, 0, 0, 0);
        harness.set(2, 50, 0, 0);
        harness.update(1L, 2L);
        harness.set(1, 10, 0, 0);
        harness.set(2, 55, 4, 0);
        harness.update(1L, 2L);
        assertDamage(harness.tracker.snapshot(1), 10, 0, 0);
        assertDamage(harness.tracker.snapshot(2), 5, 4, 0);
    }

    @Test void relationRemovalResetsItsEngagementAndReaddingStartsANewBaseline() {
        Harness harness = new Harness();
        harness.set(1, 0, 0, 0);
        harness.update(1L);
        harness.set(1, 20, 0, 0);
        harness.update(1L);
        assertTrue(harness.tracker.tracks(1));
        harness.update();
        assertFalse(harness.tracker.tracks(1));
        harness.set(1, 50, 0, 0);
        harness.update(1L);
        assertDamage(harness.tracker.snapshot(1), 0, 0, 0);
    }

    private static void assertDamage(GobDamageInfo.DamageSnapshot snapshot,
                                     int armor, int shp, int hhp) {
        assertEquals(armor, snapshot.armor);
        assertEquals(shp, snapshot.shp);
        assertEquals(hhp, snapshot.hhp);
    }

    private static final class Harness {
        final CombatEngagementDamage tracker = new CombatEngagementDamage();
        final Map<Long, GobDamageInfo.DamageSnapshot> current = new HashMap<>();

        void set(long id, int armor, int shp, int hhp) {
            current.put(id, new GobDamageInfo.DamageSnapshot(armor, shp, hhp));
        }

        void update(Long... ids) {
            tracker.update(ids.length == 0 ? Collections.emptyList() : Arrays.asList(ids),
                id -> current.get(id));
        }
    }
}
