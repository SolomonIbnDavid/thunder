package thunder.combat;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

public class CombatPlanEvaluatorTest {
    private static final double NOW = 10.0;

    @Test void evaluatesRulesTopToBottomAndAndsEveryCondition() {
        CombatPlan.Rule first = rule("First", "paginae/atk/cleave",
            condition(CombatPlan.Metric.YOUR_IP, CombatPlan.Comparison.GE, 6),
            condition(CombatPlan.Metric.OPPONENT_RED, CombatPlan.Comparison.GE, 55));
        CombatPlan.Rule second = rule("Second", CombatPlan.FULL_CIRCLE);
        CombatPlan.Strategy strategy = strategy(first, second);
        CombatPlanEvaluator.State state = state();
        state.yourIp = 6;
        state.opponentRed = 55;
        CombatPlanEvaluator.Result result = evaluate(strategy, state,
            move("paginae/atk/cleave", NOW), move(CombatPlan.FULL_CIRCLE, NOW));
        assertEquals(CombatPlanEvaluator.Outcome.READY, result.outcome);
        assertSame(first, result.rule);

        state.yourIp = 5;
        result = evaluate(strategy, state,
            move("paginae/atk/cleave", NOW), move(CombatPlan.FULL_CIRCLE, NOW));
        assertSame(second, result.rule);
    }

    @Test void supportsEveryMetricAndOperator() {
        CombatPlanEvaluator.State state = state();
        state.yourIp = state.opponentIp = 5;
        state.yourGreen = state.yourYellow = state.yourRed = state.yourBlue = 5;
        state.opponentGreen = state.opponentYellow = state.opponentRed = state.opponentBlue = 5;
        state.armorDamage = state.shpDamage = state.hhpDamage = 5;
        for(CombatPlan.Metric metric : CombatPlan.Metric.values()) {
            double expected = metric == CombatPlan.Metric.TARGET_TOTAL_DAMAGE ? 15 : 5;
            CombatPlan.Rule rule = rule("metric", CombatPlan.FULL_CIRCLE,
                condition(metric, CombatPlan.Comparison.EQ, expected));
            assertEquals(true, CombatPlanEvaluator.matches(rule, state), metric.name());
        }
        assertEquals(true, matches(state, CombatPlan.Comparison.LT, 6));
        assertEquals(true, matches(state, CombatPlan.Comparison.LE, 5));
        assertEquals(true, matches(state, CombatPlan.Comparison.EQ, 5));
        assertEquals(true, matches(state, CombatPlan.Comparison.GE, 5));
        assertEquals(true, matches(state, CombatPlan.Comparison.GT, 4));
    }

    @Test void noMatchingRuleWaitsWithoutSelectingAMove() {
        CombatPlan.Strategy strategy = strategy(rule("Later", CombatPlan.FULL_CIRCLE,
            condition(CombatPlan.Metric.YOUR_IP, CombatPlan.Comparison.GT, 10)));
        CombatPlanEvaluator.Result result = evaluate(strategy, state(), move(CombatPlan.FULL_CIRCLE, NOW));
        assertEquals(CombatPlanEvaluator.Outcome.NO_MATCH, result.outcome);
        assertNull(result.move);
    }

    @Test void missingExactMovePausesInsteadOfFallingThrough() {
        CombatPlan.Rule missing = rule("Kill", "paginae/atk/cleave");
        CombatPlan.Rule fallback = rule("Fallback", CombatPlan.FULL_CIRCLE);
        CombatPlanEvaluator.Result result = evaluate(strategy(missing, fallback), state(),
            move(CombatPlan.FULL_CIRCLE, NOW));
        assertEquals(CombatPlanEvaluator.Outcome.MISSING_MOVE, result.outcome);
        assertSame(missing, result.rule);
    }

