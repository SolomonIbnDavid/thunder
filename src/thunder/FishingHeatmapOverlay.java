package thunder;

import haven.CFG;
import haven.Coord;
import haven.Coord2d;
import haven.Coord3f;
import haven.GOut;
import haven.MCache;
import haven.MapView;
import haven.Tiler;
import haven.dev.DebugDraw;
import haven.resutil.WaterTile;

import java.awt.Color;
import java.util.List;

/** World-anchored casting samples plus the next point suggested by the surface fit. */
public final class FishingHeatmapOverlay implements DebugDraw {
    static {DebugDraw.Registry.register(new FishingHeatmapOverlay());}

    @Override public CFG<Boolean> toggle() {return CFG.FISHING_HEATMAP;}

    @Override public void paint(GOut g, MapView mv) {
	if(mv == null || mv.ui == null || mv.ui.gui == null) {return;}
	FishingHelper h=mv.ui.gui.fishingHelper;
	if(!h.heatmapVisible()) {return;}
	List<FishingSamples.Sample> samples=h.filteredSamples();
	FishingSearch.Guidance gd=h.guidance();
	FishingSearch.Guidance saved=h.persistentPeak();
	if(samples.isEmpty() && saved==null) {return;}
	/* Paint a conservative smoothed field around the most recent sample. The
	 * raw numbered observations below remain the source of truth. */
	if(samples.size() >= 3) {
	    Coord2d center=samples.get(samples.size()-1).cast;
	    int tx=(int)Math.floor(center.x/11.0), ty=(int)Math.floor(center.y/11.0);
	    for(int y=ty-10;y<=ty+10;y++) for(int x=tx-10;x<=tx+10;x++) {
		Coord2d w=Coord2d.of((x+.5)*11.0,(y+.5)*11.0);
		if(!isWater(mv,w)) {continue;}
		double estimate=FishingSearch.estimate(samples,w,88.0);
		if(Double.isNaN(estimate)) {continue;}
		Coord c=screen(mv,w); if(c==null) {continue;}
		g.chcolor(color((int)Math.round(estimate),45)); g.fellipse(c,Coord.of(7,4));
	    }
	}
	for(FishingSamples.Sample s:samples) {
	    Coord c=screen(mv,s.cast); if(c==null) {continue;}
	    g.chcolor(color(s.bite,150));
	    g.fellipse(c,Coord.of(9,6));
	    g.chcolor(Color.WHITE);
	    g.atext(Integer.toString(s.bite),c.add(0,-1),.5,.5);
	}
	if(gd.best!=null) {
	    Coord b=screen(mv,gd.best);
	    if(b!=null) {
		g.chcolor(new Color(80,255,100,gd.peak?220:100)); g.fellipse(b,Coord.of(gd.peak?17:13,gd.peak?11:9));
		if(gd.peak) {g.chcolor(Color.WHITE); g.atext("HIGHEST " + gd.bestBite + "%",b.add(0,-17),.5,1);}
	    }
	}
	if(!gd.peak && saved!=null && saved.best!=null) {
	    Coord b=screen(mv,saved.best);
	    if(b!=null) {
		g.chcolor(new Color(80,255,100,220)); g.fellipse(b,Coord.of(17,11));
		g.chcolor(Color.WHITE); g.atext("HIGHEST " + saved.bestBite + "%",b.add(0,-17),.5,1);
	    }
	}
	if(gd.next!=null) {
	    Coord2d waterNext=nearestWater(mv,gd.next,10,samples);
	    Coord n=screen(mv,waterNext), from=screen(mv,h.playerPosition());
	    if(n!=null) {
		g.chcolor(new Color(120,230,255,245));
		g.fellipse(n,Coord.of(12,8));
		g.line(n.add(-7,0),n.add(7,0),2); g.line(n.add(0,-7),n.add(0,7),2);
		if(from!=null) {g.line(from,n,2);}
		g.chcolor(Color.WHITE); g.atext(gd.count<3?"PROBE":"NEXT",n.add(0,-13),.5,1);
	    }
	}
	g.chcolor();
    }

    /** Snap unconstrained surface guidance onto loaded, fishable terrain. */
    private static Coord2d nearestWater(MapView mv, Coord2d target, int radius,
					List<FishingSamples.Sample> samples) {
	if(target==null) {return null;}
	Coord tc=target.floor(MCache.tilesz);
	Coord best=null; double bd=Double.MAX_VALUE;
	for(int y=-radius;y<=radius;y++) for(int x=-radius;x<=radius;x++) {
	    Coord c=tc.add(x,y);
	    Coord2d w=Coord2d.of((c.x+.5)*MCache.tilesz.x,(c.y+.5)*MCache.tilesz.y);
	    if(!isWater(mv,w)) {continue;}
	    if(alreadyExplored(samples,w,MCache.tilesz.x*2.5)) {continue;}
	    double d=w.dist(target);
	    if(d<bd) {bd=d; best=c;}
	}
	return best==null?null:Coord2d.of((best.x+.5)*MCache.tilesz.x,(best.y+.5)*MCache.tilesz.y);
    }

    private static boolean alreadyExplored(List<FishingSamples.Sample> samples, Coord2d at, double radius) {
	if(samples==null) {return false;}
	for(FishingSamples.Sample sample:samples) {
	    if(sample!=null && sample.cast!=null && sample.cast.dist(at)<radius) {return true;}
	}
	return false;
    }

    private static boolean isWater(MapView mv, Coord2d w) {
	try {
	    int id=mv.glob.map.gettile(w.floor(MCache.tilesz));
	    Tiler tile=mv.glob.map.tiler(id);
	    return tile instanceof WaterTile;
	} catch(RuntimeException e) {
	    return false;
	}
    }

    static Color color(int pct,int alpha) {
	double t=Math.max(0,Math.min(100,pct))/100.0;
	if(t<.5) {double q=t*2; return new Color((int)(40+215*q),(int)(100+120*q),(int)(255-205*q),alpha);}
	double q=(t-.5)*2; return new Color(255,(int)(220+35*q),(int)(50-20*q),alpha);
    }

    private static Coord screen(MapView mv,Coord2d w) {
	if(w==null) {return null;} Coord3f p;
	try {p=mv.screenxf(mv.glob.map.getzp(w));} catch(RuntimeException e) {p=mv.screenxf(w);}
	if(p==null) {return null;} Coord c=Coord.of(Math.round(p.x),Math.round(p.y));
	return c.x>=-80&&c.y>=-80&&c.x<=mv.sz.x+80&&c.y<=mv.sz.y+80?c:null;
    }
}
