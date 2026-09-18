package thunder;

import auto.MapHelper;
import haven.Coord;
import haven.Coord2d;
import haven.GameUI;
import haven.Gob;
import haven.Loading;
import haven.MCache;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Session controls and derived search state for the mining heat map. */
public final class MiningHeatMap {
    public static final double LOCAL_RADIUS = MCache.tilesz.x * 50.0;
    private static final double STEP = MCache.tilesz.x;

    public static final class TileRef {
	public final long gridId;
	public final int tileIdx;

	public TileRef(long gridId, int tileIdx) {
	    this.gridId = gridId;
	    this.tileIdx = tileIdx;
	}

	@Override public boolean equals(Object other) {
	    return other instanceof TileRef && ((TileRef)other).gridId == gridId && ((TileRef)other).tileIdx == tileIdx;
	}
	@Override public int hashCode() {return Objects.hash(gridId, tileIdx);}
    }

    public static final class Sample implements SpatialPeakSearch.Sample {
	public final TileRef tile;
	public final String kind;
	public final short quality;
	public final Coord2d rc;

	Sample(TileRef tile, String kind, short quality, Coord2d rc) {
	    this.tile = tile;
	    this.kind = kind;
	    this.quality = quality;
	    this.rc = rc;
	}

	@Override public Coord2d point() {return rc;}
	@Override public double value() {return quality / 10.0;}
    }

    public static final class Guidance {
	public final List<Sample> samples;
	public final List<Coord2d> emptyAttempts;
	public final SpatialPeakSearch.Guidance fit;
	public final Coord2d next;
	public final double min, max;

	Guidance(List<Sample> samples, List<Coord2d> emptyAttempts,
		 SpatialPeakSearch.Guidance fit, Coord2d next, double min, double max) {
	    this.samples = samples;
	    this.emptyAttempts = emptyAttempts;
	    this.fit = fit;
	    this.next = next;
	    this.min = min;
	    this.max = max;
	}

	public static Guidance empty() {
	    return new Guidance(Collections.emptyList(), Collections.emptyList(),
		SpatialPeakSearch.guide(Collections.emptyList(), null, STEP), null, Double.NaN, Double.NaN);
	}
    }

    interface TerrainProbe {
	boolean loaded(Coord tile);
	boolean minedFloor(Coord tile);
    }

    private final Object lock = new Object();
    private final Set<TileRef> attempts = new LinkedHashSet<>();
    private String target;
    private boolean followLatest = true;
    private boolean visible = true;
    private Guidance cached = Guidance.empty();
    private long cachedHeatSeq = -1, cachedTileSeq = -1, cachedAt;
    private Coord cachedPlayerTile;
    public volatile long seq;

    public boolean visible() {synchronized(lock) {return visible;}}
    public void setVisible(boolean visible) {synchronized(lock) {this.visible = visible; seq++;}}
    public boolean followLatest() {synchronized(lock) {return followLatest;}}
    public String target() {synchronized(lock) {return target;}}

    public void setFollowLatest(boolean follow) {
	synchronized(lock) {
	    followLatest = follow;
	    seq++;
	}
    }

    public void select(String key) {
	String normalized = MiningQualityCatalog.normalizeKey(key);
	synchronized(lock) {
	    target = normalized;
	    followLatest = false;
	    seq++;
	}
    }

    public void noteMineout(GameUI gui, Coord2d rc) {
	TileRef ref = resolve(gui, rc);
	if(ref == null) {return;}
	synchronized(lock) {
	    if(attempts.add(ref)) {seq++;}
	}
    }

    public void noteObservation(String kind) {
	String normalized = MiningQualityCatalog.normalizeKey(kind);
	if(!isMiningKind(normalized)) {return;}
	synchronized(lock) {
	    if(followLatest || target == null) {target = normalized;}
	    seq++;
	}
    }

    public List<String> materialKeys(TileQuality tracker) {
	Set<String> keys = new TreeSet<>(Comparator.comparing(TileQuality::displayName, String.CASE_INSENSITIVE_ORDER));
	for(MiningQualityCatalog.Entry entry : MiningQualityCatalog.entries()) {keys.add(entry.key);}
	keys.add(TileQuality.KEY_CRYSTAL);
	if(tracker != null) {
	    for(TileQuality.TileSnapshot tile : tracker.snapshotAll()) {
		for(String key : tile.kinds.keySet()) {if(isMiningKind(key)) {keys.add(MiningQualityCatalog.normalizeKey(key));}}
	    }
	}
	return new ArrayList<>(keys);
    }