    @Test void exactMoveAndGlobalCooldownsBlockTheMatchedRule() {
        CombatPlan.Strategy strategy = strategy(rule("Attack", CombatPlan.FULL_CIRCLE));
        CombatPlanEvaluator.Result moveCooldown = CombatPlanEvaluator.evaluate(strategy, state(),
            Collections.singletonList(move(CombatPlan.FULL_CIRCLE, NOW + 0.401)), NOW, NOW);
        assertEquals(CombatPlanEvaluator.Outcome.WAIT, moveCooldown.outcome);
        CombatPlanEvaluator.Result globalCooldown = CombatPlanEvaluator.evaluate(strategy, state(),
            Collections.singletonList(move(CombatPlan.FULL_CIRCLE, NOW)), NOW, NOW + 0.401);
        assertEquals(CombatPlanEvaluator.Outcome.WAIT, globalCooldown.outcome);
    }

    @Test void fourHundredMillisecondQueueBoundaryIsInclusive() {
        CombatPlan.Strategy strategy = strategy(rule("Attack", CombatPlan.FULL_CIRCLE));
        CombatPlanEvaluator.Result result = CombatPlanEvaluator.evaluate(strategy, state(),
            Collections.singletonList(move(CombatPlan.FULL_CIRCLE, NOW + 0.400)), NOW, NOW + 0.400);
        assertEquals(CombatPlanEvaluator.Outcome.READY, result.outcome);
    }

    @Test void autoClearUsesHighestClearableOpeningAndPrefersAReadyMove() {
        CombatPlan.Rule clear = new CombatPlan.Rule();
        clear.name = "Clear";
        clear.action = CombatPlan.Action.autoClear(40);
        CombatPlanEvaluator.State state = state();
        state.yourGreen = 90; // No green restoration is equipped.
        state.yourBlue = 70;
        state.yourRed = 60;
        CombatPlanEvaluator.DeckMove slowBlue = move("blue-slow", NOW + 0.5,
            CombatAutomationRules.Opening.BLUE);
        CombatPlanEvaluator.DeckMove readyBlue = move("blue-ready", NOW + 0.3,
            CombatAutomationRules.Opening.BLUE);
        CombatPlanEvaluator.DeckMove red = move("red", NOW,
            CombatAutomationRules.Opening.RED);
        CombatPlanEvaluator.Result result = evaluate(strategy(clear), state, slowBlue, readyBlue, red);
        assertEquals(CombatPlanEvaluator.Outcome.READY, result.outcome);
        assertEquals(CombatAutomationRules.Opening.BLUE, result.opening);
        assertEquals("blue-ready", result.move.resource);
    }

    @Test void missingRestorationFallsThroughToOffense() {
        CombatPlan.Rule clear = new CombatPlan.Rule();
        clear.name = "Clear";
        clear.action = CombatPlan.Action.autoClear(40);
        CombatPlan.Rule offense = rule("Offense", CombatPlan.FULL_CIRCLE);
        CombatPlanEvaluator.State state = state();
        state.yourGreen = 75;
        CombatPlanEvaluator.Result result = evaluate(strategy(clear, offense), state,
            move(CombatPlan.FULL_CIRCLE, NOW));
        assertEquals(CombatPlanEvaluator.Outcome.READY, result.outcome);
        assertSame(offense, result.rule);
    }

    @Test void seededPlanPreservesFortyDefenseAndFiftyFiveRedBehavior() {
        CombatPlan.Strategy strategy = CombatPlan.defaults().allCombat().activeStrategy();
        CombatPlanEvaluator.State state = state();
        state.opponentRed = 54;
        CombatPlanEvaluator.Result build = evaluate(strategy, state,
            move(CombatPlan.QUICK_BARRAGE, NOW), move(CombatPlan.FULL_CIRCLE, NOW));
        assertEquals(CombatPlan.QUICK_BARRAGE, build.move.resource);

        state.opponentRed = 55;
        CombatPlanEvaluator.Result attack = evaluate(strategy, state,
            move(CombatPlan.QUICK_BARRAGE, NOW), move(CombatPlan.FULL_CIRCLE, NOW));
        assertEquals(CombatPlan.FULL_CIRCLE, attack.move.resource);

        state.yourYellow = 40;
        CombatPlanEvaluator.Result clear = evaluate(strategy, state,
            move(CombatPlan.QUICK_BARRAGE, NOW), move(CombatPlan.FULL_CIRCLE, NOW),
            move("paginae/atk/jump", NOW, CombatAutomationRules.Opening.YELLOW));
        assertEquals("paginae/atk/jump", clear.move.resource);
    }

