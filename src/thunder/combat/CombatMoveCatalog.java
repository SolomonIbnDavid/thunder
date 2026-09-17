package thunder.combat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Stable resource IDs for the complete known combat-move catalog. */
public final class CombatMoveCatalog {
    public enum Category {
        MOVE("Move"), RESTORATION("Restoration"), MANEUVER("Maneuver"), ATTACK("Attack"), UNKNOWN("Deck move");

        public final String label;

        Category(String label) {
            this.label = label;
        }
    }

    public static final class Entry {
        public final String resource;
        public final String label;
        public final Category category;
        public final boolean distanceCapable;
        public final boolean known;

        Entry(String resource, String label, Category category, boolean distanceCapable, boolean known) {
            this.resource = resource;
            this.label = label;
            this.category = category;
            this.distanceCapable = distanceCapable;
            this.known = known;
        }

        @Override
        public String toString() {
            return(label);
        }
    }

    private static final List<Entry> KNOWN = Collections.unmodifiableList(Arrays.asList(
        move("paginae/atk/dash", "Dash", Category.MOVE, true),
        move("paginae/atk/oppknock", "Opportunity Knocks", Category.MOVE, false),
        move("paginae/atk/takeaim", "Take Aim", Category.MOVE, true),
        move("paginae/atk/think", "Think", Category.MOVE, true),

        move("paginae/atk/artevade", "Artful Evasion", Category.RESTORATION, true),
        move("paginae/atk/fdodge", "Feigned Dodge", Category.RESTORATION, false),
        move("paginae/atk/flex", "Flex", Category.RESTORATION, false),
        move("paginae/atk/jump", "Jump", Category.RESTORATION, true),
        move("paginae/atk/qdodge", "Quick Dodge", Category.RESTORATION, true),
        move("paginae/atk/regain", "Regain Composure", Category.RESTORATION, true),
        move("paginae/atk/sidestep", "Sidestep", Category.RESTORATION, true),
        move("paginae/atk/watchmoves", "Watch Its Moves", Category.RESTORATION, false),
        move("paginae/atk/yieldground", "Yield Ground", Category.RESTORATION, true),
        move("paginae/atk/zigzag", "Zig-Zag Ruse", Category.RESTORATION, true),

        move("paginae/atk/bloodlust", "Bloodlust", Category.MANEUVER, false),
        move("paginae/atk/chinup", "Chin Up", Category.MANEUVER, false),
        move("paginae/atk/combmed", "Combat Meditation", Category.MANEUVER, false),
        move("paginae/atk/dorg", "Death or Glory", Category.MANEUVER, false),
        move("paginae/atk/oakstance", "Oak Stance", Category.MANEUVER, false),
        move("paginae/atk/parry", "Parry", Category.MANEUVER, false),
        move("paginae/atk/shield", "Shield Up", Category.MANEUVER, false),
        move("paginae/atk/toarms", "To Arms", Category.MANEUVER, false),

        move("paginae/atk/chop", "Chop", Category.ATTACK, false),
        move("paginae/atk/cleave", "Cleave", Category.ATTACK, false),
        move("paginae/atk/fullcircle", "Full Circle", Category.ATTACK, false),
        move("paginae/atk/gojug", "Go for the Jugular", Category.ATTACK, false),
        move("paginae/atk/haymaker", "Haymaker", Category.ATTACK, false),
        move("paginae/atk/kick", "Kick", Category.ATTACK, false),
        move("paginae/atk/knockteeth", "Knock Its Teeth Out", Category.ATTACK, false),
        move("paginae/atk/lefthook", "Left Hook", Category.ATTACK, false),
        move("paginae/atk/lowblow", "Low Blow", Category.ATTACK, false),
        move("paginae/atk/pow", "Punch", Category.ATTACK, false),
        move("paginae/atk/punchboth", "Punch 'em Both", Category.ATTACK, false),
        move("paginae/atk/barrage", "Quick Barrage", Category.ATTACK, false),
        move("paginae/atk/ravenbite", "Raven's Bite", Category.ATTACK, false),
        move("paginae/atk/ripapart", "Rip Apart", Category.ATTACK, false),
        move("paginae/atk/sideswipe", "Sideswipe", Category.ATTACK, false),
        move("paginae/atk/stealthunder", "Steal Thunder", Category.ATTACK, false),
        move("paginae/atk/sting", "Sting", Category.ATTACK, false),
        move("paginae/atk/sos", "Storm of Swords", Category.ATTACK, false),
        move("paginae/atk/takedown", "Takedown", Category.ATTACK, false),
        move("paginae/atk/uppercut", "Uppercut", Category.ATTACK, false)
    ));

    private static final Map<String, Entry> BY_RESOURCE;

    static {
        Map<String, Entry> byResource = new LinkedHashMap<>();
        for(Entry entry : KNOWN)
            byResource.put(entry.resource, entry);
        BY_RESOURCE = Collections.unmodifiableMap(byResource);
    }

    private CombatMoveCatalog() {
    }

    public static List<Entry> known() {
        return(KNOWN);
    }

    public static Entry byResource(String resource) {
        return(BY_RESOURCE.get(resource));
    }

    public static String label(String resource) {
        Entry entry = byResource(resource);
        if(entry != null)
            return(entry.label);
        if(resource == null || resource.trim().isEmpty())
            return("Unknown move");
        int slash = resource.lastIndexOf('/');
        return(slash < 0 ? resource : resource.substring(slash + 1));
    }

    public static boolean distanceCapable(String resource) {
        Entry entry = byResource(resource);
        return(entry != null && entry.distanceCapable);
    }

    public static List<Entry> withDiscovered(Collection<Entry> discovered) {
        Map<String, Entry> merged = new LinkedHashMap<>(BY_RESOURCE);
        if(discovered != null) {
            for(Entry entry : discovered) {
                if(entry != null && entry.resource != null && !merged.containsKey(entry.resource))
                    merged.put(entry.resource, entry);
            }
        }
        List<Entry> result = new ArrayList<>(merged.values());
        Collections.sort(result, Comparator
            .comparing((Entry entry) -> entry.category.ordinal())
            .thenComparing(entry -> entry.label));
        return(result);
    }

    public static Entry discovered(String resource, String localizedName) {
        Entry known = byResource(resource);
        if(known != null)
            return(known);
        String label = localizedName == null || localizedName.trim().isEmpty() ? label(resource) : localizedName;
        return(new Entry(resource, label, Category.UNKNOWN, false, false));
    }

    private static Entry move(String resource, String label, Category category, boolean distance) {
        return(new Entry(resource, label, category, distance, true));
    }
}
