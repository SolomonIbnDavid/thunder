package thunder;

import haven.Coord2d;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class DirectionalForagerLogicTest {
    @Test void compassDirectionsUseWorldAxes() {
        Coord2d p = Coord2d.of(100, 200);
        assertPoint(100, 190, DirectionalForagerLogic.forward(p, DirectionalForagerLogic.Direction.NORTH, 10));
        assertPoint(100, 210, DirectionalForagerLogic.forward(p, DirectionalForagerLogic.Direction.SOUTH, 10));
        assertPoint(90, 200, DirectionalForagerLogic.forward(p, DirectionalForagerLogic.Direction.WEST, 10));
        assertPoint(110, 200, DirectionalForagerLogic.forward(p, DirectionalForagerLogic.Direction.EAST, 10));
    }

    @Test void nearestHonorsWhitelistAndFailedTargets() {
        List<DirectionalForagerLogic.Candidate> c = Arrays.asList(
            new DirectionalForagerLogic.Candidate(1, "dandelion", Coord2d.of(2, 0)),
            new DirectionalForagerLogic.Candidate(2, "rustroot", Coord2d.of(3, 0)),
            new DirectionalForagerLogic.Candidate(3, "dandelion", Coord2d.of(4, 0)));
        Set<String> selected = Collections.singleton("dandelion");
        assertEquals(1, DirectionalForagerLogic.nearest(Coord2d.z, c, selected, Collections.emptySet()).id);
        assertEquals(3, DirectionalForagerLogic.nearest(Coord2d.z, c, selected, Collections.singleton(1L)).id);
    }

    @Test void noSelectionMeansNoTarget() {
        DirectionalForagerLogic.Candidate c = new DirectionalForagerLogic.Candidate(1, "dandelion", Coord2d.z);
        assertNull(DirectionalForagerLogic.nearest(Coord2d.z, Collections.singleton(c), Collections.emptySet(), Collections.emptySet()));
    }

    @Test void caveProbesRotateAroundPreferredHeading() {
        Coord2d p = Coord2d.of(100, 100);
        assertPoint(100, 90, DirectionalForagerLogic.probe(p, DirectionalForagerLogic.Direction.NORTH, 10, 0));
        assertPoint(110, 100, DirectionalForagerLogic.probe(p, DirectionalForagerLogic.Direction.NORTH, 10, Math.PI / 2));
        assertEquals(10, DirectionalForagerLogic.forwardProgress(p, Coord2d.of(100, 90), DirectionalForagerLogic.Direction.NORTH), 0.0001);
        assertEquals(0, DirectionalForagerLogic.forwardProgress(p, Coord2d.of(110, 100), DirectionalForagerLogic.Direction.NORTH), 0.0001);
    }

    @Test void dangerRadiusRejectsOnlyPointsInsideIt() {
        List<Coord2d> dangers = Collections.singletonList(Coord2d.of(100, 100));
        assertFalse(DirectionalForagerLogic.safeFrom(Coord2d.of(109, 100), dangers, 10));
        assertTrue(DirectionalForagerLogic.safeFrom(Coord2d.of(110, 100), dangers, 10));
        assertTrue(DirectionalForagerLogic.safeFrom(Coord2d.of(200, 200), dangers, 10));
    }

    @Test void catalogNormalizesWorldAndInventoryResources() {
        assertEquals("spindlytaproot", ForageCatalog.key("gfx/terobjs/herbs/spindlytaproot"));
        assertEquals("spindlytaproot", ForageCatalog.key("gfx/invobjs/herbs/spindlytaproot"));
        Set<String> keys = new HashSet<>();
        for(ForageCatalog.Entry entry : ForageCatalog.entries()) keys.add(entry.key);
        assertTrue(keys.containsAll(Arrays.asList("perfectautumnleaf", "tansy", "windweed", "champignon", "clay-gray", "lakesnail")));
    }

    private static void assertPoint(double x, double y, Coord2d p) {
        assertEquals(x, p.x, 0.0001); assertEquals(y, p.y, 0.0001);
    }
}
