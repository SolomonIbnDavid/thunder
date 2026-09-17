package thunder.combat;

/** Shared timing and safety primitives for combat-plan execution. */
public final class CombatAutomationRules {
    public static final double ACTION_QUEUE_LEAD = 0.400;
    public static final double MAX_ACTION_DISTANCE = 55.0;
    public static final int MAX_CONSECUTIVE_ACK_TIMEOUTS = 3;

    public enum Opening {
        GREEN,
        YELLOW,
        RED,
        BLUE
    }

    private CombatAutomationRules() {
    }

    public static boolean canAttemptAction(double targetDistance) {
        return canAttemptAction(targetDistance, false);
    }

    public static boolean canAttemptAction(double targetDistance, boolean distanceCapable) {
        return Double.isFinite(targetDistance) && targetDistance >= 0 &&
            (distanceCapable || targetDistance <= MAX_ACTION_DISTANCE);
    }

    public static boolean cooldownReadyToQueue(double now, double cooldownEnd) {
        return Double.isFinite(now) && Double.isFinite(cooldownEnd) &&
            cooldownEnd <= now + ACTION_QUEUE_LEAD;
    }

    public static boolean shouldRetryMissingAcknowledgement(int consecutiveTimeouts,
                                                              double targetDistance) {
        return shouldRetryMissingAcknowledgement(consecutiveTimeouts, targetDistance, false);
    }

    public static boolean shouldRetryMissingAcknowledgement(int consecutiveTimeouts,
                                                              double targetDistance,
                                                              boolean distanceCapable) {
        return !canAttemptAction(targetDistance, distanceCapable) ||
            consecutiveTimeouts < MAX_CONSECUTIVE_ACK_TIMEOUTS;
    }
}