    @Test void quickBarrageFullCircleAndDamageIpGatedCleaveScenario() {
        CombatPlan.Rule cleave = rule("Cleave", "paginae/atk/cleave",
            condition(CombatPlan.Metric.TARGET_TOTAL_DAMAGE, CombatPlan.Comparison.GE, 600),
            condition(CombatPlan.Metric.YOUR_IP, CombatPlan.Comparison.GE, 6));
        CombatPlan.Rule barrage = rule("Build", CombatPlan.QUICK_BARRAGE,
            condition(CombatPlan.Metric.OPPONENT_RED, CombatPlan.Comparison.LT, 55));
        CombatPlan.Rule circle = rule("Damage", CombatPlan.FULL_CIRCLE);
        CombatPlan.Strategy strategy = strategy(cleave, barrage, circle);
        CombatPlanEvaluator.DeckMove[] deck = {
            move("paginae/atk/cleave", NOW), move(CombatPlan.QUICK_BARRAGE, NOW),
            move(CombatPlan.FULL_CIRCLE, NOW)
        };
        CombatPlanEvaluator.State state = state();
        state.opponentRed = 20;
        assertEquals(CombatPlan.QUICK_BARRAGE, evaluate(strategy, state, deck).move.resource);
        state.opponentRed = 60;
        state.armorDamage = 300;
        assertEquals(CombatPlan.FULL_CIRCLE, evaluate(strategy, state, deck).move.resource);
        state.armorDamage = 500;
        state.shpDamage = 100;
        state.yourIp = 6;
        assertEquals("paginae/atk/cleave", evaluate(strategy, state, deck).move.resource);
    }

    private static boolean matches(CombatPlanEvaluator.State state, CombatPlan.Comparison comparison,
                                   double value) {
        return CombatPlanEvaluator.matches(rule("operator", CombatPlan.FULL_CIRCLE,
            condition(CombatPlan.Metric.YOUR_IP, comparison, value)), state);
    }

    private static CombatPlanEvaluator.Result evaluate(CombatPlan.Strategy strategy,
                                                       CombatPlanEvaluator.State state,
                                                       CombatPlanEvaluator.DeckMove... deck) {
        return CombatPlanEvaluator.evaluate(strategy, state, Arrays.asList(deck), NOW, NOW);
    }

    private static CombatPlanEvaluator.State state() {
        return new CombatPlanEvaluator.State();
    }

    private static CombatPlan.Strategy strategy(CombatPlan.Rule... rules) {
        CombatPlan.Strategy strategy = new CombatPlan.Strategy();
        strategy.name = "Test";
        strategy.rules.addAll(Arrays.asList(rules));
        return strategy;
    }

    private static CombatPlan.Rule rule(String name, String resource, CombatPlan.Condition... conditions) {
        CombatPlan.Rule rule = new CombatPlan.Rule();
        rule.name = name;
        rule.action = CombatPlan.Action.move(resource);
        rule.conditions.addAll(Arrays.asList(conditions));
        return rule;
    }

    private static CombatPlan.Condition condition(CombatPlan.Metric metric,
                                                  CombatPlan.Comparison comparison, double value) {
        return new CombatPlan.Condition(metric, comparison, value);
    }

    private static CombatPlanEvaluator.DeckMove move(String resource, double cooldown,
                                                     CombatAutomationRules.Opening... clears) {
        return new CombatPlanEvaluator.DeckMove(resource, resource, 0, cooldown,
            clears.length == 0 ? Collections.emptySet() : EnumSet.copyOf(Arrays.asList(clears)));
    }
}
