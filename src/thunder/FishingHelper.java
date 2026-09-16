package thunder;

import haven.Astronomy;
import haven.Coord;
import haven.Coord2d;
import haven.GameUI;
import haven.GItem;
import haven.Gob;
import haven.Label;
import haven.Inventory;
import haven.WItem;
import haven.Widget;
import haven.Window;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import static haven.MCache.tilesz;

/**
 * Session fishing helper: parse/tint the bait window, kit, lock, peaks.
 * Never clicks a row or recasts.
 */
public class FishingHelper {
    public static final int HISTORY = 20;
    private static final java.awt.Color LAND_WARN = new java.awt.Color(255, 140, 60);
    private static final java.awt.Color LOCK_HIT = new java.awt.Color(255, 215, 80);

    public static final class Peak {
	public final String species;
	public final int bitePct;
	public final int landPct;
	public final Coord2d rc;
	public final double mp;
	public final int hh, mm;
	public final long at;

	Peak(String species, int bitePct, int landPct, Coord2d rc, double mp, int hh, int mm) {
	    this.species = species;
	    this.bitePct = bitePct;
	    this.landPct = landPct;
	    this.rc = rc;
	    this.mp = mp;
	    this.hh = hh;
	    this.mm = mm;
	    this.at = System.currentTimeMillis();
	}
    }

    private final Object lock = new Object();
    private FishingAdvice.Mode mode = FishingAdvice.Mode.FOOD;
    private String speciesLock = "";
    private FishingBiteList.Result lastList = FishingBiteList.Result.fail();
    private FishingKit.Snapshot lastKit = FishingKit.of(null, 0, null, 0, null, 0, null, 0);
    private boolean depleted;
    private final Deque<Peak> peaks = new ArrayDeque<>();
    private final Map<String, Integer> bestBite = new HashMap<>();
    private final Map<Label, java.awt.Color> origCol = new WeakHashMap<>();
    private Window baitWnd;
    private Window sampledWnd;
    private Coord lastTile;
    private String lastPickName;
    private int lastPickBite = -1;
    private boolean kitWasReady;
    private Window kitWarnWnd;
    private final FishingSamples sampleStore = new FishingSamples();
    private Coord2d lastMapClick;
    private Coord2d activeCast;
    private Coord2d playerPosition;
    private long lastMapClickAt;
    private boolean castArmed;
    private String heatTarget = "";
    private double currentMoon;
    private boolean heatmapVisible = true;
    private boolean sameTackleOnly = true;
    private boolean includeStale = false;
    private boolean autoTackle = false;
    private boolean autoAim = false;
    private FishingKit.Snapshot rememberedKit;
    private String pendingTackle;
    private boolean pendingTaken;
    private long nextTackleAction;
    private Window autoAimedWnd;
    private FishingSearch.Guidance persistentPeak;
    public volatile long seq = 0;

    /** MapView hook. The fishing window that follows binds to this water point. */
    public void noteMapClick(Coord2d mc) {
	if(mc == null) {return;}
	synchronized(lock) {
	    /* Opening the casting list is normally a direct water click, not an
	     * action-menu Fish command. Keep the latest map target; arrival of the
	     * bait window is what confirms that the click was a cast probe. */
	    lastMapClick = mc; lastMapClickAt = System.currentTimeMillis();
	    if(castArmed) {castArmed = false;}
	}
    }

    public void armCast() {synchronized(lock) {castArmed = true;}}

    public boolean heatmapVisible() {synchronized(lock) {return heatmapVisible;}}
    public void setHeatmapVisible(boolean v) {synchronized(lock) {heatmapVisible = v; seq++;}}
    public boolean sameTackleOnly() {synchronized(lock) {return sameTackleOnly;}}
    public void setSameTackleOnly(boolean v) {synchronized(lock) {sameTackleOnly = v; seq++;}}
    public boolean includeStale() {synchronized(lock) {return includeStale;}}
    public void setIncludeStale(boolean v) {synchronized(lock) {includeStale = v; seq++;}}
    public boolean autoTackle() {synchronized(lock) {return autoTackle;}}
    public void setAutoTackle(boolean v) {synchronized(lock) {autoTackle=v; pendingTackle=null; pendingTaken=false; seq++;}}
    public boolean autoAim() {synchronized(lock) {return autoAim;}}
    public void setAutoAim(boolean v) {synchronized(lock) {autoAim=v; seq++;}}
    public Coord2d playerPosition() {synchronized(lock) {return playerPosition;}}

