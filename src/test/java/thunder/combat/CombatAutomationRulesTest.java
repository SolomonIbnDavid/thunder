package thunder.combat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class CombatAutomationRulesTest {
    private static CombatAutomationRules.Snapshot state(int enemyRed,
                                                         int green, int yellow, int red, int blue) {
        return new CombatAutomationRules.Snapshot(true, true, true,
            green, yellow, red, blue, enemyRed);
    }

    @Test void waitsWithoutAnActiveCombat() {
        CombatAutomationRules.Snapshot state = new CombatAutomationRules.Snapshot(
            false, true, true, 0, 0, 0, 0, 0);
        assertEquals(CombatAutomationRules.Decision.WAIT, CombatAutomationRules.decide(state));
    }

    @Test void rejectsUnsupportedTargetsBeforeChoosingAMove() {
        CombatAutomationRules.Snapshot state = new CombatAutomationRules.Snapshot(
            true, false, true, 0, 0, 0, 0, 80);
        assertEquals(CombatAutomationRules.Decision.UNSUPPORTED_TARGET,
            CombatAutomationRules.decide(state));
    }

    @Test void waitsForTheServerReportedGlobalCooldown() {
        CombatAutomationRules.Snapshot state = new CombatAutomationRules.Snapshot(
            true, true, false, 0, 0, 0, 0, 0);
        assertEquals(CombatAutomationRules.Decision.WAIT, CombatAutomationRules.decide(state));
    }

    @Test void quickBarragesUntilEnemyRedReachesFiftyFive() {
        assertEquals(CombatAutomationRules.Decision.QUICK_BARRAGE,
            CombatAutomationRules.decide(state(54, 0, 0, 0, 0)));
        assertEquals(CombatAutomationRules.Decision.FULL_CIRCLE,
            CombatAutomationRules.decide(state(55, 0, 0, 0, 0)));
        assertEquals(CombatAutomationRules.Decision.FULL_CIRCLE,
            CombatAutomationRules.decide(state(65, 0, 0, 0, 0)));
    }

    @Test void returnsToQuickBarrageWhenEnemyRedFallsBelowTarget() {
        List<CombatAutomationRules.Decision> decisions = new ArrayList<>();
        for(int enemyRed : new int[] {35, 45, 55, 62, 54, 64})
            decisions.add(CombatAutomationRules.decide(state(enemyRed, 0, 0, 0, 0)));
        assertEquals(Arrays.asList(
            CombatAutomationRules.Decision.QUICK_BARRAGE,
            CombatAutomationRules.Decision.QUICK_BARRAGE,
            CombatAutomationRules.Decision.FULL_CIRCLE,
            CombatAutomationRules.Decision.FULL_CIRCLE,
            CombatAutomationRules.Decision.QUICK_BARRAGE,
            CombatAutomationRules.Decision.FULL_CIRCLE), decisions);
    }

    @Test void restoresAtTheFortyPercentBoundaryBeforeAttacking() {
        assertEquals(CombatAutomationRules.Decision.FULL_CIRCLE,
            CombatAutomationRules.decide(state(60, 39, 39, 39, 39)));
        assertEquals(CombatAutomationRules.Decision.RESTORE_GREEN,
            CombatAutomationRules.decide(state(60, 40, 0, 0, 0)));
        assertEquals(CombatAutomationRules.Decision.RESTORE_YELLOW,
            CombatAutomationRules.decide(state(20, 0, 40, 0, 0)));
        assertEquals(CombatAutomationRules.Decision.RESTORE_RED,
            CombatAutomationRules.decide(state(20, 0, 0, 40, 0)));
        assertEquals(CombatAutomationRules.Decision.RESTORE_BLUE,
            CombatAutomationRules.decide(state(20, 0, 0, 0, 40)));
    }

    @Test void restoresTheLargestOpeningAndPrefersRedOnTies() {
        assertEquals(CombatAutomationRules.Decision.RESTORE_BLUE,
            CombatAutomationRules.decide(state(70, 41, 42, 55, 61)));
        assertEquals(CombatAutomationRules.Decision.RESTORE_RED,
            CombatAutomationRules.decide(state(70, 60, 60, 60, 60)));
    }

    @Test void skipsAnUnsafeColorThatHasNoMatchingMoveOnTheDeck() {
        CombatAutomationRules.Snapshot state = new CombatAutomationRules.Snapshot(
            true, true, true, 68, 0, 0, 40, 60,
            EnumSet.of(CombatAutomationRules.Opening.BLUE));
        assertEquals(CombatAutomationRules.Decision.RESTORE_BLUE,
            CombatAutomationRules.decide(state));
    }

    @Test void queuesActionsDuringTheLastFourHundredMillisecondsOfCooldown() {
        assertFalse(CombatAutomationRules.cooldownReadyToQueue(10.0, 10.401));
        assertTrue(CombatAutomationRules.cooldownReadyToQueue(10.0, 10.400));
        assertTrue(CombatAutomationRules.cooldownReadyToQueue(10.0, 9.0));
        assertFalse(CombatAutomationRules.cooldownReadyToQueue(Double.NaN, 10.0));
        assertFalse(CombatAutomationRules.cooldownReadyToQueue(10.0, Double.NaN));
    }

    @Test void continuesOffenseWhenNoUnsafeColorCanBeCleared() {
        CombatAutomationRules.Snapshot finish = new CombatAutomationRules.Snapshot(
            true, true, true, 68, 0, 0, 0, 60, Collections.emptySet());
        CombatAutomationRules.Snapshot build = new CombatAutomationRules.Snapshot(
            true, true, true, 68, 0, 0, 0, 40, Collections.emptySet());
        assertEquals(CombatAutomationRules.Decision.FULL_CIRCLE,
            CombatAutomationRules.decide(finish));
        assertEquals(CombatAutomationRules.Decision.QUICK_BARRAGE,
            CombatAutomationRules.decide(build));
    }

    @Test void waitsUntilTheTargetIsInsideMeleeActionRange() {
        assertTrue(CombatAutomationRules.canAttemptAction(0));
        assertTrue(CombatAutomationRules.canAttemptAction(55));
        assertFalse(CombatAutomationRules.canAttemptAction(55.01));
        assertFalse(CombatAutomationRules.canAttemptAction(Double.NaN));
        assertFalse(CombatAutomationRules.canAttemptAction(Double.POSITIVE_INFINITY));
    }

    @Test void retriesTransientOrOutOfRangeAcknowledgementTimeouts() {
        assertTrue(CombatAutomationRules.shouldRetryMissingAcknowledgement(1, 30));
        assertTrue(CombatAutomationRules.shouldRetryMissingAcknowledgement(2, 30));
        assertFalse(CombatAutomationRules.shouldRetryMissingAcknowledgement(3, 30));
        assertTrue(CombatAutomationRules.shouldRetryMissingAcknowledgement(3, 80));
    }
}
