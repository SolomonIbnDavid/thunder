package thunder;

import haven.Astronomy;
import haven.Button;
import haven.CharWnd;
import haven.Coord;
import haven.Coord2d;
import haven.Dropbox;
import haven.FlowerMenu;
import haven.GOut;
import haven.GameUI;
import haven.Label;
import haven.Listbox;
import haven.MiniMap;
import haven.Text;
import haven.TextEntry;
import haven.UI;
import haven.WindowX;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static haven.MCache.tilesz;

/**
 * Fishing helper window: trophy/food mode, species lock, kit, advice, peak pins.
 * Does not click the bait list or walk.
 */
public class FishingHelperWnd extends WindowX {
    private static final Color ROW_EVEN = new Color(255, 255, 255, 16);
    private static final Color ROW_ODD = new Color(255, 255, 255, 32);
    private static final Color STALE = new Color(255, 170, 70);
    private static final Text.Foundry ELF = CharWnd.attrf;
    private static final int ELH = ELF.height() + UI.scale(2);
    private static final List<String> SPECIES = FishingTackle.species();

    private final haven.CheckBox trophyBox;
    private final TextEntry lockEntry;
    private final Dropbox<String> speciesDrop;
    private final Label kitLbl;
    private final Label listLbl;
    private final Label moonLbl;
    private final Label adviceLbl;
    private final Label searchLbl;
    private final haven.CheckBox heatBox;
    private final haven.CheckBox tackleBox;
    private final haven.CheckBox staleBox;
    private final haven.CheckBox autoTackleBox;
    private final haven.CheckBox autoAimBox;
    private final PeakList peakList;
    private long lastSeq = -1;
    private List<FishingHelper.Peak> peaks = Collections.emptyList();

    public FishingHelperWnd() {
	super(Coord.z, "Fishing Helper");
	justclose = true;

	int y = 0;
	trophyBox = add(new haven.CheckBox("Trophy (lock species)") {
	    @Override
	    public void changed(boolean val) {
		FishingHelper h = helper();
		if(h != null) {h.setMode(val ? FishingAdvice.Mode.TROPHY : FishingAdvice.Mode.FOOD);}
	    }
	}, 0, y);
	y += trophyBox.sz.y + UI.scale(4);

	add(new Label("Lock"), 0, y + UI.scale(3));
	lockEntry = add(new TextEntry(UI.scale(140), "") {
	    @Override
	    protected void changed() {
		FishingHelper h = helper();
		if(h != null) {h.setLock(text());}
	    }
	}, UI.scale(40), y);
	speciesDrop = add(new Dropbox<String>(UI.scale(150), 8, UI.scale(16)) {
	    @Override
	    protected String listitem(int i) {return SPECIES.get(i);}
	    @Override
	    protected int listitems() {return SPECIES.size();}
	    @Override
	    protected void drawitem(GOut g, String item, int i) {g.text(item, Coord.z);}
	    @Override
	    public void change(String item) {
		super.change(item);
		if(item != null) {
		    lockEntry.settext(item);
		    FishingHelper h = helper();
		    if(h != null) {
			h.setMode(FishingAdvice.Mode.TROPHY);
			h.setLock(item);
		    }
		}
	    }
	}, UI.scale(190), y);
	y += UI.scale(22);

	kitLbl = add(new Label("Kit: —"), 0, y);
	y += UI.scale(16);
	listLbl = add(new Label("List: —"), 0, y);
	y += UI.scale(16);
	moonLbl = add(new Label("Moon: —"), 0, y);
	y += UI.scale(16);
	adviceLbl = add(new Label(""), 0, y);
	y += UI.scale(20);
	searchLbl = add(new Label("Search: cast to begin"), 0, y);
	y += UI.scale(18);

	heatBox = add(new haven.CheckBox("Heat map") {
	    @Override public void changed(boolean val) {FishingHelper h=helper(); if(h!=null) h.setHeatmapVisible(val);}
	}, 0, y);
	tackleBox = add(new haven.CheckBox("Same tackle") {
	    @Override public void changed(boolean val) {FishingHelper h=helper(); if(h!=null) h.setSameTackleOnly(val);}
	}, UI.scale(105), y);
	staleBox = add(new haven.CheckBox("Include stale") {
	    @Override public void changed(boolean val) {FishingHelper h=helper(); if(h!=null) h.setIncludeStale(val);}
	}, UI.scale(225), y);
	y += UI.scale(22);
	autoTackleBox = add(new haven.CheckBox("Auto replace tackle") {
	    @Override public void changed(boolean val) {FishingHelper h=helper(); if(h!=null) h.setAutoTackle(val);}
	}, 0, y);
	autoAimBox = add(new haven.CheckBox("Auto aim locked fish") {
	    @Override public void changed(boolean val) {FishingHelper h=helper(); if(h!=null) h.setAutoAim(val);}
	}, UI.scale(180), y);
	y += UI.scale(22);

	add(new Label("Session peaks  (right-click to flag)"), 0, y);
	y += UI.scale(18);
	peakList = add(new PeakList(UI.scale(420), 8), 0, y);
	y += peakList.sz.y + UI.scale(6);

	add(new Button(UI.scale(90), "Pin here") {
	    @Override
	    public void click() {pinCurrent();}
	}, 0, y);
	add(new Button(UI.scale(70), "Clear") {
	    @Override
	    public void click() {
		FishingHelper h = helper();
		if(h != null) {h.clearPeaks();}
	    }
	}, UI.scale(100), y);
	add(new Button(UI.scale(125), "Select suggested") {
	    @Override public void click() {
		FishingHelper h=helper();
		if(h==null || !h.selectRecommended()) {if(ui!=null&&ui.gui!=null) ui.gui.msg("No selectable fishing row.", GameUI.MsgType.INFO);}
	    }
	}, UI.scale(180), y);
	add(new Button(UI.scale(110), "Clear samples") {
	    @Override public void click() {FishingHelper h=helper(); if(h!=null) h.clearSamples();}
	}, UI.scale(315), y);
	pack();
	refresh();
    }

