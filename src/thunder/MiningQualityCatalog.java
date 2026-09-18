package thunder;

import java.util.*;

/**
 * Stable identities for mineable stone, ore, and gemstone observations.
 *
 * Display names are the game names. Keys deliberately do not depend on the
 * resource slug: several ores use geological resource names which differ
 * from their displayed item name (for example magnetite is "Black Ore").
 */
public final class MiningQualityCatalog {
    public enum Category {
        STONE("stone", "Stone"),
        ORE("ore", "Ore"),
        GEM("gem", "Gemstone");

        public final String prefix;
        public final String displayName;

        Category(String prefix, String displayName) {
            this.prefix = prefix;
            this.displayName = displayName;
        }
    }

    public static final class Entry {
        public final Category category;
        public final String key;
        public final String name;
        public final String resourceName;

        private Entry(Category category, String name, String resourceSlug) {
            this.category = category;
            this.name = name;
            this.key = category.prefix + "/" + slug(name);
            this.resourceName = resourceSlug == null ? null : "gfx/invobjs/" + resourceSlug;
        }
    }

    private static final List<Entry> ENTRIES = new ArrayList<>();
    private static final Map<String, Entry> BY_KEY = new HashMap<>();
    private static final Map<String, Entry> BY_NAME = new HashMap<>();
    private static final Map<String, Entry> BY_RESOURCE = new HashMap<>();
    private static final List<Entry> GEMS = new ArrayList<>();

    static {
        // User-confirmed exhaustive mining-stone list. Resource exceptions are
        // explicit so old tracker keys can be migrated without guessing.
        add(Category.STONE, "Alabaster");
        add(Category.STONE, "Apatite");
        add(Category.STONE, "Arkose");
        add(Category.STONE, "Basalt");
        add(Category.STONE, "Bat Rock");
        add(Category.STONE, "Black Coal");
        add(Category.STONE, "Breccia");
        add(Category.STONE, "Cat Gold");
        add(Category.STONE, "Chert");
        add(Category.STONE, "Diabase");
        add(Category.STONE, "Diorite");
        add(Category.STONE, "Dolomite");
        add(Category.STONE, "Dross");
        add(Category.STONE, "Eclogite");
        add(Category.STONE, "Feldspar");
        add(Category.STONE, "Flint");
        add(Category.STONE, "Fluorospar");
        add(Category.STONE, "Gabbro");
        add(Category.STONE, "Gneiss");
        add(Category.STONE, "Granite");
        add(Category.STONE, "Graywacke");
        add(Category.STONE, "Greenschist");
        add(Category.STONE, "Hornblende");
        add(Category.STONE, "Jasper");
        add(Category.STONE, "Korund", "corund");
        add(Category.STONE, "Kyanite");
        add(Category.STONE, "Lava Rock");
        add(Category.STONE, "Limestone");
        add(Category.STONE, "Marble");
        add(Category.STONE, "Mica");
        add(Category.STONE, "Microlite");
        add(Category.STONE, "Obsidian");
        add(Category.STONE, "Olivine");
        add(Category.STONE, "Orthoclase");
        add(Category.STONE, "Pegmatite");
        add(Category.STONE, "Porphyry");
        add(Category.STONE, "Pumice");
        add(Category.STONE, "Quarryartz", "quarryquartz");
        add(Category.STONE, "Quartz");
        add(Category.STONE, "Rhyolite");
        add(Category.STONE, "Rock Crystal");
        add(Category.STONE, "Rock Salt", "halite");
        add(Category.STONE, "Sandstone");
        add(Category.STONE, "Schist");
        add(Category.STONE, "Serpentine");
        add(Category.STONE, "Shard of Conch", "petrifiedshell");
        add(Category.STONE, "Slag");
        add(Category.STONE, "Slate");
        add(Category.STONE, "Soapstone");
        add(Category.STONE, "Sodalite");
        add(Category.STONE, "Sunstone");
        add(Category.STONE, "Zincspar");

        // Display names and resource names differ for many ores.
        add(Category.ORE, "Black Ore", "magnetite");
        add(Category.ORE, "Bloodstone", "hematite");
        add(Category.ORE, "Cassiterite");
        add(Category.ORE, "Chalcopyrite");
        add(Category.ORE, "Cinnabar");
        add(Category.ORE, "Direvein", "petzite");
        add(Category.ORE, "Galena");
        add(Category.ORE, "Heavy Earth", "ilmenite");
        add(Category.ORE, "Horn Silver", "hornsilver");
        add(Category.ORE, "Iron Ochre", "limonite");
        add(Category.ORE, "Lead Glance", "leadglance");
        add(Category.ORE, "Leaf Ore", "nagyagite");
        add(Category.ORE, "Malachite");
        add(Category.ORE, "Meteorite");
        add(Category.ORE, "Peacock Ore", "peacockore");
        add(Category.ORE, "Schrifterz", "sylvanite");
        add(Category.ORE, "Silvershine", "argentite");
        add(Category.ORE, "Wine Glance", "cuprite");

        // The game's gemstone type catalog. All cuts and sizes share the
        // dynamic gfx/invobjs/gems/gemstone item resource.
        add(Category.GEM, "Amber", "gems/gemstone");
        add(Category.GEM, "Amethyst", "gems/gemstone");
        add(Category.GEM, "Diamond", "gems/gemstone");
        add(Category.GEM, "Emerald", "gems/gemstone");
        add(Category.GEM, "Jade", "gems/gemstone");
        add(Category.GEM, "Moonstone", "gems/gemstone");
        add(Category.GEM, "Onyx", "gems/gemstone");
        add(Category.GEM, "Opal", "gems/gemstone");
        add(Category.GEM, "Ruby", "gems/gemstone");
        add(Category.GEM, "Sapphire", "gems/gemstone");
        add(Category.GEM, "Topaz", "gems/gemstone");
        add(Category.GEM, "Turquoise", "gems/gemstone");

        ENTRIES.sort(Comparator.comparing((Entry e) -> e.category.ordinal())
                               .thenComparing(e -> e.name, String.CASE_INSENSITIVE_ORDER));
        GEMS.sort(Comparator.comparingInt((Entry e) -> e.name.length()).reversed());
    }