    public List<FishingSamples.Sample> filteredSamples() {
	synchronized(lock) {
	    String species = heatSpecies();
	    if(species.isEmpty()) {return java.util.Collections.emptyList();}
	    List<FishingSamples.Sample> matched = sampleStore.matching(species,
		sameTackleOnly ? tackleKey(lastKit) : "", currentMoon, includeStale);
	    if(playerPosition == null) {return matched;}
	    /* Fish nodes are local. Never let a previous lake or a distant stretch
	     * of river influence the surface fitted around the player. */
	    List<FishingSamples.Sample> local = new ArrayList<>();
	    double radius = tilesz.x * 50.0;
	    for(FishingSamples.Sample sample : matched) {
		if(sample.cast != null && sample.cast.dist(playerPosition) <= radius) {local.add(sample);}
	    }
	    return java.util.Collections.unmodifiableList(local);
	}
    }

    public FishingSearch.Guidance guidance() {
	List<FishingSamples.Sample> samples = filteredSamples();
	Coord2d from = samples.isEmpty() ? null : samples.get(samples.size() - 1).cast;
	FishingSearch.Guidance guidance=FishingSearch.guide(samples, from, tilesz.x * 4.0);
	synchronized(lock) {
	    if(guidance.peak) {persistentPeak=guidance;}
	    else if(persistentPeak!=null && guidance.bestBite>persistentPeak.bestBite) {persistentPeak=null;}
	}
	return guidance;
    }

    public FishingSearch.Guidance persistentPeak() {synchronized(lock) {return persistentPeak;}}
    public void clearPersistentPeak() {synchronized(lock) {persistentPeak=null; seq++;}}

    public void clearSamples() {sampleStore.clear(); synchronized(lock) {persistentPeak=null; seq++;}}

    public boolean selectRecommended() {
	FishingBiteList.Row row;
	synchronized(lock) {row = FishingAdvice.pick(mode, speciesLock, lastList);}
	if(row == null || row.button == null) {return false;}
	row.button.click();
	return true;
    }

    /** Drives one server-safe tackle action at a time while the helper window is open. */
    public void automationTick(GameUI gui) {
	if(gui==null) {return;}
	FishingKit.Snapshot now=FishingKit.from(gui);
	String wanted;
	synchronized(lock) {
	    if(now.ready() && now.castingRod()) {
		rememberedKit=now; pendingTackle=null; pendingTaken=false;
		return;
	    }
	    if(!autoTackle || !now.loaded || !now.castingRod() || rememberedKit==null || !rememberedKit.ready()) {return;}
	    if(System.currentTimeMillis()<nextTackleAction) {return;}
	    wanted=missingRemembered(now,rememberedKit);
	    if(wanted==null) {pendingTackle=null; pendingTaken=false; return;}
	    if(pendingTackle==null || !FishingTackle.norm(pendingTackle).equals(FishingTackle.norm(wanted))) {
		pendingTackle=wanted; pendingTaken=false;
	    }
	}
	if(!pendingTaken) {
	    if(gui.vhand!=null) {return;} // Never interfere with an item the player is holding.
	    GItem found=findInventory(gui,wanted);
	    if(found==null) {return;}
	    found.wdgmsg("take",Coord.z);
	    synchronized(lock) {pendingTaken=true; nextTackleAction=System.currentTimeMillis()+350;}
	    return;
	}
	if(gui.vhand==null || gui.vhand.item==null) {return;}
	String held=FishingKit.itemName(gui.vhand.item);
	if(!FishingTackle.norm(wanted).equals(FishingTackle.norm(held))) {return;}
	GItem pole=fishingPole(gui);
	if(pole==null) {return;}
	pole.wdgmsg("itemact",0);
	synchronized(lock) {pendingTackle=null; pendingTaken=false; nextTackleAction=System.currentTimeMillis()+700;}
    }

