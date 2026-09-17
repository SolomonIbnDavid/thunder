package thunder.combat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Persisted, UI-editable combat strategy model. */
public final class CombatPlan {
    public static final String ALL_COMBAT = "*";
    public static final String QUICK_BARRAGE = "paginae/atk/barrage";
    public static final String FULL_CIRCLE = "paginae/atk/fullcircle";

    public enum Metric {
        YOUR_IP("Your IP", false),
        OPPONENT_IP("Opponent IP", false),
        YOUR_GREEN("Your green opening", true),
        YOUR_YELLOW("Your yellow opening", true),
        YOUR_RED("Your red opening", true),
        YOUR_BLUE("Your blue opening", true),
        YOUR_MAX_OPENING("Your maximum opening", true),
        OPPONENT_GREEN("Opponent green opening", true),
        OPPONENT_YELLOW("Opponent yellow opening", true),
        OPPONENT_RED("Opponent red opening", true),
        OPPONENT_BLUE("Opponent blue opening", true),
        OPPONENT_MAX_OPENING("Opponent maximum opening", true),
        TARGET_ARMOR_DAMAGE("Target armor damage", false),
        TARGET_SHP_DAMAGE("Target SHP damage", false),
        TARGET_HHP_DAMAGE("Target HHP damage", false),
        TARGET_TOTAL_DAMAGE("Target total damage", false);

        public final String label;
        public final boolean percent;

        Metric(String label, boolean percent) {
            this.label = label;
            this.percent = percent;
        }

        @Override
        public String toString() {
            return(label);
        }
    }

    public enum Comparison {
        LT("<"), LE("<="), EQ("="), GE(">="), GT(">");

        public final String symbol;

        Comparison(String symbol) {
            this.symbol = symbol;
        }

        public boolean test(double actual, double expected) {
            switch(this) {
            case LT: return(actual < expected);
            case LE: return(actual <= expected);
            case EQ: return(Double.compare(actual, expected) == 0);
            case GE: return(actual >= expected);
            case GT: return(actual > expected);
            default: throw new AssertionError(this);
            }
        }

        @Override
        public String toString() {
            return(symbol);
        }
    }

    public enum ActionKind {
        MOVE("Exact combat move"),
        AUTO_CLEAR("Auto-clear worst opening");

        public final String label;

        ActionKind(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return(label);
        }
    }

    public static final class Condition {
        public Metric metric = Metric.OPPONENT_RED;
        public Comparison comparison = Comparison.LT;
        public double value = 55;

        public Condition() {
        }

        public Condition(Metric metric, Comparison comparison, double value) {
            this.metric = metric;
            this.comparison = comparison;
            this.value = value;
        }

        public Condition copy() {
            return(new Condition(metric, comparison, value));
        }
    }

    public static final class Action {
        public ActionKind kind = ActionKind.MOVE;
        public String resource = QUICK_BARRAGE;
        public int openingThreshold = 40;

        public static Action move(String resource) {
            Action action = new Action();
            action.kind = ActionKind.MOVE;
            action.resource = resource;
            return(action);
        }

        public static Action autoClear(int threshold) {
            Action action = new Action();
            action.kind = ActionKind.AUTO_CLEAR;
            action.resource = null;
            action.openingThreshold = threshold;
            return(action);
        }

        public Action copy() {
            Action copy = new Action();
            copy.kind = kind;
            copy.resource = resource;
            copy.openingThreshold = openingThreshold;
            return(copy);
        }
    }

    public static final class Rule {
        public String id = id();
        public String name = "Rule";
        public boolean enabled = true;
        public List<Condition> conditions = new ArrayList<>();
        public Action action = Action.move(QUICK_BARRAGE);

        public Rule copy() {
            Rule copy = new Rule();
            copy.id = id;
            copy.name = name;
            copy.enabled = enabled;
            copy.conditions = new ArrayList<>();
            if(conditions != null) {
                for(Condition condition : conditions)
                    copy.conditions.add(condition == null ? null : condition.copy());
            }
            copy.action = action == null ? null : action.copy();
            return(copy);
        }
    }

    public static final class Strategy {
        public String id = id();
        public String name = "Strategy";
        public List<Rule> rules = new ArrayList<>();

