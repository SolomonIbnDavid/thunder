package thunder.combat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Known combat-target resource families plus normalization helpers. */
public final class CombatTargetCatalog {
    public static final class Entry {
        public final String key;
        public final String label;

        Entry(String key, String label) {
            this.key = key;
            this.label = label;
        }

        @Override
        public String toString() {
            return(label);
        }
    }

    private static final List<Entry> ENTRIES;

    static {
        List<Entry> entries = Arrays.asList(
            entry("gfx/kritter/adder/", "Adder"),
            entry("gfx/kritter/ants/", "Ants"),
            entry("gfx/kritter/badger/", "Badger"),
            entry("gfx/kritter/bat/", "Bat"),
            entry("gfx/kritter/bear/", "Bear"),
            entry("gfx/kritter/beaver/", "Beaver"),
            entry("gfx/kritter/bees/", "Bees"),
            entry("gfx/kritter/boar/", "Boar"),
            entry("gfx/kritter/boreworm/", "Boreworm"),
            entry("gfx/kritter/cattle/aurochs", "Aurochs"),
            entry("gfx/kritter/caveangler/", "Cave Angler"),
            entry("gfx/kritter/cavelouse/", "Cave Louse"),
            entry("gfx/kritter/chasmconch/", "Chasm Conch"),
            entry("gfx/kritter/crane/crane", "Crane"),
            entry("gfx/kritter/eagleowl/", "Eagle Owl"),
            entry("gfx/kritter/fox/", "Fox"),
            entry("gfx/kritter/goat/wildgoat", "Wildgoat"),
            entry("gfx/kritter/goldeneagle", "Golden Eagle"),
            entry("gfx/kritter/greyseal", "Grey Seal"),
            entry("gfx/kritter/horse/", "Wild Horse"),
            entry("gfx/kritter/lynx/", "Lynx"),
            entry("gfx/kritter/mammoth/", "Mammoth"),
            entry("gfx/kritter/moose/", "Moose"),
            entry("gfx/kritter/nidbane/", "Nidbane"),
            entry("gfx/kritter/ooze/", "Green Ooze"),
            entry("gfx/kritter/orca/", "Orca"),
            entry("gfx/kritter/otter/", "Otter"),
            entry("gfx/kritter/pelican/", "Pelican"),
            entry("gfx/kritter/rat/blackrat", "Black Rat"),
            entry("gfx/kritter/rat/caverat", "Cave Rat"),
            entry("gfx/kritter/rat/fatrat", "Fat Rat"),
            entry("gfx/kritter/reddeer/", "Red Deer"),
            entry("gfx/kritter/reindeer/", "Reindeer"),
            entry("gfx/kritter/roedeer/", "Roe Deer"),
            entry("gfx/kritter/sheep/mouflon", "Mouflon"),
            entry("gfx/kritter/spermwhale/", "Sperm Whale"),
            entry("gfx/kritter/stoat/", "Stoat"),
            entry("gfx/kritter/swan/", "Swan"),
            entry("gfx/kritter/troll/", "Troll"),
            entry("gfx/kritter/walrus/", "Walrus"),
            entry("gfx/kritter/wildbees/beeswarm", "Wild Bees"),
            entry("gfx/kritter/wolf/", "Wolf"),
            entry("gfx/kritter/wolverine/", "Wolverine"),
            entry("gfx/kritter/woodgrouse/woodgrouse-m", "Wood Grouse")
        );
        entries = new ArrayList<>(entries);
        Collections.sort(entries, Comparator.comparing(e -> e.label));
        ENTRIES = Collections.unmodifiableList(entries);
    }

    private CombatTargetCatalog() {
    }

    public static List<Entry> entries() {
        return(ENTRIES);
    }

    public static Entry knownMatch(String resource) {
        String normalized = normalize(resource);
        Entry best = null;
        for(Entry entry : ENTRIES) {
            if(normalized.startsWith(entry.key) && (best == null || entry.key.length() > best.key.length()))
                best = entry;
        }
        return(best);
    }

    public static String labelFor(String resource) {
        Entry known = knownMatch(resource);
        if(known != null)
            return(known.label);
        String normalized = normalize(resource);
        if(normalized.isEmpty())
            return("Unknown target");
        int slash = normalized.lastIndexOf('/');
        String tail = slash < 0 ? normalized : normalized.substring(slash + 1);
        if(tail.isEmpty() && slash > 0) {
            int previous = normalized.lastIndexOf('/', slash - 1);
            tail = normalized.substring(previous + 1, slash);
        }
        return(title(tail.replace('-', ' ').replace('_', ' ')));
    }

    public static String normalize(String resource) {
        if(resource == null)
            return("");
        String normalized = resource.trim().replace('\\', '/').toLowerCase(Locale.ROOT);
        return(normalized.replaceFirst("\\[[0-9]+\\]$", ""));
    }

    private static Entry entry(String key, String label) {
        return(new Entry(normalize(key), label));
    }

    private static String title(String text) {
        StringBuilder result = new StringBuilder();
        boolean upper = true;
        for(int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if(upper && Character.isLetter(ch)) {
                result.append(Character.toUpperCase(ch));
                upper = false;
            } else {
                result.append(ch);
                upper = Character.isWhitespace(ch);
            }
        }
        return(result.length() == 0 ? "Unknown target" : result.toString());
    }
}
