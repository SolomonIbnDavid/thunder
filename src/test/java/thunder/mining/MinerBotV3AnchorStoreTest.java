package thunder.mining;

import auto.MinerBotV3Logic;
import haven.Coord;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class MinerBotV3AnchorStoreTest {
    @Test
    void convertsBetweenLiveAndSavedMapTiles() {
        MinerBotV3AnchorStore.SavedAnchor saved =
            MinerBotV3AnchorStore.SavedAnchor.fromLive(77L,
                Coord.of(1000, -300), Coord.of(10, 20),
                MinerBotV3Logic.Direction.EAST, true);

        assertEquals(Coord.of(1010, -280), saved.tile);
        assertEquals(Coord.of(10, 20), saved.liveTile(Coord.of(1000, -300)));
        assertEquals(MinerBotV3Logic.Direction.EAST, saved.heading);
        assertTrue(saved.manuallyPicked);
        assertTrue(saved.isOnSegment(77L));
        assertFalse(saved.isOnSegment(78L));
    }

    @Test
    void checkpointSurvivesWindowLifetimesButResetsWithSessionIdentity() {
        MinerBotV3AnchorStore store = MinerBotV3AnchorStore.get();
        Object firstSession = new Object();
        Object secondSession = new Object();
        MinerBotV3AnchorStore.SavedAnchor first =
            new MinerBotV3AnchorStore.SavedAnchor(5L, Coord.of(12, 34),
                MinerBotV3Logic.Direction.NORTH, false);

        store.put(firstSession, first);
        MinerBotV3AnchorStore.SavedAnchor copy = store.get(firstSession);
        assertNotSame(first, copy);
        assertEquals(first.tile, copy.tile);
        assertEquals(first.heading, copy.heading);
        assertNull(store.get(secondSession));
    }

    @Test
    void laterFrontierReplacesThePriorCheckpointAndCanBeCleared() {
        MinerBotV3AnchorStore store = MinerBotV3AnchorStore.get();
        Object session = new Object();
        store.put(session, new MinerBotV3AnchorStore.SavedAnchor(9L,
            Coord.of(1, 2), MinerBotV3Logic.Direction.SOUTH, true));
        store.put(session, new MinerBotV3AnchorStore.SavedAnchor(9L,
            Coord.of(1, 13), MinerBotV3Logic.Direction.SOUTH, false));

        MinerBotV3AnchorStore.SavedAnchor saved = store.get(session);
        assertEquals(Coord.of(1, 13), saved.tile);
        assertFalse(saved.manuallyPicked);
        store.clear(session);
        assertNull(store.get(session));
    }
}