    public Guidance guidance(GameUI gui) {
	if(gui == null || gui.tileQuality == null || gui.map == null || gui.ui == null || gui.ui.sess == null) {
	    return Guidance.empty();
	}
	Gob player = gui.map.player();
	String selected = target();
	if(player == null || selected == null) {return Guidance.empty();}
	Coord playerTile = player.rc.floor(MCache.tilesz);
	long now = System.currentTimeMillis();
	long heatVersion = seq, tileVersion = TileQuality.seq;
	synchronized(lock) {
	    if(cachedHeatSeq == heatVersion && cachedTileSeq == tileVersion && playerTile.equals(cachedPlayerTile)
	       && now - cachedAt < 2000) {return cached;}
	}
	Guidance computed = computeGuidance(gui, player, selected);
	synchronized(lock) {
	    cached = computed;
	    cachedHeatSeq = heatVersion;
	    cachedTileSeq = tileVersion;
	    cachedPlayerTile = playerTile;
	    cachedAt = now;
	}
	return computed;
    }

    private Guidance computeGuidance(GameUI gui, Gob player, String selected) {
	MCache map = gui.ui.sess.glob.map;
	List<Sample> samples = new ArrayList<>();
	Set<TileRef> sampled = new HashSet<>();
	for(TileQuality.TileSnapshot tile : gui.tileQuality.snapshotAll()) {
	    Short quality = tile.kinds.get(selected);
	    if(quality == null) {continue;}
	    Coord2d rc = world(map, new TileRef(tile.gridId, tile.tileIdx));
	    if(rc == null || rc.dist(player.rc) > LOCAL_RADIUS) {continue;}
	    TileRef ref = new TileRef(tile.gridId, tile.tileIdx);
	    samples.add(new Sample(ref, selected, quality, rc));
	    sampled.add(ref);
	}
	List<Coord2d> empty = new ArrayList<>();
	Set<TileRef> covered;
	synchronized(lock) {covered = new LinkedHashSet<>(attempts);}
	for(TileRef ref : covered) {
	    if(sampled.contains(ref)) {continue;}
	    Coord2d rc = world(map, ref);
	    if(rc != null && rc.dist(player.rc) <= LOCAL_RADIUS) {empty.add(rc);}
	}
	SpatialPeakSearch.Guidance fit = SpatialPeakSearch.guide(samples, player.rc, STEP);
	Coord2d next = fit.peak ? null : snapFrontier(gui, fit.next, fit.best, covered);
	double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
	for(Sample sample : samples) {min = Math.min(min, sample.value()); max = Math.max(max, sample.value());}
	if(samples.isEmpty()) {min = max = Double.NaN;}
	return new Guidance(Collections.unmodifiableList(samples), Collections.unmodifiableList(empty), fit, next, min, max);
    }

    private static Coord2d snapFrontier(GameUI gui, Coord2d desired, Coord2d best, Set<TileRef> attempts) {
	if(desired == null || best == null) {return null;}
	MCache map = gui.ui.sess.glob.map;
	TerrainProbe probe = new TerrainProbe() {
	    public boolean loaded(Coord tile) {
		try {map.getgridt(tile); return true;} catch(Loading loading) {return false;}
	    }
	    public boolean minedFloor(Coord tile) {return MapHelper.isMinedFloorTile(gui, tile);}
	};
	Set<Coord> tested = new HashSet<>();
	for(TileRef ref : attempts) {
	    Coord2d rc = world(map, ref);
	    if(rc != null) {tested.add(rc.floor(MCache.tilesz));}
	}
	return snapFrontier(desired, best, tested, probe);
    }

