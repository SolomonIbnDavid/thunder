package thunder.mining;

import haven.Area;
import haven.Coord;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class MinerBotV3ZoneStoreTest {
    @Test
    void convertsBetweenLiveAndSavedMapTiles() {
        Area live = new Area(Coord.of(10, 20), Coord.of(14, 25));
        Coord sessionTile = Coord.of(1000, -300);
        MinerBotV3ZoneStore.SavedArea saved = MinerBotV3ZoneStore.SavedArea.fromLive(
            77L, sessionTile, live);

        assertEquals(Coord.of(1010, -280), saved.area.ul);
        assertEquals(Coord.of(1014, -275), saved.area.br);
        assertEquals(live, saved.liveArea(sessionTile));
        assertTrue(saved.isOnSegment(77L));
        assertFalse(saved.isOnSegment(78L));
    }

    @Test
    void zonesSurviveWindowLifetimesButResetWithTheSessionIdentity() {
        MinerBotV3ZoneStore store = MinerBotV3ZoneStore.get();
        Object firstSession = new Object();
        Object secondSession = new Object();
        MinerBotV3ZoneStore.SavedArea saved = new MinerBotV3ZoneStore.SavedArea(
            5L, new Area(Coord.of(1, 2), Coord.of(3, 4)));

        store.put(firstSession, MinerBotV3ZoneStore.ROLE_STORAGE, saved);
        assertNotNull(store.get(firstSession, MinerBotV3ZoneStore.ROLE_STORAGE));
        assertNull(store.get(secondSession, MinerBotV3ZoneStore.ROLE_STORAGE));
    }
}
