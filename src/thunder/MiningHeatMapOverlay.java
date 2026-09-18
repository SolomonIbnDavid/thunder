package thunder;

import haven.CFG;
import haven.Coord;
import haven.Coord2d;
import haven.Coord3f;
import haven.GOut;
import haven.MapView;
import haven.dev.DebugDraw;

import java.awt.Color;

/** World overlay for selected mining-quality samples and search guidance. */
public final class MiningHeatMapOverlay implements DebugDraw {
    static {DebugDraw.Registry.register(new MiningHeatMapOverlay());}

    @Override public CFG<Boolean> toggle() {return CFG.MINING_HEATMAP;}

    @Override public void paint(GOut g, MapView mv) {
	if(mv == null || mv.ui == null || mv.ui.gui == null) {return;}
	MiningHeatMap heat = mv.ui.gui.miningHeatMap;
	if(heat == null || !heat.visible()) {return;}
	MiningHeatMap.Guidance guidance = heat.guidance(mv.ui.gui);
	if(guidance.samples.isEmpty() && guidance.emptyAttempts.isEmpty()) {return;}

	if(guidance.samples.size() >= 3) {
	    Coord2d center = guidance.fit.best;
	    if(center != null) {
		for(int y = -8; y <= 8; y++) for(int x = -8; x <= 8; x++) {
		    Coord2d at = center.add(x * 11.0, y * 11.0);
		    double estimate = SpatialPeakSearch.estimate(guidance.samples, at, 88.0);
		    if(Double.isNaN(estimate)) {continue;}
		    Coord c = screen(mv, at);
		    if(c == null) {continue;}
		    g.chcolor(MiningHeatMap.localColor(estimate, guidance.min, guidance.max, 42));
		    g.fellipse(c, Coord.of(7, 4));
		}
	    }
	}

	for(Coord2d attempt : guidance.emptyAttempts) {
	    Coord c = screen(mv, attempt);
	    if(c == null) {continue;}
	    g.chcolor(new Color(190, 200, 210, 180));
	    g.line(c.add(0, -6), c.add(8, 0), 1.5);
	    g.line(c.add(8, 0), c.add(0, 6), 1.5);
	    g.line(c.add(0, 6), c.add(-8, 0), 1.5);
	    g.line(c.add(-8, 0), c.add(0, -6), 1.5);
	}

	for(MiningHeatMap.Sample sample : guidance.samples) {
	    Coord c = screen(mv, sample.rc);
	    if(c == null) {continue;}
	    g.chcolor(MiningHeatMap.localColor(sample.value(), guidance.min, guidance.max, 175));
	    g.fellipse(c, Coord.of(10, 7));
	    g.chcolor(Color.WHITE);
	    g.atext(MiningHeatMap.quality(sample.value()), c.add(0, -1), .5, .5);
	}

	if(guidance.fit.best != null) {
	    Coord best = screen(mv, guidance.fit.best);
	    if(best != null) {
		g.chcolor(new Color(80, 255, 100, guidance.fit.peak ? 235 : 125));
		g.fellipse(best, Coord.of(guidance.fit.peak ? 18 : 14, guidance.fit.peak ? 12 : 9));
		if(guidance.fit.peak) {
		    g.chcolor(Color.WHITE);
		    g.atext("HIGHEST q" + MiningHeatMap.quality(guidance.fit.bestValue), best.add(0, -18), .5, 1);
		}
	    }
	}

	if(guidance.next != null) {
	    Coord next = screen(mv, guidance.next);
	    Coord from = screen(mv, guidance.fit.best);
	    if(next != null) {
		g.chcolor(new Color(120, 230, 255, 245));
		g.fellipse(next, Coord.of(12, 8));
		g.line(next.add(-7, 0), next.add(7, 0), 2);
		g.line(next.add(0, -7), next.add(0, 7), 2);
		if(from != null) {g.line(from, next, 2);}
		g.chcolor(Color.WHITE);
		g.atext(guidance.fit.count < 3 ? "PROBE" : "NEXT", next.add(0, -13), .5, 1);
	    }
	}
	g.chcolor();
    }

    private static Coord screen(MapView mv, Coord2d world) {
	if(world == null) {return null;}
	Coord3f point;
	try {point = mv.screenxf(mv.glob.map.getzp(world));}
	catch(RuntimeException unavailable) {point = mv.screenxf(world);}
	if(point == null) {return null;}
	Coord c = Coord.of(Math.round(point.x), Math.round(point.y));
	return c.x >= -80 && c.y >= -80 && c.x <= mv.sz.x + 80 && c.y <= mv.sz.y + 80 ? c : null;
    }
}
