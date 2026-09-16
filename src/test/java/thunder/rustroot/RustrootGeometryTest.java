package thunder.rustroot;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class RustrootGeometryTest {
    @Test
    void firstSuggestionCreatesPerpendicularBaseline() {
        RustrootGeometry.Reading east = new RustrootGeometry.Reading(
            new RustrootGeometry.Point(5.5, 5.5), -0.2, 0.2, 30, 220);
        RustrootGeometry.Point next = RustrootGeometry.suggest(Collections.singletonList(east));
        Assertions.assertEquals(5.5, next.x, 0.001);
        Assertions.assertTrue(next.y > 5.5);
    }

    @Test
    void intersectingConesRetainTargetAndRejectOutsideTiles() {
        RustrootGeometry.Point target = new RustrootGeometry.Point(104.5, 104.5);
        RustrootGeometry.Reading east = coneToward(new RustrootGeometry.Point(5.5, 104.5), target, 0.20, 220);
        RustrootGeometry.Reading south = coneToward(new RustrootGeometry.Point(104.5, 5.5), target, 0.20, 220);
        List<RustrootGeometry.Point> candidates = RustrootGeometry.candidates(Arrays.asList(east, south));
        Assertions.assertTrue(candidates.stream().anyMatch(p -> p.distance(target) < 1));
        Assertions.assertFalse(candidates.stream().anyMatch(p -> p.x < 50 || p.y < 50));
    }

    @Test
    void wrappedAngleConeWorksAcrossPiBoundary() {
        RustrootGeometry.Reading west = new RustrootGeometry.Reading(
            new RustrootGeometry.Point(104.5, 104.5), 3.0, -3.0, 30, 220);
        Assertions.assertTrue(west.contains(new RustrootGeometry.Point(5.5, 104.5)));
        Assertions.assertFalse(west.contains(new RustrootGeometry.Point(203.5, 104.5)));
    }

    @Test
    void oreClassificationAcceptsResourceAndAliasButRejectsOrdinaryRock() {
        Assertions.assertTrue(RustrootGeometry.isOre("gfx/tiles/rocks/cassiterite"));
        Assertions.assertTrue(RustrootGeometry.isOre("Magnetite (Black ore)"));
        Assertions.assertFalse(RustrootGeometry.isOre("Gneiss"));
        Assertions.assertFalse(RustrootGeometry.isOre("Quartz"));
    }

    private static RustrootGeometry.Reading coneToward(RustrootGeometry.Point origin,
                                                        RustrootGeometry.Point target,
                                                        double halfWidth, double range) {
        double angle = Math.atan2(-(target.y - origin.y), target.x - origin.x);
        return new RustrootGeometry.Reading(origin, angle - halfWidth, angle + halfWidth, 30, range);
    }
}
