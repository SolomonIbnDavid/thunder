package thunder;

import haven.Equipory;
import haven.GItem;
import haven.GameUI;
import haven.ItemInfo;
import haven.Loading;
import haven.WItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Casting-rod kit from equipped hands: pole + line + hook + lure (or bait).
 * Geometric mean {@code (pole×line×hook×lure)^(1/4)} is the cited gear softcap.
 */
public final class FishingKit {
    private static final Set<String> LURES = normSet(
	"Copper Comet", "Copperbrush Snapper", "Feather Fly", "Gold Spoon-Lure",
	"Pinecone Plug", "Poppy Wobbler", "Rock Lobster", "Steelbrush Plunger",
	"Tin Fly", "Woodfish"
    );
    private static final Set<String> BAITS = normSet(
	"Woodworm", "Entrails", "Earthworm", "Ant Empress", "Ant Larvae", "Ant Pupae",
	"Ant Queen", "Ant Soldiers", "Aphids", "Bay Shrimp", "Bee Larvae",
	"Brimstone Butterfly", "Cave Moth", "Chum Bait", "Emerald Dragonfly", "Firefly",
	"Grasshopper", "Grub", "Ladybug", "Leech", "Monarch Butterfly", "Moonmoth",
	"Raw Crab", "Raw Lobster", "Ruby Dragonfly", "Sand Flea", "Silkmoth", "Silkworm",
	"Silkworm Egg", "Springtime Bumblebee", "Stag Beetle", "Tick", "Waterstrider"
    );

    private FishingKit() {}

    public enum Kind { POLE, LINE, HOOK, LURE, BAIT, OTHER }

    public static final class Snapshot {
	public final String pole, line, hook, lure, bait;
	public final double poleQ, lineQ, hookQ, lureQ, baitQ;
	public final boolean loaded;

	public Snapshot(String pole, double poleQ, String line, double lineQ,
			String hook, double hookQ, String lure, double lureQ,
			String bait, double baitQ) {
	    this(pole, poleQ, line, lineQ, hook, hookQ, lure, lureQ, bait, baitQ, true);
	}

	public Snapshot(String pole, double poleQ, String line, double lineQ,
			String hook, double hookQ, String lure, double lureQ,
			String bait, double baitQ, boolean loaded) {
	    this.pole = pole;
	    this.poleQ = poleQ;
	    this.line = line;
	    this.lineQ = lineQ;
	    this.hook = hook;
	    this.hookQ = hookQ;
	    this.lure = lure;
	    this.lureQ = lureQ;
	    this.bait = bait;
	    this.baitQ = baitQ;
	    this.loaded = loaded;
	}

	public boolean hasPole() {return pole != null;}

	public boolean ready() {
	    return loaded && pole != null && line != null && hook != null && (lure != null || bait != null);
	}

	/** Pole in hand but line, hook, or lure/bait missing. */
	public boolean emptyKit() {
	    return loaded && hasPole() && !ready();
	}

	public boolean castingRod() {
	    return pole != null && FishingTackle.norm(pole).contains("casting rod");
	}

	public double gearMean() {
	    if(!ready()) {return 0;}
	    double fourth = lure != null ? lureQ : baitQ;
	    if(!(poleQ > 0) || !(lineQ > 0) || !(hookQ > 0) || !(fourth > 0)) {return 0;}
	    return Math.pow(poleQ * lineQ * hookQ * fourth, 0.25);
	}

	public String missing() {
	    if(!hasPole()) {return "no pole in hand";}
	    List<String> miss = new ArrayList<>();
	    if(line == null) {miss.add("line");}
	    if(hook == null) {miss.add("hook");}
	    if(lure == null && bait == null) {miss.add(castingRod() ? "lure" : "lure/bait");}
	    return miss.isEmpty() ? null : String.join("/", miss);
	}
    }

    public static Snapshot of(String pole, double poleQ, String line, double lineQ,
			      String hook, double hookQ, String lure, double lureQ) {
	return new Snapshot(pole, poleQ, line, lineQ, hook, hookQ, lure, lureQ, null, 0);
    }

    public static Kind classify(String name) {
	if(name == null || name.isEmpty()) {return Kind.OTHER;}
	String n = FishingTackle.norm(name);
	if(n.contains("casting rod") || n.contains("fishingpole")) {return Kind.POLE;}
	if(n.contains("fishline")) {return Kind.LINE;}
	if(n.contains("hook")) {return Kind.HOOK;}
	if(LURES.contains(n)) {return Kind.LURE;}
	if(BAITS.contains(n)) {return Kind.BAIT;}
	return Kind.OTHER;
    }

