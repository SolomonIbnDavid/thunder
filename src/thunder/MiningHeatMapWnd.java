package thunder;

import haven.Button;
import haven.CheckBox;
import haven.Coord;
import haven.Dropbox;
import haven.GOut;
import haven.Label;
import haven.UI;
import haven.WindowX;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/** Controls the advisory mining-quality surface search. */
public final class MiningHeatMapWnd extends WindowX {
    private final CheckBox overlayBox;
    private final CheckBox followBox;
    private final MaterialDrop materials;
    private final Label summary;
    private final Label guidance;
    private long lastHeatSeq = -1;
    private long lastQualitySeq = -1;

    public MiningHeatMapWnd() {
	super(Coord.z, "Mining Heat Map");
	justclose = true;
	int y = 0;
	add(new Label("Material"), 0, y + UI.scale(3));
	materials = add(new MaterialDrop(UI.scale(220), 12), UI.scale(58), y);
	y += UI.scale(22);
	overlayBox = add(new CheckBox("Show heat map") {
	    @Override public void changed(boolean val) {heat().setVisible(val);}
	}, 0, y);
	followBox = add(new CheckBox("Follow latest") {
	    @Override public void changed(boolean val) {heat().setFollowLatest(val);}
	}, UI.scale(145), y);
	y += UI.scale(24);
	summary = add(new Label("Samples: —"), 0, y);
	y += UI.scale(18);
	guidance = add(new Label("Mine a tile to begin."), 0, y);
	y += UI.scale(24);
	add(new Button(UI.scale(100), "Tile qualities", () -> TileQualityWnd.toggle(ui)), 0, y);
	pack();
	refresh();
    }

    public static void toggle(UI ui) {
	if(ui == null || ui.gui == null) {return;}
	if(ui.gui.miningHeatMapWnd == null) {
	    ui.gui.miningHeatMapWnd = ui.gui.add(new MiningHeatMapWnd(), UI.scale(140), UI.scale(140));
	} else {
	    ui.gui.miningHeatMapWnd.destroy();
	}
    }

    @Override public void destroy() {
	super.destroy();
	if(ui != null && ui.gui != null) {ui.gui.miningHeatMapWnd = null;}
    }

    @Override public void tick(double dt) {
	super.tick(dt);
	MiningHeatMap heat = heat();
	if(heat != null && (heat.seq != lastHeatSeq || TileQuality.seq != lastQualitySeq)) {refresh();}
    }

    private MiningHeatMap heat() {
	return ui == null || ui.gui == null ? null : ui.gui.miningHeatMap;
    }

    private void refresh() {
	MiningHeatMap heat = heat();
	if(heat == null) {return;}
	materials.items = heat.materialKeys(ui.gui.tileQuality);
	materials.sel = heat.target();
	overlayBox.a = heat.visible();
	followBox.a = heat.followLatest();
	MiningHeatMap.Guidance result = heat.guidance(ui.gui);
	String target = heat.target();
	if(target == null) {
	    summary.settext("Samples: —");
	    guidance.settext("Mine a tile to choose a material.");
	} else if(result.samples.isEmpty()) {
	    summary.settext("Samples: 0  ·  " + TileQuality.displayName(target));
	    guidance.settext("No local quality observations yet.");
	} else {
	    summary.settext(String.format("Samples: %d  ·  best q%s  ·  tested without drop %d",
		result.samples.size(), MiningHeatMap.quality(result.fit.bestValue), result.emptyAttempts.size()));
	    if(result.fit.peak) {
		guidance.setcolor(new Color(150, 255, 160));
		guidance.settext("HIGHEST POINT FOUND · q" + MiningHeatMap.quality(result.fit.bestValue));
	    } else if(result.next == null) {
		guidance.setcolor(Color.WHITE);
		guidance.settext("No loaded mineable frontier tile available.");
	    } else {
		guidance.setcolor(Color.WHITE);
		guidance.settext(String.format("Next tile marked · confidence %.0f%%", result.fit.confidence * 100));
	    }
	}
	lastHeatSeq = heat.seq;
	lastQualitySeq = TileQuality.seq;
	pack();
    }

    private final class MaterialDrop extends Dropbox<String> {
	private List<String> items = new ArrayList<>();

	MaterialDrop(int width, int rows) {super(width, rows, UI.scale(16));}
	@Override protected String listitem(int index) {return items.get(index);}
	@Override protected int listitems() {return items.size();}
	@Override protected void drawitem(GOut g, String item, int index) {
	    g.text(item == null ? "—" : TileQuality.displayName(item), Coord.of(UI.scale(3), 0));
	}
	@Override public void change(String item) {
	    super.change(item);
	    if(item != null) {heat().select(item);}
	}
    }
}
