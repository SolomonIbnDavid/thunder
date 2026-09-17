package thunder.combat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure ordered-rule evaluator, including deck and cooldown selection. */
public final class CombatPlanEvaluator {
    public enum Outcome {
        READY,
        WAIT,
        MISSING_MOVE,
        NO_MATCH
    }

    public static final class State {
        public int yourIp;
        public int opponentIp;
        public int yourGreen;
        public int yourYellow;
        public int yourRed;
        public int yourBlue;
        public int opponentGreen;
        public int opponentYellow;
        public int opponentRed;
        public int opponentBlue;
        public long armorDamage;
        public long shpDamage;
        public long hhpDamage;

        public int yourMaxOpening() {
            return(max(yourGreen, yourYellow, yourRed, yourBlue));
        }

        public int opponentMaxOpening() {
            return(max(opponentGreen, opponentYellow, opponentRed, opponentBlue));
        }

        public long totalDamage() {
            return(armorDamage + shpDamage + hhpDamage);
        }

        public int opening(CombatAutomationRules.Opening opening) {
            switch(opening) {
            case GREEN: return(yourGreen);
            case YELLOW: return(yourYellow);
            case RED: return(yourRed);
            case BLUE: return(yourBlue);
            default: throw new AssertionError(opening);
            }
        }

        public double metric(CombatPlan.Metric metric) {
            switch(metric) {
            case YOUR_IP: return(yourIp);
            case OPPONENT_IP: return(opponentIp);
            case YOUR_GREEN: return(yourGreen);
            case YOUR_YELLOW: return(yourYellow);
            case YOUR_RED: return(yourRed);
            case YOUR_BLUE: return(yourBlue);
            case YOUR_MAX_OPENING: return(yourMaxOpening());
            case OPPONENT_GREEN: return(opponentGreen);
            case OPPONENT_YELLOW: return(opponentYellow);
            case OPPONENT_RED: return(opponentRed);
            case OPPONENT_BLUE: return(opponentBlue);
            case OPPONENT_MAX_OPENING: return(opponentMaxOpening());
            case TARGET_ARMOR_DAMAGE: return(armorDamage);
            case TARGET_SHP_DAMAGE: return(shpDamage);
            case TARGET_HHP_DAMAGE: return(hhpDamage);
            case TARGET_TOTAL_DAMAGE: return(totalDamage());
            default: throw new AssertionError(metric);
            }
        }

        private static int max(int... values) {
            int result = Integer.MIN_VALUE;
            for(int value : values)
                result = Math.max(result, value);
            return(result);
        }
    }

    public static final class DeckMove {
        public final String resource;
        public final String name;
        public final int slot;
        public final double cooldownEnd;
        public final Set<CombatAutomationRules.Opening> clears;

        public DeckMove(String resource, String name, int slot, double cooldownEnd,
                        Set<CombatAutomationRules.Opening> clears) {
            this.resource = resource;
            this.name = name;
            this.slot = slot;
            this.cooldownEnd = cooldownEnd;
            this.clears = clears == null ? Collections.emptySet() : clears;
        }
    }

    public static final class Result {
        public final Outcome outcome;
        public final CombatPlan.Rule rule;
        public final DeckMove move;
        public final CombatAutomationRules.Opening opening;
        public final String detail;

        private Result(Outcome outcome, CombatPlan.Rule rule, DeckMove move,
                       CombatAutomationRules.Opening opening, String detail) {
            this.outcome = outcome;
            this.rule = rule;
            this.move = move;
            this.opening = opening;
            this.detail = detail;
        }
    }

    private CombatPlanEvaluator() {
    }

