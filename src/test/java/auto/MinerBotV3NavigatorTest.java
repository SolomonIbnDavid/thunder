package auto;

import haven.Area;
import haven.Coord;
import java.util.List;
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
}
