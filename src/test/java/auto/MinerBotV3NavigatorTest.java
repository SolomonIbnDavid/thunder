package auto;

import haven.Area;
import haven.Coord;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class MinerBotV3NavigatorTest {
    @Test
    void areaGoalsStayCenteredInsteadOfStoppingAtTheZoneBoundary() {
        Coord center = Coord.of(10, 5);
        List<Coord> goals = MinerBotV3Navigator.goals(
            new Area(Coord.of(0, 0), Coord.of(20, 10)));

        assertEquals(25, goals.size());
        assertEquals(center, goals.get(0));
        assertFalse(goals.contains(Coord.of(0, 0)));
        assertTrue(goals.stream().allMatch(tile ->
            Math.max(Math.abs(tile.x - center.x), Math.abs(tile.y - center.y)) <= 2));
    }

    @Test
    void failedLocalWaypointDoesNotSealANarrowTunnel() {
        Set<Coord> blocked = new HashSet<>();
        Coord failed = Coord.of(50, 80);
        MinerBotV3Navigator.markBarrier(blocked, failed);

        assertEquals(Set.of(failed), blocked);
        assertFalse(blocked.contains(failed.add(1, 0)));
        assertFalse(blocked.contains(failed.add(-1, 0)));
        assertFalse(blocked.contains(failed.add(0, 1)));
        assertFalse(blocked.contains(failed.add(0, -1)));
    }

    @Test
    void finalHopFailureNeverBlacklistsTheRequestedDestination() {
        List<Coord> route = List.of(
            Coord.of(0, 0), Coord.of(1, 0), Coord.of(2, 0), Coord.of(3, 0));

        assertEquals(Coord.of(2, 0),
            MinerBotV3Navigator.failureBarrier(route, 0, 3, true));
        assertEquals(Coord.of(3, 0),
            MinerBotV3Navigator.failureBarrier(route, 0, 3, false));
        assertNull(MinerBotV3Navigator.failureBarrier(route, 2, 3, true));
    }
}
