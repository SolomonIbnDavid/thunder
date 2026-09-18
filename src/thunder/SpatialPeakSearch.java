package thunder;

import haven.Coord2d;

import java.util.List;

/** Shared local surface fit used by the fishing and mining heat maps. */
public final class SpatialPeakSearch {
    public interface Sample {
	Coord2d point();
	double value();
    }

    public static final class Guidance {
	public final Coord2d direction, next, best;
	public final double bestValue;
	public final int count;
	public final double confidence;
	public final boolean peak;

	Guidance(Coord2d direction, Coord2d next, Coord2d best, double bestValue,
		 int count, double confidence, boolean peak) {
	    this.direction = direction;
	    this.next = next;
	    this.best = best;
	    this.bestValue = bestValue;
	    this.count = count;
	    this.confidence = confidence;
	    this.peak = peak;
	}
    }

    private SpatialPeakSearch() {}

    /** Inverse-distance smoothing; returns NaN outside sampled influence. */
    public static double estimate(List<? extends Sample> in, Coord2d at, double radius) {
	if(in == null || in.isEmpty() || at == null) {return Double.NaN;}
	double sum = 0, weights = 0;
	int nearby = 0;
	for(Sample sample : in) {
	    double d = sample.point().dist(at);
	    if(d < 0.1) {return sample.value();}
	    if(d > radius) {continue;}
	    double w = 1.0 / (d * d);
	    sum += w * sample.value();
	    weights += w;
	    nearby++;
	}
	return nearby < 2 || weights == 0 ? Double.NaN : sum / weights;
    }

    public static Guidance guide(List<? extends Sample> in, Coord2d from, double step) {
	if(in == null || in.isEmpty()) {return new Guidance(null, null, null, Double.NaN, 0, 0, false);}
	Sample best = in.get(0);
	for(Sample sample : in) {if(sample.value() > best.value()) {best = sample;}}
	Sample summit = bracketedPeak(in, best.value(), step);
	if(summit != null) {
	    return new Guidance(null, null, summit.point(), summit.value(), in.size(), 1.0, true);
	}
	if(from == null) {from = best.point();}
	if(in.size() == 1) {
	    return new Guidance(null, unexplored(in, best.point(), step), best.point(), best.value(), 1, 0, false);
	}
	if(in.size() == 2) {
	    Sample a = in.get(0), b = in.get(1);
	    if(a.value() == b.value() || a.point().dist(b.point()) < 1e-6) {
		return new Guidance(null, unexplored(in, best.point(), step), best.point(), best.value(), 2, 0, false);
	    }
	    Sample low = a.value() < b.value() ? a : b;
	    Sample high = a.value() > b.value() ? a : b;
	    Coord2d delta = high.point().sub(low.point());
	    double n = Math.hypot(delta.x, delta.y);
	    Coord2d dir = Coord2d.of(delta.x / n, delta.y / n);
	    return new Guidance(dir, high.point().add(dir.mul(step)), high.point(), high.value(), 2, .25, false);
	}

	double sw = 0, sx = 0, sy = 0, sz = 0, sxx = 0, syy = 0, sxy = 0, sxz = 0, syz = 0;
	for(Sample sample : in) {
	    double dx = sample.point().x - from.x, dy = sample.point().y - from.y;
	    double dist = Math.hypot(dx, dy);
	    double w = 1.0 / (1.0 + dist / 110.0);
	    sw += w; sx += w * dx; sy += w * dy; sz += w * sample.value();
	    sxx += w * dx * dx; syy += w * dy * dy; sxy += w * dx * dy;
	    sxz += w * dx * sample.value(); syz += w * dy * sample.value();
	}
	double axx = sxx - sx * sx / sw, ayy = syy - sy * sy / sw, axy = sxy - sx * sy / sw;
	double bx = sxz - sx * sz / sw, by = syz - sy * sz / sw;
	double det = axx * ayy - axy * axy;
	Coord2d dir = null;
	if(Math.abs(det) > 1e-7) {
	    double gx = (bx * ayy - by * axy) / det, gy = (by * axx - bx * axy) / det;
	    double n = Math.hypot(gx, gy);
	    if(n > 1e-6) {dir = Coord2d.of(gx / n, gy / n);}
	} else {
	    double n = Math.hypot(bx, by);
	    if(n > 1e-6) {dir = Coord2d.of(bx / n, by / n);}
	}
	double spread = Math.sqrt(Math.max(0, (axx + ayy) / sw));
	double conf = Math.min(1.0, (in.size() - 2) / 6.0) * Math.min(1.0, spread / Math.max(1, step));
	Coord2d next = dir == null ? unexplored(in, best.point(), step) : best.point().add(dir.mul(step));
	if(tooClose(in, next, step * .45)) {next = unexplored(in, best.point(), step);}
	return new Guidance(dir, next, best.point(), best.value(), in.size(), conf, false);
    }

    private static Sample bracketedPeak(List<? extends Sample> in, double bestValue, double step) {
	if(in.size() < 3) {return null;}
	double reach = Math.max(step, 1) * 8.0;
	for(Sample top : in) {
	    if(top.value() != bestValue) {continue;}
	    for(Sample a : in) {
		if(a == top || a.value() >= top.value() || a.point().dist(top.point()) > reach) {continue;}
		double ax = a.point().x - top.point().x, ay = a.point().y - top.point().y;
		double an = Math.hypot(ax, ay);
		if(an < 1e-6) {continue;}
		for(Sample b : in) {
		    if(b == top || b == a || b.value() >= top.value() || b.point().dist(top.point()) > reach) {continue;}
		    double bx = b.point().x - top.point().x, by = b.point().y - top.point().y;
		    double bn = Math.hypot(bx, by);
		    if(bn > 1e-6 && (ax * bx + ay * by) / (an * bn) < -.5) {return top;}
		}
	    }
	}
	return null;
    }

    private static Coord2d unexplored(List<? extends Sample> in, Coord2d center, double step) {
	Coord2d[] dirs = {Coord2d.of(1, 0), Coord2d.of(0, 1), Coord2d.of(-1, 0), Coord2d.of(0, -1),
		Coord2d.of(.707, .707), Coord2d.of(-.707, .707), Coord2d.of(-.707, -.707), Coord2d.of(.707, -.707)};
	Coord2d pick = center.add(dirs[0].mul(step));
	double score = -1;
	for(Coord2d d : dirs) {
	    Coord2d p = center.add(d.mul(step));
	    double nearest = Double.MAX_VALUE;
	    for(Sample sample : in) {nearest = Math.min(nearest, sample.point().dist(p));}
	    if(nearest > score) {score = nearest; pick = p;}
	}
	return pick;
    }

    private static boolean tooClose(List<? extends Sample> in, Coord2d point, double radius) {
	for(Sample sample : in) {if(sample.point().dist(point) < radius) {return true;}}
	return false;
    }
}