        public Strategy copy() {
            Strategy copy = new Strategy();
            copy.id = id;
            copy.name = name;
            copy.rules = new ArrayList<>();
            if(rules != null) {
                for(Rule rule : rules)
                    copy.rules.add(rule == null ? null : rule.copy());
            }
            return(copy);
        }
    }

    public static final class TargetProfile {
        public String targetKey;
        public String displayName;
        public String activeStrategyId;
        public List<Strategy> strategies = new ArrayList<>();

        public TargetProfile copy() {
            TargetProfile copy = new TargetProfile();
            copy.targetKey = targetKey;
            copy.displayName = displayName;
            copy.activeStrategyId = activeStrategyId;
            copy.strategies = new ArrayList<>();
            if(strategies != null) {
                for(Strategy strategy : strategies)
                    copy.strategies.add(strategy == null ? null : strategy.copy());
            }
            return(copy);
        }

        public Strategy activeStrategy() {
            if(strategies == null || strategies.isEmpty())
                return(null);
            for(Strategy strategy : strategies) {
                if(strategy != null && strategy.id != null && strategy.id.equals(activeStrategyId))
                    return(strategy);
            }
            return(null);
        }
    }

    public static final class CharacterPlans {
        public Map<String, TargetProfile> profiles = new LinkedHashMap<>();
        public Map<String, String> observedTargets = new LinkedHashMap<>();

        public CharacterPlans copy() {
            CharacterPlans copy = new CharacterPlans();
            if(profiles != null) {
                for(Map.Entry<String, TargetProfile> entry : profiles.entrySet())
                    copy.profiles.put(entry.getKey(), entry.getValue() == null ? null : entry.getValue().copy());
            }
            if(observedTargets != null)
                copy.observedTargets.putAll(observedTargets);
            return(copy);
        }

        public TargetProfile allCombat() {
            return(profiles == null ? null : profiles.get(ALL_COMBAT));
        }
    }

    public static final class Resolution {
        public final TargetProfile profile;
        public final Strategy strategy;
        public final boolean fallback;

        Resolution(TargetProfile profile, Strategy strategy, boolean fallback) {
            this.profile = profile;
            this.strategy = strategy;
            this.fallback = fallback;
        }
    }

    private CombatPlan() {
    }

    public static CharacterPlans defaults() {
        CharacterPlans plans = new CharacterPlans();
        TargetProfile all = new TargetProfile();
        all.targetKey = ALL_COMBAT;
        all.displayName = "All Combat";

        Strategy strategy = new Strategy();
        strategy.id = "default-all-combat";
        strategy.name = "Default";

        Rule clear = new Rule();
        clear.id = "default-clear-openings";
        clear.name = "Clear dangerous opening";
        clear.action = Action.autoClear(40);
        strategy.rules.add(clear);

        Rule barrage = new Rule();
        barrage.id = "default-build-red";
        barrage.name = "Build opponent red";
        barrage.conditions.add(new Condition(Metric.OPPONENT_RED, Comparison.LT, 55));
        barrage.action = Action.move(QUICK_BARRAGE);
        strategy.rules.add(barrage);

        Rule circle = new Rule();
        circle.id = "default-full-circle";
        circle.name = "Attack";
        circle.action = Action.move(FULL_CIRCLE);
        strategy.rules.add(circle);

        all.activeStrategyId = strategy.id;
        all.strategies.add(strategy);
        plans.profiles.put(ALL_COMBAT, all);
        return(plans);
    }

    public static Resolution resolve(CharacterPlans plans, String resource) {
        if(plans == null || plans.profiles == null)
            return(new Resolution(null, null, false));
        String target = CombatTargetCatalog.normalize(resource);
        TargetProfile selected = null;
        int selectedLength = -1;
        for(Map.Entry<String, TargetProfile> entry : plans.profiles.entrySet()) {
            String key = CombatTargetCatalog.normalize(entry.getKey());
            if(ALL_COMBAT.equals(entry.getKey()) || key.isEmpty())
                continue;
            if(target.startsWith(key) && key.length() > selectedLength) {
                selected = entry.getValue();
                selectedLength = key.length();
            }
        }
        Strategy strategy = selected == null ? null : selected.activeStrategy();
        if(strategy != null)
            return(new Resolution(selected, strategy, false));
        TargetProfile all = plans.profiles.get(ALL_COMBAT);
        return(new Resolution(all, all == null ? null : all.activeStrategy(), selected != null));
    }

    public static String id() {
        return(UUID.randomUUID().toString());
    }
}
