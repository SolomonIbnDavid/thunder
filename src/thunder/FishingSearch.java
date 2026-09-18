package thunder;

import haven.Coord2d;

import java.util.List;

/** Small weighted least-squares surface fit: bite = a + bx + cy. */
public final class FishingSearch {
    public static final class Guidance {
	public final Coord2d direction, next, best;
	public final int bestBite, count;
	public final double confidence;
	public final boolean peak;
	Guidance(Coord2d direction, Coord2d next, Coord2d best, int bestBite, int count, double confidence, boolean peak) {
	    this.direction = direction; this.next = next; this.best = best;
	    this.bestBite = bestBite; this.count = count; this.confidence = confidence; this.peak = peak;
	}
    }

    private FishingSearch() {}

    /** Inverse-distance smoothing for the visual surface; returns NaN outside sampled influence. */
    public static double estimate(List<FishingSamples.Sample> in, Coord2d at, double radius) {
	return SpatialPeakSearch.estimate(in, at, radius);
    }

    public static Guidance guide(List<FishingSamples.Sample> in, Coord2d from, double step) {
	SpatialPeakSearch.Guidance guidance = SpatialPeakSearch.guide(in, from, step);
	return new Guidance(guidance.direction, guidance.next, guidance.best,
		guidance.count == 0 ? -1 : (int)Math.round(guidance.bestValue), guidance.count,
		guidance.confidence, guidance.peak);
    }

}