    public static Snapshot fromParts(List<String> names, List<Double> qs) {
	return fromParts(names, qs, true);
    }

    public static Snapshot fromParts(List<String> names, List<Double> qs, boolean loaded) {
	String pole = null, line = null, hook = null, lure = null, bait = null;
	double poleQ = 0, lineQ = 0, hookQ = 0, lureQ = 0, baitQ = 0;
	int n = names == null ? 0 : names.size();
	for(int i = 0; i < n; i++) {
	    String name = names.get(i);
	    double q = (qs != null && i < qs.size() && qs.get(i) != null) ? qs.get(i) : 0;
	    switch(classify(name)) {
	    case POLE:
		if(pole == null) {pole = name; poleQ = q;}
		break;
	    case LINE:
		if(line == null) {line = name; lineQ = q;}
		break;
	    case HOOK:
		if(hook == null) {hook = name; hookQ = q;}
		break;
	    case LURE:
		if(lure == null) {lure = name; lureQ = q;}
		break;
	    case BAIT:
		if(bait == null) {bait = name; baitQ = q;}
		break;
	    default:
		break;
	    }
	}
	return new Snapshot(pole, poleQ, line, lineQ, hook, hookQ, lure, lureQ, bait, baitQ, loaded);
    }

    public static Snapshot from(GameUI gui) {
	if(gui == null || gui.equipory == null) {
	    return new Snapshot(null, 0, null, 0, null, 0, null, 0, null, 0, true);
	}
	List<String> names = new ArrayList<>();
	List<Double> qs = new ArrayList<>();
	boolean[] loaded = {true};
	WItem[] slots = gui.equipory.slots;
	for(int idx : new int[] {Equipory.SLOTS.HAND_LEFT.idx, Equipory.SLOTS.HAND_RIGHT.idx}) {
	    if(idx < 0 || idx >= slots.length) {continue;}
	    WItem w = slots[idx];
	    if(w == null || w.item == null) {continue;}
	    collect(w.item, names, qs, true, loaded);
	}
	return fromParts(names, qs, loaded[0]);
    }

    private static void collect(GItem item, List<String> names, List<Double> qs, boolean includeSelf, boolean[] loaded) {
	if(item == null) {return;}
	try {
	    if(includeSelf) {
		String name = itemName(item);
		if(name != null) {
		    names.add(name);
		    qs.add(itemQ(item));
		}
	    }
	    if(item.contents != null) {
		for(WItem ch : item.contents.children(WItem.class)) {
		    collect(ch.item, names, qs, true, loaded);
		}
	    } else {
		List<ItemInfo> info = item.info();
		for(ItemInfo.Contents c : ItemInfo.findall(ItemInfo.Contents.class, info)) {
		    collectInfo(c.sub, names, qs);
		}
	    }
	} catch(Loading ignored) {
	    loaded[0] = false;
	}
    }

    private static void collectInfo(List<ItemInfo> info, List<String> names, List<Double> qs) {
	if(info == null) {return;}
	for(ItemInfo.Name nm : ItemInfo.findall(ItemInfo.Name.class, info)) {
	    if(nm.original != null && !nm.original.isEmpty()) {
		names.add(nm.original);
		qs.add(0.0);
	    }
	}
	for(ItemInfo.Contents c : ItemInfo.findall(ItemInfo.Contents.class, info)) {
	    collectInfo(c.sub, names, qs);
	}
    }

    static String itemName(GItem item) {
	try {
	    List<ItemInfo> info = item.info();
	    ItemInfo.Name nm = ItemInfo.find(ItemInfo.Name.class, info);
	    if(nm != null && nm.original != null && !nm.original.isEmpty()) {return nm.original;}
	} catch(Loading ignored) {}
	String res = item.resname();
	return (res == null || res.isEmpty()) ? null : res;
    }

    private static double itemQ(GItem item) {
	try {
	    return item.quality();
	} catch(Loading e) {
	    return 0;
	}
    }

    private static Set<String> normSet(String... names) {
	Set<String> s = new HashSet<>();
	for(String n : names) {s.add(FishingTackle.norm(n));}
	return Collections.unmodifiableSet(s);
    }
}