    private static String missingRemembered(FishingKit.Snapshot now,FishingKit.Snapshot remembered) {
	if(now.line==null) {return remembered.line;}
	if(now.hook==null) {return remembered.hook;}
	if(now.lure==null && now.bait==null) {return remembered.lure!=null?remembered.lure:remembered.bait;}
	return null;
    }

    private static GItem findInventory(GameUI gui,String name) {
	if(name==null) {return null;}
	GItem found=findIn(gui.maininv,name);
	if(found!=null) {return found;}
	/* Auxiliary inventories only exist client-side while their contents
	 * window has been opened. Restrict this search to Creels so automatic
	 * tackle replacement cannot unexpectedly pull from unrelated storage. */
	for(GItem.ContentsWindow wnd:gui.children(GItem.ContentsWindow.class)) {
	    String container=FishingKit.itemName(wnd.cont);
	    if(container==null || !FishingTackle.norm(container).contains("creel")) {continue;}
	    if(wnd.inv instanceof Inventory) {
		found=findIn((Inventory)wnd.inv,name);
		if(found!=null) {return found;}
	    }
	    for(Inventory inv:wnd.inv.children(Inventory.class)) {
		found=findIn(inv,name);
		if(found!=null) {return found;}
	    }
	}
	return null;
    }

    private static GItem findIn(Inventory inv,String name) {
	if(inv==null) {return null;}
	final GItem[] found={null};
	inv.forEachItem((item,witem)->{
	    if(found[0]!=null) {return;}
	    String n=FishingKit.itemName(item);
	    if(FishingTackle.norm(name).equals(FishingTackle.norm(n))) {found[0]=item;}
	});
	return found[0];
    }

    private static GItem fishingPole(GameUI gui) {
	if(gui.equipory==null) {return null;}
	for(int idx:new int[]{haven.Equipory.SLOTS.HAND_LEFT.idx,haven.Equipory.SLOTS.HAND_RIGHT.idx}) {
	    if(idx<0 || idx>=gui.equipory.slots.length) {continue;}
	    WItem w=gui.equipory.slots[idx];
	    if(w!=null && w.item!=null && FishingKit.classify(FishingKit.itemName(w.item))==FishingKit.Kind.POLE) {return w.item;}
	}
	return null;
    }

    public static void attach(Window window) {
	if(window == null || window.ui == null || window.ui.gui == null) {return;}
	if(!FishingBiteList.CAPTION.equals(window.caption())) {return;}
	for(Widget w : window.children()) {
	    if(w instanceof Watcher) {return;}
	}
	window.add(new Watcher(), Coord.z);
    }

    public FishingAdvice.Mode mode() {
	synchronized(lock) {return mode;}
    }

    public String lock() {
	synchronized(lock) {return speciesLock;}
    }

    public FishingBiteList.Result list() {
	synchronized(lock) {return lastList;}
    }

    public FishingKit.Snapshot kit() {
	synchronized(lock) {return lastKit;}
    }

    public List<Peak> peaks() {
	synchronized(lock) {return new ArrayList<>(peaks);}
    }

    public void setMode(FishingAdvice.Mode mode) {
	if(mode == null) {return;}
	synchronized(lock) {
	    if(this.mode == mode) {return;}
	    this.mode = mode;
	    persistentPeak = null;
	    seq++;
	}
    }

    public void setLock(String species) {
	String v = species == null ? "" : species.trim();
	synchronized(lock) {
	    if(v.equals(this.speciesLock)) {return;}
	    this.speciesLock = v;
	    persistentPeak = null;
	    seq++;
	}
    }

    public void clearPeaks() {
	synchronized(lock) {
	    peaks.clear();
	    bestBite.clear();
	    seq++;
	}
    }

    public String advice() {
	synchronized(lock) {
	    FishingTackle.Combo combo = null;
	    if(mode == FishingAdvice.Mode.TROPHY && speciesLock != null && !speciesLock.isEmpty()) {
		combo = FishingTackle.suggest(speciesLock);
	    }
	    return FishingAdvice.advise(new FishingAdvice.Situation(mode, speciesLock, lastList, lastKit, depleted, combo));
	}
    }