    public static Result evaluate(CombatPlan.Strategy strategy, State state,
                                  Collection<DeckMove> deck, double now, double globalCooldownEnd) {
        if(strategy == null || state == null || strategy.rules == null)
            return(result(Outcome.NO_MATCH, null, null, null, "No active strategy"));
        List<DeckMove> moves = deck == null ? Collections.emptyList() : new ArrayList<>(deck);
        for(CombatPlan.Rule rule : strategy.rules) {
            if(rule == null || !rule.enabled || !matches(rule, state))
                continue;
            if(rule.action == null)
                return(result(Outcome.MISSING_MOVE, rule, null, null, "Rule has no action"));
            if(rule.action.kind == CombatPlan.ActionKind.AUTO_CLEAR) {
                Result clear = selectAutoClear(rule, state, moves, now, globalCooldownEnd);
                if(clear != null)
                    return(clear);
                continue;
            }
            DeckMove exact = find(moves, rule.action.resource);
            if(exact == null)
                return(result(Outcome.MISSING_MOVE, rule, null, null,
                    "Missing " + CombatMoveCatalog.label(rule.action.resource)));
            if(!ready(now, globalCooldownEnd) || !ready(now, exact.cooldownEnd))
                return(result(Outcome.WAIT, rule, exact, null, "Move cooldown"));
            return(result(Outcome.READY, rule, exact, null, "Ready"));
        }
        return(result(Outcome.NO_MATCH, null, null, null, "No rule matches"));
    }

    public static boolean matches(CombatPlan.Rule rule, State state) {
        if(rule == null || state == null || rule.conditions == null)
            return(false);
        for(CombatPlan.Condition condition : rule.conditions) {
            if(condition == null || condition.metric == null || condition.comparison == null ||
               !condition.comparison.test(state.metric(condition.metric), condition.value))
                return(false);
        }
        return(true);
    }

    private static Result selectAutoClear(CombatPlan.Rule rule, State state, List<DeckMove> deck,
                                          double now, double globalCooldownEnd) {
        Map<CombatAutomationRules.Opening, List<DeckMove>> candidates =
            new EnumMap<>(CombatAutomationRules.Opening.class);
        for(DeckMove move : deck) {
            for(CombatAutomationRules.Opening opening : move.clears)
                candidates.computeIfAbsent(opening, ignored -> new ArrayList<>()).add(move);
        }

        CombatAutomationRules.Opening selected = null;
        int selectedValue = rule.action.openingThreshold - 1;
        CombatAutomationRules.Opening[] preference = {
            CombatAutomationRules.Opening.RED,
            CombatAutomationRules.Opening.YELLOW,
            CombatAutomationRules.Opening.BLUE,
            CombatAutomationRules.Opening.GREEN
        };
        for(CombatAutomationRules.Opening opening : preference) {
            int value = state.opening(opening);
            if(value > selectedValue && candidates.containsKey(opening) && !candidates.get(opening).isEmpty()) {
                selected = opening;
                selectedValue = value;
            }
        }
        if(selected == null)
            return(null);

        List<DeckMove> clearing = candidates.get(selected);
        Collections.sort(clearing, Comparator
            .comparing((DeckMove move) -> !ready(now, move.cooldownEnd))
            .thenComparingDouble(move -> move.cooldownEnd)
            .thenComparingInt(move -> move.slot));
        DeckMove move = clearing.get(0);
        if(!ready(now, globalCooldownEnd) || !ready(now, move.cooldownEnd))
            return(result(Outcome.WAIT, rule, move, selected, "Restoration cooldown"));
        return(result(Outcome.READY, rule, move, selected, "Ready"));
    }

    private static DeckMove find(List<DeckMove> deck, String resource) {
        DeckMove first = null;
        for(DeckMove move : deck) {
            if(resource != null && resource.equals(move.resource)) {
                if(first == null || move.cooldownEnd < first.cooldownEnd)
                    first = move;
            }
        }
        return(first);
    }

    private static boolean ready(double now, double cooldownEnd) {
        return(CombatAutomationRules.cooldownReadyToQueue(now, cooldownEnd));
    }

    private static Result result(Outcome outcome, CombatPlan.Rule rule, DeckMove move,
                                 CombatAutomationRules.Opening opening, String detail) {
        return(new Result(outcome, rule, move, opening, detail));
    }
}