    public static void toggle(UI ui) {
	if(ui == null || ui.gui == null) {return;}
	if(ui.gui.fishingHelperWnd == null) {
	    ui.gui.fishingHelperWnd = ui.gui.add(new FishingHelperWnd(), 120, 120);
	} else {
	    ui.gui.fishingHelperWnd.destroy();
	}
    }

    @Override
    public void destroy() {
	FishingHelper h=helper();
	if(h!=null) {h.clearPersistentPeak();}
	super.destroy();
	if(ui != null && ui.gui != null) {ui.gui.fishingHelperWnd = null;}
    }

    @Override
    public void tick(double dt) {
	super.tick(dt);
	FishingHelper h = helper();
	if(h != null && ui != null && ui.gui != null) {h.automationTick(ui.gui);}
	if(h != null && lastSeq != h.seq) {refresh();}
    }

    private FishingHelper helper() {
	return (ui == null || ui.gui == null) ? null : ui.gui.fishingHelper;
    }

    private void refresh() {
	FishingHelper h = helper();
	if(h == null) {return;}
	lastSeq = h.seq;
	peaks = h.peaks();
	peakList.setItems(peaks);

	boolean trophy = h.mode() == FishingAdvice.Mode.TROPHY;
	if(trophyBox.a != trophy) {trophyBox.a = trophy;}
	if(!lockEntry.hasfocus) {
	    String lock = h.lock();
	    if(lock == null) {lock = "";}
	    if(!lock.equals(lockEntry.text())) {lockEntry.settext(lock);}
	}

	FishingKit.Snapshot kit = h.kit();
	kitLbl.settext(kitLine(kit));
	FishingBiteList.Result list = h.list();
	if(list == null || !list.parsed) {
	    listLbl.settext("List: —  (cast a casting-rod)");
	} else {
	    FishingBiteList.Row pick = FishingAdvice.pick(h.mode(), h.lock(), list);
	    String pickTxt = pick == null ? "none" : String.format("%s %d/%d", pick.name, pick.bitePct, pick.landPct);
	    listLbl.settext(String.format("List: %d fish   pick %s", list.rows.size(), pickTxt));
	}

	Astronomy ast = astronomy();
	if(ast == null) {
	    moonLbl.settext("Moon: —");
	} else {
	    moonLbl.settext("Moon: " + FishingMoon.stamp(ast.mp, ast.hh, ast.mm));
	}

	String advice = h.advice();
	if(advice.startsWith("empty kit") || advice.startsWith("swap tackle")) {
	    adviceLbl.setcolor(STALE);
	} else if(advice.startsWith("ok") || advice.startsWith("food pick")) {
	    adviceLbl.setcolor(new Color(180, 255, 180));
	} else {
	    adviceLbl.setcolor(Color.WHITE);
	}
	adviceLbl.settext(advice == null ? "" : advice);
	heatBox.a = h.heatmapVisible();
	tackleBox.a = h.sameTackleOnly();
	staleBox.a = h.includeStale();
	autoTackleBox.a = h.autoTackle();
	autoAimBox.a = h.autoAim();
	FishingSearch.Guidance gd = h.guidance();
	if(gd.count == 0) {
	    searchLbl.settext("Search: no comparable samples");
	} else if(gd.peak) {
	    searchLbl.settext(String.format("Search: HIGHEST POINT FOUND · %d%% · %d samples", gd.bestBite, gd.count));
	} else if(gd.next == null) {
	    searchLbl.settext(String.format("Search: %d samples · best %d%%", gd.count, gd.bestBite));
	} else {
	    searchLbl.settext(String.format("Search: %d samples · best %d%% · next marked · confidence %.0f%%",
		gd.count, gd.bestBite, gd.confidence * 100));
	}
    }

