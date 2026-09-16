package thunder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Species → line/hook/lure lookup. Sevenless dump (W12-ish) with W16 addenda.
 * Not law for W16 — live 100% land beats this table. Do not auto-unequip.
 */
public final class FishingTackle {
    public static final class Combo {
	public final String fish;
	public final String biome;
	public final String lines;
	public final String hooks;
	public final String lures;
	public final boolean wikiW10;

	Combo(String fish, String biome, String lines, String hooks, String lures, boolean wikiW10) {
	    this.fish = fish;
	    this.biome = biome;
	    this.lines = lines;
	    this.hooks = hooks;
	    this.lures = lures;
	    this.wikiW10 = wikiW10;
	}

	public String hint() {
	    String h = String.format("%s · %s · %s", lines, hooks, lures);
	    if(wikiW10) {h += " (wiki W10 — unconfirmed)";}
	    return h;
	}
    }

    private static final Map<String, Combo> BY_NORM = new LinkedHashMap<>();
    private static final List<String> SPECIES = new ArrayList<>();

    static {
	// Fresh — Sevenless
	add("Asp", "fresh", "straw/flax", "bone/metal", "comet/plug/lobster", false);
	add("Brill", "fresh", "taproot/hide/straw/flax", "bone/metal", "comet/tinfly/plug", false);
	add("Burbot", "fresh", "straw/flax/hide", "bone/metal", "comet/plug/lobster", false);
	add("Bream", "fresh", "hide/flax", "bone/metal", "comet/featherfly", false);
	add("Carp", "fresh", "flax", "bone", "tinfly", false);
	add("Catfish", "fresh", "taproot", "metal", "comet", false);
	add("Chub", "fresh", "cattail/flax", "bone/metal", "lobster", false);
	add("Eel", "fresh", "cattail/hide", "bone", "lobster", false);
	add("Grayling", "fresh", "flax/straw", "bone/metal", "comet/lobster", false);
	add("Ide", "fresh", "hide", "bone", "plug", false);
	add("Lavaret", "fresh", "straw", "bone/metal", "tinfly/plug", false);
	add("Perch", "fresh", "flax", "bone", "featherfly/tinfly", false);
	add("Pike", "fresh", "straw/flax", "bone/metal", "tinfly/featherfly/lobster", false);
	add("Plaice", "fresh", "flax/hide", "bone/metal", "comet/tinfly/lobster/plug/wood", false);
	add("Salmon", "fresh", "straw/taproot", "metal/bone", "lobster", false);
	add("Silver Bream", "fresh", "straw/flax/straps", "bone/metal", "comet/tinfly/plug/woodfish", false);
	add("Smelt", "fresh", "flax/straw", "bone/metal", "comet/tinfly/plug", false);
	add("Sturgeon", "fresh", "flax/hide", "metal/bone", "featherfly/plug/lobster", false);
	add("Roach", "fresh", "cattail/flax", "bone", "tinfly/plug/lobster", false);
	add("Ruffe", "fresh", "flax", "bone", "tinfly", false);
	add("Tench", "fresh", "flax/hide", "bone", "plug", false);
	add("Trout", "fresh", "flax", "metal", "comet", false);
	add("Zope", "fresh", "flax/hide", "bone/metal", "plug/comet", false);
	add("Zander", "fresh", "cattail", "bone", "lobster", false);
	// Ocean — Sevenless incomplete
	add("Saithe", "ocean", "hide", "bone", "lobster", false);
	add("Mullet", "ocean", "hide", "bone", "lobster/woodfish", false);
	add("Mackerel", "ocean", "hide", "bone", "woodfish", false);
	add("Haddock", "ocean", "hide", "bone", "woodfish", false);
	add("Rosefish", "ocean", "hide", "bone", "woodfish", false);
	add("Bass", "ocean", "hide", "bone", "lobster", false);
	add("Pomfret", "ocean", "straw", "metal", "lobster", false);
	// Cave — Sevenless
	add("Cave Sculpin", "cave", "hide", "bone", "lobster", false);
	add("Cavelacanth", "cave", "hide", "bone", "featherfly/woodfish/pinecone plug", false);
	add("Pale Ghostfish", "cave", "hemp", "bone", "lobster/poppy", false);
	// Wiki W10 scraps not in Sevenless
	add("Abyss Gazer", "cave", "bushcraft", "metal", "poppy/woodfish", true);
	add("Cod", "ocean", "unknown", "unknown", "woodfish/comet", true);
	add("Herring", "ocean", "woodsman's", "unknown", "lobster/feather/plug", true);
	add("Whiting", "ocean", "unknown", "unknown", "feather/lobster", true);
    }

    private FishingTackle() {}

    private static void add(String fish, String biome, String lines, String hooks, String lures, boolean wikiW10) {
	Combo c = new Combo(fish, biome, lines, hooks, lures, wikiW10);
	BY_NORM.put(norm(fish), c);
	SPECIES.add(fish);
    }

    public static String norm(String name) {
	if(name == null) {return "";}
	String n = name.toLowerCase(Locale.ROOT).trim().replaceFirst(":+$", "").trim().replace('-', ' ');
	n = n.replaceAll("\\s+", " ");
	if(n.equals("rose fish")) {return "rosefish";}
	if(n.equals("silver bream")) {return "silver bream";}
	return n;
    }

    public static Combo suggest(String fish) {
	if(fish == null || fish.isEmpty()) {return null;}
	return BY_NORM.get(norm(fish));
    }

    public static List<String> species() {
	return Collections.unmodifiableList(SPECIES);
    }
}