    private MiningQualityCatalog() {}

    private static void add(Category category, String name) {
        add(category, name, compact(name));
    }

    private static void add(Category category, String name, String resourceSlug) {
        Entry entry = new Entry(category, name, resourceSlug);
        ENTRIES.add(entry);
        BY_KEY.put(entry.key, entry);
        BY_NAME.put(normalizeName(name), entry);
        if(resourceSlug != null) {BY_RESOURCE.put(resourceSlug.toLowerCase(Locale.ROOT), entry);}
        if(category == Category.GEM) {GEMS.add(entry);}
    }

    public static List<Entry> entries() {
        return Collections.unmodifiableList(ENTRIES);
    }

    public static List<Entry> entries(Category category) {
        List<Entry> out = new ArrayList<>();
        for(Entry entry : ENTRIES) {
            if(entry.category == category) {out.add(entry);}
        }
        return Collections.unmodifiableList(out);
    }

    public static Set<String> names(Category category) {
        Set<String> out = new LinkedHashSet<>();
        for(Entry entry : ENTRIES) {
            if(entry.category == category) {out.add(entry.name);}
        }
        return Collections.unmodifiableSet(out);
    }

    public static Entry byKey(String key) {
        return BY_KEY.get(normalizeKey(key));
    }

    public static Entry byDisplayName(String name) {
        if(name == null) {return null;}
        return BY_NAME.get(normalizeName(name));
    }

    public static String keyForMinedName(String itemName) {
        if(itemName == null) {return null;}
        Entry entry = BY_NAME.get(normalizeName(stripStackSuffix(itemName)));
        return entry == null || entry.category == Category.GEM ? null : entry.key;
    }

    /** Extracts the type from names such as "Fair Smooth Onyx". */
    public static String keyForGemName(String itemName) {
        if(itemName == null) {return null;}
        String normalized = normalizeName(stripStackSuffix(itemName));
        for(Entry gem : GEMS) {
            String gemName = normalizeName(gem.name);
            if(normalized.equals(gemName) || normalized.endsWith(" " + gemName)) {return gem.key;}
        }
        String[] words = normalized.split(" ");
        if(words.length == 0 || words[words.length - 1].isEmpty()) {return null;}
        return Category.GEM.prefix + "/" + slug(words[words.length - 1]);
    }

    /**
     * Converts V2 tracker keys (bare gem names and stone/resource-slug keys)
     * to the stable catalog form. Unknown keys are retained verbatim.
     */
    public static String normalizeKey(String key) {
        if(key == null || key.isEmpty()) {return key;}
        String lower = key.toLowerCase(Locale.ROOT);
        Entry direct = BY_KEY.get(lower);
        if(direct != null) {return direct.key;}

        // Version-2 stored these three uncommon mine products as bare special
        // keys. They are ordinary support-building stone in the canonical
        // catalog, so retain their observations under the corresponding entry.
        switch(lower) {
            case "shell": return "stone/shard-of-conch";
            case "quartz": return "stone/quarryartz";
            case "catgold": return "stone/cat-gold";
        }

        Entry bareName = BY_NAME.get(normalizeName(lower));
        if(bareName != null && bareName.category == Category.GEM) {return bareName.key;}

        int slash = lower.indexOf('/');
        if(slash > 0) {
            String prefix = lower.substring(0, slash);
            String suffix = lower.substring(slash + 1);
            if(prefix.equals(Category.STONE.prefix) || prefix.equals(Category.ORE.prefix)) {
                Entry resource = BY_RESOURCE.get(suffix);
                if(resource != null) {return resource.key;}
                String compactSuffix = compact(suffix);
                for(Entry entry : ENTRIES) {
                    if(entry.category != Category.GEM && compact(entry.name).equals(compactSuffix)) {
                        return entry.key;
                    }
                }
            } else if(prefix.equals(Category.GEM.prefix)) {
                return Category.GEM.prefix + "/" + slug(suffix);
            }
        }
        return lower;
    }

    public static Category categoryOf(String key) {
        String normalized = normalizeKey(key);
        if(normalized == null) {return null;}
        for(Category category : Category.values()) {
            if(normalized.startsWith(category.prefix + "/")) {return category;}
        }
        return null;
    }

    public static String displayName(String key) {
        String normalized = normalizeKey(key);
        Entry entry = BY_KEY.get(normalized);
        if(entry != null) {return entry.name;}
        int slash = normalized == null ? -1 : normalized.indexOf('/');
        return title(slash < 0 ? normalized : normalized.substring(slash + 1));
    }

    private static String stripStackSuffix(String name) {
        String n = name.trim();
        String suffix = ", stack of";
        if(n.toLowerCase(Locale.ROOT).endsWith(suffix)) {
            n = n.substring(0, n.length() - suffix.length()).trim();
        }
        return n;
    }

    private static String normalizeName(String name) {
        return name == null ? "" : name.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String compact(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String slug(String value) {
        String out = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        return out.replaceAll("^-+|-+$", "");
    }

    private static String title(String value) {
        if(value == null || value.isEmpty()) {return value;}
        StringBuilder out = new StringBuilder();
        for(String part : value.split("[-_/]+")) {
            if(part.isEmpty()) {continue;}
            if(out.length() > 0) {out.append(' ');}
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return out.toString();
    }
}