    public Peak pinCurrent(GameUI gui) {
	if(gui == null) {return null;}
	FishingBiteList.Row pick;
	synchronized(lock) {
	    pick = FishingAdvice.pick(mode, speciesLock, lastList);
	}
	if(pick == null) {return null;}
	Coord2d rc = playerRc(gui);
	Astronomy ast = ast(gui);
	Peak p = new Peak(pick.name, pick.bitePct, pick.landPct, rc,
			  ast == null ? 0 : ast.mp,
			  ast == null ? 0 : ast.hh,
			  ast == null ? 0 : ast.mm);
	synchronized(lock) {
	    notePeak(p);
	    seq++;
	}
	return p;
    }

    void scan(Window wnd, GameUI gui) {
	if(wnd == null || gui == null) {return;}
	FishingBiteList.Result parsed = FishingBiteList.parse(wnd);
	FishingKit.Snapshot kit = FishingKit.from(gui);
	Coord2d rc = playerRc(gui);
	Coord tile = rc == null ? null : rc.floor(tilesz);
	Astronomy ast = ast(gui);

	FishingBiteList.Row pick;
	boolean nowDepleted;
	boolean warnEmpty = false;
	String missing = null;
	synchronized(lock) {
	    boolean freshWindow = baitWnd != wnd;
	    playerPosition = rc;
	    if(ast != null) {currentMoon = ast.mp;}
	    if(freshWindow) {
		activeCast = (lastMapClick != null && System.currentTimeMillis() - lastMapClickAt < 15000)
		    ? lastMapClick : rc;
	    }
	    lastList = parsed;
	    lastKit = kit;
	    baitWnd = wnd;
	    pick = FishingAdvice.pick(mode, speciesLock, parsed);
	    if(speciesLock != null && !speciesLock.trim().isEmpty()) {heatTarget = speciesLock.trim();}
	    else if(pick != null) {heatTarget = pick.name;}
	    nowDepleted = false;
	    if(pick != null && tile != null && lastTile != null && lastPickName != null
	       && tile.equals(lastTile) && FishingTackle.norm(pick.name).equals(FishingTackle.norm(lastPickName))
	       && lastPickBite >= 0 && pick.bitePct < lastPickBite) {
		nowDepleted = true;
	    }
	    depleted = nowDepleted;
	    if(pick != null) {
		lastPickName = pick.name;
		lastPickBite = pick.bitePct;
		lastTile = tile;
		Integer best = bestBite.get(FishingTackle.norm(pick.name));
		if(best == null || pick.bitePct > best) {
		    notePeak(new Peak(pick.name, pick.bitePct, pick.landPct, rc,
				      ast == null ? 0 : ast.mp,
				      ast == null ? 0 : ast.hh,
				      ast == null ? 0 : ast.mm));
		}
	    }
	    boolean ready = kit.ready();
	    if(ready && kit.castingRod()) {rememberedKit=kit;}
	    if(kit.emptyKit() && (kitWarnWnd != wnd || kitWasReady)) {
		kitWarnWnd = wnd;
		warnEmpty = true;
		missing = kit.missing();
	    }
	    kitWasReady = ready;
	    /* Window construction and inventory equipment loading race each other.
	     * Record once only after both the fish list and the complete tackle kit
	     * are available, or Same-tackle filtering can immediately hide the
	     * sample we just captured. */
	    if(sampledWnd != wnd && parsed.parsed && kit.loaded && kit.ready() && activeCast != null) {
		String tk = tackleKey(kit);
		long now = System.currentTimeMillis();
		for(FishingBiteList.Row row : parsed.rows) {
		    sampleStore.add(new FishingSamples.Sample(row.name, tk, row.bitePct, row.landPct,
			activeCast, rc, ast == null ? 0 : ast.mp, ast == null ? 0 : ast.hh,
			ast == null ? 0 : ast.mm, now));
		}
		sampledWnd = wnd;
	    }
	    seq++;
	}
	if(warnEmpty) {
	    gui.msg("Fishing helper: empty kit — " + missing + " missing", GameUI.MsgType.BAD);
	}

	if(parsed.parsed) {
	    tint(parsed, mode(), lock());
	    FishingBiteList.Row auto=null;
	    synchronized(lock) {
		if(autoAim && autoAimedWnd!=wnd && mode==FishingAdvice.Mode.TROPHY && speciesLock!=null && !speciesLock.trim().isEmpty()) {
		    auto=parsed.find(speciesLock);
		    if(auto!=null && auto.button!=null) {autoAimedWnd=wnd;}
		}
	    }
	    if(auto!=null && auto.button!=null) {auto.button.click();}
	} else {
	    restoreTints();
	}
    }

