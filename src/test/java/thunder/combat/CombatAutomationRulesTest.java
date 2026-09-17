package thunder.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CombatAutomationRulesTest {
    @Test void queuesActionsDuringTheLastFourHundredMillisecondsOfCooldown() {
        assertFalse(CombatAutomationRules.cooldownReadyToQueue(10.0, 10.401));
        assertTrue(CombatAutomationRules.cooldownReadyToQueue(10.0, 10.400));
        assertTrue(CombatAutomationRules.cooldownReadyToQueue(10.0, 9.0));
        assertFalse(CombatAutomationRules.cooldownReadyToQueue(Double.NaN, 10.0));
        assertFalse(CombatAutomationRules.cooldownReadyToQueue(10.0, Double.NaN));
    }

    @Test void meleeKeepsTheSafetyGateButKnownDistanceMovesDoNot() {
        assertTrue(CombatAutomationRules.canAttemptAction(55, false));
        assertFalse(CombatAutomationRules.canAttemptAction(55.01, false));
        assertTrue(CombatAutomationRules.canAttemptAction(500, true));
        assertFalse(CombatAutomationRules.canAttemptAction(Double.NaN, true));
        assertFalse(CombatAutomationRules.canAttemptAction(-1, true));
    }

    @Test void retriesTransientAndOutOfRangeAcknowledgementTimeouts() {
        assertTrue(CombatAutomationRules.shouldRetryMissingAcknowledgement(1, 30, false));
        assertTrue(CombatAutomationRules.shouldRetryMissingAcknowledgement(2, 30, false));
        assertFalse(CombatAutomationRules.shouldRetryMissingAcknowledgement(3, 30, false));
        assertTrue(CombatAutomationRules.shouldRetryMissingAcknowledgement(3, 80, false));
        assertFalse(CombatAutomationRules.shouldRetryMissingAcknowledgement(3, 80, true));
    }
}
