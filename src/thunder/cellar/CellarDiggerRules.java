package thunder.cellar;

import java.util.Locale;

/** Pure resource and safety rules for the Cellar Digger state machine. */
public final class CellarDiggerRules {
    public static final double LOW_ENERGY = 0.25;
    public static final double LOW_STAMINA = 0.40;
    public static final double STAMINA_RECOVERY_TARGET = 0.80;
    public static final int MIN_AUTO_DRINK_THRESHOLD = 40;
    public static final int MAX_ATTEMPTS = 3;
    public static final int MAX_CHIPS_PER_BOULDER = 500;
    public static final int MAX_DOOR_CYCLES = 128;
    public static final int GROUNDED_STABLE_SAMPLES = 3;

    private static final String CELLAR_DOOR = "gfx/terobjs/arch/cellardoor";
    private static final String BUMLING_PREFIX = "gfx/terobjs/bumlings/";

    private CellarDiggerRules() {}

    public enum DoorOutcome {CONTINUE, COMPLETE, RETRY}

    public static boolean isCellarDoor(String resid) {
        return CELLAR_DOOR.equals(baseResid(resid));
    }

    public static boolean isCellarTransition(String resid) {
        String n = baseResid(resid);
        return "gfx/terobjs/arch/cellarstairs".equals(n) ||
            "gfx/terobjs/arch/downstairs".equals(n) ||
            "gfx/terobjs/arch/upstairs".equals(n);
    }

    /** Cellar excavation creates only staged bumlings; ordinary surface
     * boulders are deliberately outside this bot's scope. */
    public static boolean isBumling(String resid) {
        String n = baseResid(resid);
        return n.startsWith(BUMLING_PREFIX) && n.length() > BUMLING_PREFIX.length();
    }

    /** Drawable state can be appended as a bracketed suffix by the client. */
    static String baseResid(String resid) {
        String n = resid == null ? "" : resid.toLowerCase(Locale.ROOT);
        int bracket = n.indexOf('[');
        return bracket > 0 ? n.substring(0, bracket) : n;
    }

    public static boolean energyTooLow(double energy) {
        return energy >= 0.0 && energy < LOW_ENERGY;
    }

    public static boolean staminaNeedsRecovery(double stamina) {
        return stamina >= 0.0 && stamina < LOW_STAMINA;
    }

    public static boolean staminaRecovered(double stamina) {
        return stamina >= STAMINA_RECOVERY_TARGET;
    }

    public static boolean autoDrinkThresholdSafe(int thresholdPercent) {
        return thresholdPercent >= MIN_AUTO_DRINK_THRESHOLD;
    }

    /** A released bumling is safe to interact with only after its authoritative
     * ground position has remained unchanged across several client samples. */
    public static boolean groundedBoulderStable(int stableSamples) {
        return stableSamples >= GROUNDED_STABLE_SAMPLES;
    }

    public static DoorOutcome doorOutcome(boolean bumlingVisible, boolean doorVisible,
                                          boolean cellarTransitionVisible) {
        if(bumlingVisible) return DoorOutcome.CONTINUE;
        if(!doorVisible && cellarTransitionVisible) return DoorOutcome.COMPLETE;
        return DoorOutcome.RETRY;
    }
}
