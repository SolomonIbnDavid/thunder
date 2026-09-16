package thunder;

import haven.Coord2d;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class FishingSearchTest {
    private static FishingSamples.Sample s(double x,double y,int bite) {
	return new FishingSamples.Sample("Pike","flax|bone|lobster",bite,100,Coord2d.of(x,y),
		Coord2d.of(x,y),.2,12,0,1);
    }

    @Test void followsIncreasingSurface() {
	List<FishingSamples.Sample> samples=Arrays.asList(s(-11,0,30),s(0,-11,40),s(0,11,40),s(11,0,70));
	FishingSearch.Guidance g=FishingSearch.guide(samples,Coord2d.z,22);
	assertNotNull(g.direction);
	assertTrue(g.direction.x>.8);
	assertEquals(70,g.bestBite);
	assertEquals(4,g.count);
    }

    @Test void sparseDataRequestsExploration() {
	FishingSearch.Guidance g=FishingSearch.guide(Arrays.asList(s(0,0,50)),Coord2d.z,22);
	assertNull(g.direction);
	assertNotNull(g.next);
	assertEquals(0,g.confidence);
    }

    @Test void twoSamplesContinueFromLowTowardHigh() {
	FishingSearch.Guidance g=FishingSearch.guide(Arrays.asList(s(0,0,20),s(22,0,40)),Coord2d.of(22,0),22);
	assertNotNull(g.direction);
	assertEquals(1,g.direction.x,.001);
	assertEquals(44,g.next.x,.001);
	assertEquals(40,g.bestBite);
    }

    @Test void collinearRiverFollowsIncreasingReadings() {
	List<FishingSamples.Sample> samples=Arrays.asList(s(0,0,35),s(22,0,33),s(44,0,31),s(66,0,29));
	FishingSearch.Guidance g=FishingSearch.guide(samples,Coord2d.of(66,0),22);
	assertNotNull(g.direction);
	assertTrue(g.direction.x<-.99,"must reverse away from the falling readings");
	assertTrue(g.next.x<66);
    }

    @Test void declaresPeakWhenLowerReadingsBracketBest() {
	List<FishingSamples.Sample> samples=Arrays.asList(s(-44,0,49),s(-22,0,51),s(0,0,52),s(22,0,51),s(44,0,48));
	FishingSearch.Guidance g=FishingSearch.guide(samples,Coord2d.of(44,0),22);
	assertTrue(g.peak);
	assertNull(g.next);
	assertEquals(52,g.bestBite);
	assertEquals(0,g.best.x,.001);
    }

    @Test void doesNotDeclarePeakAtEndOfSampledRun() {
	List<FishingSamples.Sample> samples=Arrays.asList(s(0,0,49),s(22,0,51),s(44,0,52));
	FishingSearch.Guidance g=FishingSearch.guide(samples,Coord2d.of(44,0),22);
	assertFalse(g.peak);
	assertNotNull(g.next);
    }

    @Test void inverseDistanceHonorsExactObservation() {
	List<FishingSamples.Sample> samples=Arrays.asList(s(0,0,30),s(22,0,70));
	assertEquals(30,FishingSearch.estimate(samples,Coord2d.z,100),.001);
	assertEquals(50,FishingSearch.estimate(samples,Coord2d.of(11,0),100),.001);
    }
}