    private static String kitLine(FishingKit.Snapshot kit) {
	if(kit == null || !kit.hasPole()) {return "Kit: —  (hold a casting-rod)";}
	if(!kit.loaded) {return String.format("Kit: %s  (loading)", kit.pole);}
	String mean = kit.gearMean() > 0 ? String.format("  mean Q%.0f", kit.gearMean()) : "";
	if(kit.emptyKit()) {
	    return String.format("Kit: %s  MISSING %s%s", kit.pole, kit.missing(), mean);
	}
	String lure = kit.lure != null ? kit.lure : kit.bait;
	return String.format("Kit: %s · %s · %s · %s%s", kit.pole, kit.line, kit.hook, lure, mean);
    }

    private Astronomy astronomy() {
	if(ui == null || ui.sess == null) {return null;}
	return ui.sess.glob.ast;
    }

    private void pinCurrent() {
	FishingHelper h = helper();
	if(h == null) {return;}
	FishingHelper.Peak p = h.pinCurrent(ui.gui);
	if(p == null) {
	    if(ui.gui != null) {ui.gui.msg("Nothing to pin — cast first.", GameUI.MsgType.INFO);}
	    return;
	}
	flagOnMap(p);
    }

    private void jumpTo(FishingHelper.Peak p) {
	if(p == null || p.rc == null || ui == null || ui.gui == null || ui.gui.mapfile == null) {return;}
	MiniMap.Location sess = ui.gui.mapfile.view.sessloc;
	if(sess == null) {return;}
	Coord tc = p.rc.floor(tilesz).add(sess.tc);
	ui.gui.mapfile.view.center(new MiniMap.SpecLocator(sess.seg.id, tc));
	if(!ui.gui.mapfile.visible()) {ui.gui.mapfile.show();}
    }

    private void flagOnMap(FishingHelper.Peak p) {
	if(p == null || p.rc == null || ui == null || ui.gui == null || ui.gui.mapfile == null) {return;}
	String name = peakName(p, astronomy());
	ui.gui.mapfile.addMarker(p.rc.floor(tilesz), name);
	ui.gui.msg("Flagged " + name, GameUI.MsgType.INFO);
    }

    static String peakName(FishingHelper.Peak p, Astronomy now) {
	String stale = (now != null && FishingMoon.staleAfterFullMoon(p.mp, now.mp)) ? " STALE" : "";
	return String.format("FISH %s %d%% %s%s", p.species, p.bitePct, FishingMoon.stamp(p.mp, p.hh, p.mm), stale);
    }

    private void flagMenu(FishingHelper.Peak p) {
	if(p == null || ui == null) {return;}
	FlowerMenu menu = new FlowerMenu("Mark on map") {
	    @Override
	    public void choose(Petal opt) {
		if(opt != null && "Mark on map".equals(opt.name)) {flagOnMap(p);}
		if(opt != null) {uimsg("act", Integer.valueOf(opt.num));}
		else {uimsg("cancel");}
	    }
	};
	ui.root.add(menu, ui.mc);
    }

    private class PeakList extends Listbox<FishingHelper.Peak> {
	private List<FishingHelper.Peak> items = Collections.emptyList();

	PeakList(int w, int h) {
	    super(w, h, ELH);
	    bgcolor = new Color(0, 0, 0, 84);
	}

	void setItems(List<FishingHelper.Peak> items) {this.items = items;}

	@Override
	protected FishingHelper.Peak listitem(int idx) {return items.get(idx);}

	@Override
	protected int listitems() {return items.size();}

	@Override
	protected void drawitem(GOut g, FishingHelper.Peak p, int idx) {
	    g.chcolor((idx % 2 == 0) ? ROW_EVEN : ROW_ODD);
	    g.frect(Coord.z, g.sz());
	    g.chcolor();
	    Astronomy ast = astronomy();
	    boolean stale = ast != null && FishingMoon.staleAfterFullMoon(p.mp, ast.mp);
	    if(stale) {g.chcolor(STALE);}
	    String line = String.format("%s   bite %d  land %d   %s%s",
					p.species, p.bitePct, p.landPct,
					FishingMoon.stamp(p.mp, p.hh, p.mm),
					stale ? "  STALE" : "");
	    g.atext(line, new Coord(UI.scale(4), ELH / 2), 0, 0.5);
	    g.chcolor();
	}

	@Override
	protected Object itemtip(FishingHelper.Peak p) {
	    if(p == null) {return null;}
	    return p.species + " bite " + p.bitePct + "%  land " + p.landPct + "%\nRight-click to flag the map.";
	}

	@Override
	protected void itemclick(FishingHelper.Peak p, int button) {
	    if(p == null) {return;}
	    if(button == 1) {
		change(p);
		jumpTo(p);
	    } else if(button == 3) {
		flagMenu(p);
	    }
	}
    }
}
