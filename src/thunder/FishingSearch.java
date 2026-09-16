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
	if(in == null || in.isEmpty() || at == null) {return Double.NaN;}
	double sum=0, weights=0; int nearby=0;
	for(FishingSamples.Sample s:in) {
	    double d=s.cast.dist(at);
	    if(d<0.1) {return s.bite;}
	    if(d>radius) {continue;}
	    double w=1.0/(d*d); sum+=w*s.bite; weights+=w; nearby++;
	}
	return nearby<2 || weights==0 ? Double.NaN : sum/weights;
    }

    public static Guidance guide(List<FishingSamples.Sample> in, Coord2d from, double step) {
	if(in == null || in.isEmpty()) {return new Guidance(null, null, null, -1, 0, 0, false);}
	FishingSamples.Sample best = in.get(0);
	for(FishingSamples.Sample s : in) {if(s.bite > best.bite) {best = s;}}
	FishingSamples.Sample summit = bracketedPeak(in, best.bite, step);
	if(summit != null) {
	    return new Guidance(null, null, summit.cast, summit.bite, in.size(), 1.0, true);
	}
	if(from == null) {from = best.cast;}
	if(in.size() == 1) {
	    return new Guidance(null, unexplored(in, best.cast, step), best.cast, best.bite, 1, 0, false);
	}
	if(in.size() == 2) {
	    FishingSamples.Sample a=in.get(0), b=in.get(1);
	    if(a.bite==b.bite || a.cast.dist(b.cast)<1e-6) {
		return new Guidance(null, unexplored(in,best.cast,step),best.cast,best.bite,2,0,false);
	    }
	    FishingSamples.Sample low=a.bite<b.bite?a:b, high=a.bite>b.bite?a:b;
	    Coord2d delta=high.cast.sub(low.cast);
	    double n=Math.hypot(delta.x,delta.y);
	    Coord2d dir=Coord2d.of(delta.x/n,delta.y/n);
	    return new Guidance(dir,high.cast.add(dir.mul(step)),high.cast,high.bite,2,.25,false);
	}

	/* Centering keeps world-sized coordinates numerically well behaved. */
	double sw=0, sx=0, sy=0, sz=0, sxx=0, syy=0, sxy=0, sxz=0, syz=0;
	for(FishingSamples.Sample s : in) {
	    double dx=s.cast.x-from.x, dy=s.cast.y-from.y;
	    double dist=Math.hypot(dx,dy);
	    double w=1.0/(1.0+dist/110.0);
	    sw+=w; sx+=w*dx; sy+=w*dy; sz+=w*s.bite;
	    sxx+=w*dx*dx; syy+=w*dy*dy; sxy+=w*dx*dy; sxz+=w*dx*s.bite; syz+=w*dy*s.bite;
	}
	double axx=sxx-sx*sx/sw, ayy=syy-sy*sy/sw, axy=sxy-sx*sy/sw;
	double bx=sxz-sx*sz/sw, by=syz-sy*sz/sw;
	double det=axx*ayy-axy*axy;
	Coord2d dir=null;
	if(Math.abs(det)>1e-7) {
	    double gx=(bx*ayy-by*axy)/det, gy=(by*axx-bx*axy)/det;
	    double n=Math.hypot(gx,gy);
	    if(n>1e-6) {dir=Coord2d.of(gx/n,gy/n);}
	} else {
	    /* Samples on a narrow river are almost collinear, so the full 2-D
	     * plane has no unique solution. The position/bite covariance still
	     * gives the valid increasing direction along that river. */
	    double n=Math.hypot(bx,by);
	    if(n>1e-6) {dir=Coord2d.of(bx/n,by/n);}
	}
	double spread=Math.sqrt(Math.max(0,(axx+ayy)/sw));
	double conf=Math.min(1.0, (in.size()-2)/6.0)*Math.min(1.0, spread/Math.max(1,step));
	/* Extrapolate beyond the best observed point. Starting from the most
	 * recent (possibly worst) probe can land back on an already-tested tile
	 * and trigger an arbitrary exploration fallback. */
	Coord2d next=dir == null ? unexplored(in,best.cast,step) : best.cast.add(dir.mul(step));
	if(tooClose(in,next,step*.45)) {next=unexplored(in,best.cast,step);}
	return new Guidance(dir,next,best.cast,best.bite,in.size(),conf,false);
    }

    /** A sampled maximum is confirmed once lower readings bracket it. This
     * handles long, narrow rivers without requiring impossible side probes. */
    private static FishingSamples.Sample bracketedPeak(List<FishingSamples.Sample> in, int bestBite, double step) {
	if(in.size()<3) {return null;}
	double reach=Math.max(step,1)*8.0;
	for(FishingSamples.Sample top:in) {
	    if(top.bite!=bestBite) {continue;}
	    for(FishingSamples.Sample a:in) {
		if(a==top || a.bite>=top.bite || a.cast.dist(top.cast)>reach) {continue;}
		double ax=a.cast.x-top.cast.x, ay=a.cast.y-top.cast.y, an=Math.hypot(ax,ay);
		if(an<1e-6) {continue;}
		for(FishingSamples.Sample b:in) {
		    if(b==top || b==a || b.bite>=top.bite || b.cast.dist(top.cast)>reach) {continue;}
		    double bx=b.cast.x-top.cast.x, by=b.cast.y-top.cast.y, bn=Math.hypot(bx,by);
		    if(bn>1e-6 && (ax*bx+ay*by)/(an*bn)<-.5) {return top;}
		}
	    }
	}
	return null;
    }

    /** Pick the least-tested compass point around the best observation. */
    private static Coord2d unexplored(List<FishingSamples.Sample> in, Coord2d center, double step) {
	Coord2d[] dirs={Coord2d.of(1,0),Coord2d.of(0,1),Coord2d.of(-1,0),Coord2d.of(0,-1),
		Coord2d.of(.707,.707),Coord2d.of(-.707,.707),Coord2d.of(-.707,-.707),Coord2d.of(.707,-.707)};
	Coord2d pick=center.add(dirs[0].mul(step)); double score=-1;
	for(Coord2d d:dirs) {
	    Coord2d p=center.add(d.mul(step)); double nearest=Double.MAX_VALUE;
	    for(FishingSamples.Sample s:in) {nearest=Math.min(nearest,s.cast.dist(p));}
	    if(nearest>score) {score=nearest; pick=p;}
	}
	return pick;
    }

    private static boolean tooClose(List<FishingSamples.Sample> in, Coord2d p, double radius) {
	for(FishingSamples.Sample s:in) {if(s.cast.dist(p)<radius) {return true;}}
	return false;
    }
}
