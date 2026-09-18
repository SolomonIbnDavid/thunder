package thunder;

import haven.Coord2d;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class SpatialPeakSearchTest {
    private static SpatialPeakSearch.Sample sample(double x, double y, double value) {
	return new SpatialPeakSearch.Sample() {
	    public Coord2d point() {return Coord2d.of(x, y);}
	    public double value() {return value;}
	};
    }

    @Test void followsRisingSurface() {
	List<SpatialPeakSearch.Sample> samples = Arrays.asList(
	    sample(-11, 0, 30), sample(0, -11, 40), sample(0, 11, 40), sample(11, 0, 70));
	SpatialPeakSearch.Guidance result = SpatialPeakSearch.guide(samples, Coord2d.z, 11);
	assertNotNull(result.direction);
	assertTrue(result.direction.x > .8);
	assertEquals(70, result.bestValue, .001);
    }

    @Test void equalSparseValuesExploreWithoutInventingDirection() {
	SpatialPeakSearch.Guidance result = SpatialPeakSearch.guide(
	    Arrays.asList(sample(0, 0, 50), sample(11, 0, 50)), Coord2d.of(11, 0), 11);
	assertNull(result.direction);
	assertNotNull(result.next);
	assertEquals(0, result.confidence);
    }

    @Test void collinearSamplesReverseTowardHigherValues() {
	SpatialPeakSearch.Guidance result = SpatialPeakSearch.guide(Arrays.asList(
	    sample(0, 0, 40), sample(11, 0, 35), sample(22, 0, 30)), Coord2d.of(22, 0), 11);
	assertNotNull(result.direction);
	assertTrue(result.direction.x < -.99);
    }

    @Test void lowerReadingsBracketPeak() {
	SpatialPeakSearch.Guidance result = SpatialPeakSearch.guide(Arrays.asList(
	    sample(-22, 0, 49), sample(-11, 0, 51), sample(0, 0, 52),
	    sample(11, 0, 51), sample(22, 0, 48)), Coord2d.of(22, 0), 11);
	assertTrue(result.peak);
	assertNull(result.next);
	assertEquals(0, result.best.x, .001);
    }

    @Test void smoothingHonorsExactObservation() {
	List<SpatialPeakSearch.Sample> samples = Arrays.asList(sample(0, 0, 30), sample(22, 0, 70));
	assertEquals(30, SpatialPeakSearch.estimate(samples, Coord2d.z, 100), .001);
	assertEquals(50, SpatialPeakSearch.estimate(samples, Coord2d.of(11, 0), 100), .001);
    }
}