    static Coord2d snapFrontier(Coord2d desired, Coord2d best, Set<Coord> tested, TerrainProbe probe) {
	if(desired == null || best == null || probe == null) {return null;}
	Coord center = desired.floor(MCache.tilesz);
	Coord bestTile = best.floor(MCache.tilesz);
	Coord picked = null;
	double score = Double.MAX_VALUE;
	for(int radius = 0; radius <= 6; radius++) {
	    for(int y = -radius; y <= radius; y++) for(int x = -radius; x <= radius; x++) {
		if(radius > 0 && Math.max(Math.abs(x), Math.abs(y)) != radius) {continue;}
		Coord tile = center.add(x, y);
		if(tested != null && tested.contains(tile)) {continue;}
		if(!frontier(tile, probe)) {continue;}
		Coord2d rc = tileCenter(tile);
		double distance = rc.dist(desired);
		Coord2d wanted = desired.sub(best);
		Coord2d actual = rc.sub(tileCenter(bestTile));
		double alignmentPenalty = 0;
		double wn = Math.hypot(wanted.x, wanted.y), an = Math.hypot(actual.x, actual.y);
		if(wn > 0 && an > 0) {alignmentPenalty = (1 - (wanted.x * actual.x + wanted.y * actual.y) / (wn * an)) * STEP;}
		double candidateScore = distance + alignmentPenalty;
		if(candidateScore < score) {score = candidateScore; picked = tile;}
	    }
	    if(picked != null) {break;}
	}
	return picked == null ? null : tileCenter(picked);
    }

    private static boolean frontier(Coord tile, TerrainProbe probe) {
	if(!probe.loaded(tile) || probe.minedFloor(tile)) {return false;}
	for(Coord d : new Coord[]{Coord.of(1, 0), Coord.of(-1, 0), Coord.of(0, 1), Coord.of(0, -1)}) {
	    Coord neighbor = tile.add(d);
	    if(probe.loaded(neighbor) && probe.minedFloor(neighbor)) {return true;}
	}
	return false;
    }

    static Color localColor(double value, double min, double max, int alpha) {
	double t = (!Double.isFinite(min) || !Double.isFinite(max) || max <= min)
	    ? .5 : Math.max(0, Math.min(1, (value - min) / (max - min)));
	if(t < .5) {
	    double q = t * 2;
	    return new Color((int)(40 + 215 * q), (int)(100 + 120 * q), (int)(255 - 205 * q), alpha);
	}
	double q = (t - .5) * 2;
	return new Color((int)(255 - 215 * q), (int)(220 + 35 * q), (int)(50 + 30 * q), alpha);
    }

    static boolean isMiningKind(String key) {
	if(key == null) {return false;}
	String normalized = MiningQualityCatalog.normalizeKey(key);
	return normalized.startsWith(TileQuality.KEY_STONE_PREFIX)
	    || normalized.startsWith(TileQuality.KEY_ORE_PREFIX)
	    || normalized.startsWith(TileQuality.KEY_GEM_PREFIX)
	    || TileQuality.KEY_CRYSTAL.equals(normalized);
    }

    private static TileRef resolve(GameUI gui, Coord2d rc) {
	if(gui == null || gui.ui == null || gui.ui.sess == null || rc == null) {return null;}
	Coord gc = rc.floor(MCache.tilesz);
	try {
	    MCache.Grid grid = gui.ui.sess.glob.map.getgridt(gc);
	    Coord local = gc.sub(grid.gc.mul(MCache.cmaps));
	    return new TileRef(grid.id, local.x + local.y * MCache.cmaps.x);
	} catch(Loading loading) {return null;}
    }

    private static Coord2d world(MCache map, TileRef ref) {
	MCache.Grid grid = map.getgrid(ref.gridId);
	if(grid == null) {return null;}
	Coord local = Coord.of(ref.tileIdx % MCache.cmaps.x, ref.tileIdx / MCache.cmaps.x);
	return tileCenter(grid.gc.mul(MCache.cmaps).add(local));
    }

    private static Coord2d tileCenter(Coord tile) {
	return Coord2d.of((tile.x + .5) * MCache.tilesz.x, (tile.y + .5) * MCache.tilesz.y);
    }

    public static String quality(double q) {
	return Math.abs(q - Math.rint(q)) < .05 ? String.format(Locale.ROOT, "%.0f", q) : String.format(Locale.ROOT, "%.1f", q);
    }
}