    void release(Window wnd) {
	synchronized(lock) {
	if(baitWnd == wnd) {baitWnd = null;}
	if(sampledWnd == wnd) {sampledWnd = null;}
	if(autoAimedWnd == wnd) {autoAimedWnd = null;}
	if(baitWnd == null) {activeCast = null;}
	    if(kitWarnWnd == wnd) {kitWarnWnd = null;}
	    lastList = FishingBiteList.Result.fail();
	    depleted = false;
	    seq++;
	}
	restoreTints();
    }

    private void notePeak(Peak p) {
	if(p == null || p.species == null) {return;}
	bestBite.put(FishingTackle.norm(p.species), p.bitePct);
	peaks.addFirst(p);
	while(peaks.size() > HISTORY) {peaks.removeLast();}
    }

    private void tint(FishingBiteList.Result parsed, FishingAdvice.Mode mode, String lock) {
	String nlock = FishingTackle.norm(lock);
	for(FishingBiteList.Row row : parsed.rows) {
	    remember(row.nameLbl);
	    remember(row.biteLbl);
	    remember(row.landLbl);
	    if(row.landLbl != null) {
		if(row.landPct != 100) {row.landLbl.setcolor(LAND_WARN);}
		else {row.landLbl.setcolor(origCol.getOrDefault(row.landLbl, java.awt.Color.WHITE));}
	    }
	    if(row.nameLbl != null) {
		boolean locked = mode == FishingAdvice.Mode.TROPHY && !nlock.isEmpty()
		    && FishingTackle.norm(row.name).equals(nlock);
		if(locked) {row.nameLbl.setcolor(LOCK_HIT);}
		else {row.nameLbl.setcolor(origCol.getOrDefault(row.nameLbl, java.awt.Color.WHITE));}
	    }
	}
    }

    private void remember(Label l) {
	if(l == null) {return;}
	origCol.computeIfAbsent(l, x -> x.col);
    }

    private void restoreTints() {
	for(Map.Entry<Label, java.awt.Color> e : origCol.entrySet()) {
	    Label l = e.getKey();
	    if(l != null && l.text != null) {l.setcolor(e.getValue());}
	}
	origCol.clear();
    }

    private static Coord2d playerRc(GameUI gui) {
	if(gui == null || gui.map == null) {return null;}
	Gob pl = gui.map.player();
	return pl == null ? null : pl.rc;
    }

    private String heatSpecies() {
	/* A species lock only applies in Trophy mode. Keeping stale text in the
	 * lock box while using Food mode must not hide every heat-map sample. */
	if(mode == FishingAdvice.Mode.TROPHY && speciesLock != null && !speciesLock.trim().isEmpty()) {
	    return speciesLock.trim();
	}
	return heatTarget;
    }

    static String tackleKey(FishingKit.Snapshot k) {
	if(k == null) {return "";}
	String fourth = k.lure != null ? k.lure : k.bait;
	return FishingTackle.norm(k.line) + "|" + FishingTackle.norm(k.hook) + "|" + FishingTackle.norm(fourth);
    }

    private static Astronomy ast(GameUI gui) {
	if(gui == null || gui.ui == null || gui.ui.sess == null) {return null;}
	return gui.ui.sess.glob.ast;
    }

    public static class Watcher extends Widget {
	@Override
	public void tick(double dt) {
	    Window wnd = getparent(Window.class);
	    if(wnd == null || ui == null || ui.gui == null) {return;}
	    ui.gui.fishingHelper.scan(wnd, ui.gui);
	}

	@Override
	public void destroy() {
	    Window wnd = getparent(Window.class);
	    if(ui != null && ui.gui != null) {ui.gui.fishingHelper.release(wnd);}
	    super.destroy();
	}
    }
}
