package thunder.rustroot;

import haven.CFG;
import haven.Coord;
import haven.Coord2d;
import haven.Coord3f;
import haven.GOut;
import haven.MapView;
import haven.dev.DebugDraw;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/** Temporary, world-anchored prospect results; never writes non-ore map markers. */
public final class RustrootTileOverlay implements DebugDraw {
    static {
        DebugDraw.Registry.register(new RustrootTileOverlay());
    }

    static void init() {
        // Forces static registration when the prospector window is opened.
    }

    private RustrootTileOverlay() {}

    @Override
    public CFG<Boolean> toggle() {
        return CFG.RUSTROOT_TILE_OVERLAY;
    }

    @Override
    public void paint(GOut g, MapView mv) {
        RustrootProspectorWnd wnd = RustrootProspectorWnd.current();
        if(wnd == null) {return;}
        List<RustrootProspectorWnd.TileResult> results;
        synchronized(wnd.tileResults) {
            if(wnd.tileResults.isEmpty()) {return;}
            results = new ArrayList<>(wnd.tileResults);
        }
        for(RustrootProspectorWnd.TileResult result : results) {
            Coord c = screen(mv, result.at);
            if(c == null) {continue;}
            Color color = result.ore ? new Color(255, 190, 35, 235) : new Color(150, 210, 255, 220);
            g.chcolor(color);
            g.line(c.add(0, -4), c.add(0, -22), 2);
            g.frect(c.add(1, -22), Coord.of(11, 7));
            g.chcolor(Color.WHITE);
            g.atext(result.name, c.add(15, -21), 0, 0.5);
        }
        g.chcolor();
    }

    private static Coord screen(MapView mv, Coord2d world) {
        Coord3f projected;
        try {
            projected = mv.screenxf(mv.glob.map.getzp(world));
        } catch(RuntimeException e) {
            projected = mv.screenxf(world);
        }
        if(projected == null) {return null;}
        Coord c = Coord.of(Math.round(projected.x), Math.round(projected.y));
        return c.x >= -80 && c.y >= -80 && c.x <= mv.sz.x + 80 && c.y <= mv.sz.y + 80 ? c : null;
    }
}
