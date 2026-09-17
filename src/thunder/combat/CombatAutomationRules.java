package thunder.combat;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/** Pure decision rules for the combat automation profile. */
public final class CombatAutomationRules {
    public static final int ENEMY_RED_TARGET = 55;
    public static final int OWN_OPENING_LIMIT = 40;
    public static final double ACTION_QUEUE_LEAD = 0.400;
    public static final double MAX_ACTION_DISTANCE = 55.0;
    public static final int MAX_CONSECUTIVE_ACK_TIMEOUTS = 3;

    public enum Opening {
        GREEN,
        YELLOW,
        RED,
        BLUE
    }

    public enum Decision {
        WAIT,
        RESTORE_GREEN,
        RESTORE_YELLOW,
        RESTORE_RED,
        RESTORE_BLUE,
        QUICK_BARRAGE,
        FULL_CIRCLE
    }

    public static final class Snapshot {
        public final boolean combatActive;
        public final boolean globalCooldownReady;
        public final int ownGreen;
        public final int ownYellow;
        public final int ownRed;
        public final int ownBlue;
        public final int enemyRed;
        public final Set<Opening> clearableOpenings;

        public Snapshot(boolean combatActive, boolean globalCooldownReady,
                        int ownGreen, int ownYellow, int ownRed, int ownBlue, int enemyRed) {
            this(combatActive, globalCooldownReady,
                ownGreen, ownYellow, ownRed, ownBlue, enemyRed, EnumSet.allOf(Opening.class));
        }

        public Snapshot(boolean combatActive, boolean globalCooldownReady,
                        int ownGreen, int ownYellow, int ownRed, int ownBlue, int enemyRed,
                        Set<Opening> clearableOpenings) {
            this.combatActive = combatActive;
            this.globalCooldownReady = globalCooldownReady;
            this.ownGreen = ownGreen;
            this.ownYellow = ownYellow;
            this.ownRed = ownRed;
            this.ownBlue = ownBlue;
            this.enemyRed = enemyRed;
            EnumSet<Opening> available = EnumSet.noneOf(Opening.class);
            if(clearableOpenings != null)
                available.addAll(clearableOpenings);
            this.clearableOpenings = Collections.unmodifiableSet(available);
        }
    }

    private CombatAutomationRules() {
    }

    public static Decision decide(Snapshot state) {
        if(state == null || !state.combatActive)
            return Decision.WAIT;
        if(!state.globalCooldownReady)
            return Decision.WAIT;

        Opening unsafe = worstUnsafeOpening(state);
        if(unsafe != null)
            return restorationDecision(unsafe);
        return (state.enemyRed < ENEMY_RED_TARGET) ? Decision.QUICK_BARRAGE : Decision.FULL_CIRCLE;
    }

    public static Opening worstUnsafeOpening(Snapshot state) {
        if(state == null)
            return null;
        int highest = OWN_OPENING_LIMIT - 1;
        Opening result = null;

        /* Resolve ties toward red/yellow first because Zig-Zag can lower both. */
        int[] values = {state.ownRed, state.ownYellow, state.ownBlue, state.ownGreen};
        Opening[] openings = {Opening.RED, Opening.YELLOW, Opening.BLUE, Opening.GREEN};
        for(int i = 0; i < values.length; i++) {
            if(state.clearableOpenings.contains(openings[i]) && values[i] > highest) {
                highest = values[i];
                result = openings[i];
            }
        }
        return result;
    }

    public static boolean canAttemptAction(double targetDistance) {
        return Double.isFinite(targetDistance) && targetDistance >= 0 &&
            targetDistance <= MAX_ACTION_DISTANCE;
    }

    public static boolean cooldownReadyToQueue(double now, double cooldownEnd) {
        return Double.isFinite(now) && Double.isFinite(cooldownEnd) &&
            cooldownEnd <= now + ACTION_QUEUE_LEAD;
    }

    public static boolean shouldRetryMissingAcknowledgement(int consecutiveTimeouts,
                                                              double targetDistance) {
        return !canAttemptAction(targetDistance) ||
            consecutiveTimeouts < MAX_CONSECUTIVE_ACK_TIMEOUTS;
    }

    private static Decision restorationDecision(Opening opening) {
        switch(opening) {
        case GREEN: return Decision.RESTORE_GREEN;
        case YELLOW: return Decision.RESTORE_YELLOW;
        case RED: return Decision.RESTORE_RED;
        case BLUE: return Decision.RESTORE_BLUE;
        default: throw new IllegalArgumentException("Unknown opening: " + opening);
        }
    }
}
