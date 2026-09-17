package thunder.combat;

import haven.Button;
import haven.CheckBox;
import haven.Coord;
import haven.Dropbox;
import haven.GOut;
import haven.GameUI;
import haven.Label;
import haven.Listbox;
import haven.TextEntry;
import haven.UI;
import haven.Widget;
import haven.WindowX;

import java.awt.Color;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Shift-click combat-plan editor. Normal-click toggling remains in CombatAutomation. */
public final class CombatAutomationWnd extends WindowX {
    private static final int WIDTH = 720;
    private static final int ROW = UI.scale(20);
    private static CombatAutomationWnd instance;

    private final GameUI gui;
    private final CombatPlanStore store = CombatPlanStore.get();
    private CombatPlan.CharacterPlans working;
    private String targetKey = CombatPlan.ALL_COMBAT;
    private String strategyId;
    private boolean dirty;
    private boolean rebuildEditor;
    private String deckSignature = "";

    private final ChoiceDrop<TargetOption> targets;
    private final ChoiceDrop<CombatPlan.Strategy> strategies;
    private final TextEntry strategyName;
    private final RuleList rules;
    private final Widget editorHost;
    private final StatusLabel validation;
    private final StatusLabel runtime;
    private final Button toggle;
    private CombatPlan.Rule editorRule;

    private CombatAutomationWnd(GameUI gui) {
        super(Coord.z, "Combat Automation Planner");
        justclose = false;
        this.gui = gui;
        this.working = store.character(gui.chrid);

        int y = 0;
        add(new Label("Target profile:"), 0, y + UI.scale(3));
        targets = add(new ChoiceDrop<>(UI.scale(300), 14, ROW, option -> option.label), UI.scale(95), y);
        add(new Button(UI.scale(125), "Use current target", this::selectCurrentTarget), UI.scale(405), y);
        y += ROW + UI.scale(5);

        add(new Label("Strategy:"), 0, y + UI.scale(3));
        strategies = add(new ChoiceDrop<>(UI.scale(240), 10, ROW, this::strategyLabel), UI.scale(95), y);
        strategyName = add(new TextEntry(UI.scale(210), ""), UI.scale(345), y);
        y += ROW + UI.scale(4);

        add(new Button(UI.scale(58), "New", this::newStrategy), 0, y);
        add(new Button(UI.scale(58), "Clone", this::cloneStrategy), UI.scale(63), y);
        add(new Button(UI.scale(68), "Rename", this::renameStrategy), UI.scale(126), y);
        add(new Button(UI.scale(58), "Delete", this::deleteStrategy), UI.scale(199), y);
        add(new Button(UI.scale(82), "Set Active", this::setActiveStrategy), UI.scale(262), y);
        add(new Label("Name field is used by New, Clone, and Rename."), UI.scale(355), y + UI.scale(3));
        y += UI.scale(27);

        rules = add(new RuleList(UI.scale(WIDTH), 8), 0, y);
        y += rules.sz.y + UI.scale(4);
        add(new Button(UI.scale(58), "Add", this::addRule), 0, y);
        add(new Button(UI.scale(72), "Duplicate", this::duplicateRule), UI.scale(63), y);
        add(new Button(UI.scale(45), "Up", () -> moveRule(-1)), UI.scale(140), y);
        add(new Button(UI.scale(52), "Down", () -> moveRule(1)), UI.scale(190), y);
        add(new Button(UI.scale(62), "Enable", this::toggleRule), UI.scale(247), y);
        add(new Button(UI.scale(58), "Delete", this::deleteRule), UI.scale(314), y);
        y += UI.scale(28);

        editorHost = add(new Widget(Coord.of(UI.scale(WIDTH), UI.scale(265))), 0, y);
        y += editorHost.sz.y + UI.scale(4);

        validation = add(new StatusLabel("", UI.scale(WIDTH)), 0, y);
        y += UI.scale(35);
        runtime = add(new StatusLabel("", UI.scale(WIDTH)), 0, y);
        y += UI.scale(35);

        toggle = add(new Button(UI.scale(120), toggleText(), () -> {
            CombatAutomation.toggle(gui);
            updateRuntime();
        }), 0, y);
        add(new Button(UI.scale(90), "Save", this::save), UI.scale(WIDTH - 190), y);
        add(new Button(UI.scale(90), "Revert", this::revert), UI.scale(WIDTH - 95), y);

        targets.onChange = option -> {
            if(option == null)
                return;
            targetKey = option.key;
            rebuildStrategies(null);
        };
        strategies.onChange = strategy -> {
            strategyId = strategy == null ? null : strategy.id;
            strategyName.settext(strategy == null ? "" : strategy.name);
            refreshRules(null);
        };
        rules.onChange = rule -> {
            editorRule = rule;
            rebuildEditor = true;
        };

        rebuildTargets();
        rebuildStrategies(null);
        updateValidation("Unsaved changes are edited locally. Save applies them on the next action cycle.");
        updateRuntime();
        rebuildRuleEditor();
        pack();
    }

    @Override
    public void tick(double dt) {
        super.tick(dt);
        updateRuntime();
        String signature = deckSignature();
        if(!signature.equals(deckSignature)) {
            deckSignature = signature;
            rebuildEditor = true;
        }
        if(rebuildEditor)
            rebuildRuleEditor();
    }

    private void rebuildTargets() {
        String current = profileKey(CombatAutomation.currentTargetResource(gui));
        Map<String, TargetOption> merged = new LinkedHashMap<>();
        merged.put(CombatPlan.ALL_COMBAT, new TargetOption(CombatPlan.ALL_COMBAT, "All Combat"));
        for(CombatTargetCatalog.Entry entry : CombatTargetCatalog.entries())
            merged.put(entry.key, new TargetOption(entry.key, entry.label));
        if(working.profiles != null) {
            for(CombatPlan.TargetProfile profile : working.profiles.values()) {
                if(profile != null && !CombatPlan.ALL_COMBAT.equals(profile.targetKey))
                    merged.put(profile.targetKey, new TargetOption(profile.targetKey, profile.displayName));
            }
        }
        if(working.observedTargets != null) {
            for(Map.Entry<String, String> entry : working.observedTargets.entrySet())
                merged.put(entry.getKey(), new TargetOption(entry.getKey(), entry.getValue()));
        }
        if(!current.isEmpty())
            merged.put(current, new TargetOption(current, CombatTargetCatalog.labelFor(current)));
        List<TargetOption> options = new ArrayList<>(merged.values());
        TargetOption all = options.remove(0);
        options.sort(Comparator.comparing(option -> option.label));
        options.add(0, all);
        targets.setItems(options);
        TargetOption selected = findTarget(targetKey);
        if(selected == null) {
            targetKey = CombatPlan.ALL_COMBAT;
            selected = findTarget(targetKey);
        }
        targets.sel = selected;
    }

    private void selectCurrentTarget() {
        String current = profileKey(CombatAutomation.currentTargetResource(gui));
        if(current.isEmpty()) {
            updateValidation("No current combat target is available.");
            return;
        }
        if(working.observedTargets == null)
            working.observedTargets = new LinkedHashMap<>();
        if(CombatTargetCatalog.knownMatch(current) == null)
            working.observedTargets.put(current, CombatTargetCatalog.labelFor(current));
        targetKey = current;
        dirty = true;
        rebuildTargets();
        rebuildStrategies(null);
        updateValidation("Selected current target " + CombatTargetCatalog.labelFor(current) + ".");
    }

    private void rebuildStrategies(String preferredId) {
        CombatPlan.TargetProfile shown = shownProfile();
        List<CombatPlan.Strategy> items = shown == null || shown.strategies == null ?
            new ArrayList<>() : shown.strategies;
        strategies.setItems(items);
        String wanted = preferredId != null ? preferredId : strategyId;
        CombatPlan.Strategy selected = findStrategy(items, wanted);
        if(selected == null && shown != null)
            selected = findStrategy(items, shown.activeStrategyId);
        if(selected == null && !items.isEmpty())
            selected = items.get(0);
        strategyId = selected == null ? null : selected.id;
        strategies.sel = selected;
        strategyName.settext(selected == null ? "" : selected.name);
        refreshRules(null);
    }

    private void refreshRules(String preferredRuleId) {
        CombatPlan.Strategy strategy = selectedStrategy();
        rules.setItems(strategy == null || strategy.rules == null ? new ArrayList<>() : strategy.rules);
        CombatPlan.Rule selected = findRule(rules.items, preferredRuleId);
        if(selected == null && rules.sel != null)
            selected = findRule(rules.items, rules.sel.id);
        if(selected == null && !rules.items.isEmpty())
            selected = rules.items.get(0);
        rules.sel = selected;
        editorRule = selected;
        rebuildEditor = true;
    }

    private void newStrategy() {
        CombatPlan.TargetProfile profile = ensureProfile();
        String name = uniqueStrategyName(profile, requestedName("New Strategy"));
        CombatPlan.Strategy strategy = new CombatPlan.Strategy();
        strategy.name = name;
        profile.strategies.add(strategy);
        profile.activeStrategyId = strategy.id;
        changed("Created strategy " + name + ".", strategy.id, null);
    }

    private void cloneStrategy() {
        CombatPlan.Strategy source = selectedStrategy();
        if(source == null) {
            updateValidation("Select a strategy to clone.");
            return;
        }
        CombatPlan.TargetProfile profile = ensureProfile();
        CombatPlan.Strategy clone = source.copy();
        clone.id = CombatPlan.id();
        clone.name = uniqueStrategyName(profile, requestedName(source.name + " Copy"));
        for(CombatPlan.Rule rule : clone.rules)
            rule.id = CombatPlan.id();
        profile.strategies.add(clone);
        profile.activeStrategyId = clone.id;
        changed("Cloned strategy as " + clone.name + ".", clone.id, null);
    }

    private void renameStrategy() {
        CombatPlan.Strategy strategy = editableSelectedStrategy();
        CombatPlan.TargetProfile profile = explicitProfile();
        if(strategy == null || profile == null) {
            updateValidation("Select a strategy to rename.");
            return;
        }
        String requested = requestedName(strategy.name);
        for(CombatPlan.Strategy other : profile.strategies) {
            if(other != strategy && other.name != null && other.name.equalsIgnoreCase(requested)) {
                updateValidation("Strategy names must be unique for a target.");
                return;
            }
        }
        strategy.name = requested;
        changed("Renamed strategy.", strategy.id, rules.sel == null ? null : rules.sel.id);
    }

    private void deleteStrategy() {
        CombatPlan.TargetProfile profile = explicitProfile();
        if(profile == null) {
            updateValidation("This target currently inherits All Combat; there is no local strategy to delete.");
            return;
        }
        CombatPlan.Strategy selected = findStrategy(profile.strategies, strategyId);
        if(selected == null)
            return;
        if(CombatPlan.ALL_COMBAT.equals(targetKey) && profile.strategies.size() == 1) {
            updateValidation("All Combat must keep at least one strategy.");
            return;
        }
        profile.strategies.remove(selected);
        if(profile.strategies.isEmpty()) {
            working.profiles.remove(targetKey);
            strategyId = null;
        } else if(Objects.equals(profile.activeStrategyId, selected.id)) {
            profile.activeStrategyId = profile.strategies.get(0).id;
            strategyId = profile.activeStrategyId;
        }
        changed("Deleted strategy " + selected.name + ".", strategyId, null);
    }

    private void setActiveStrategy() {
        CombatPlan.Strategy strategy = editableSelectedStrategy();
        CombatPlan.TargetProfile profile = explicitProfile();
        if(strategy == null || profile == null)
            return;
        profile.activeStrategyId = strategy.id;
        changed("Active strategy set to " + strategy.name + ".", strategy.id,
            rules.sel == null ? null : rules.sel.id);
    }

    private void addRule() {
        CombatPlan.Strategy strategy = editableSelectedStrategy();
        if(strategy == null) {
            updateValidation("Create or select a strategy first.");
            return;
        }
        CombatPlan.Rule rule = new CombatPlan.Rule();
        rule.name = "New rule";
        strategy.rules.add(rule);
        changed("Added rule.", strategy.id, rule.id);
    }

    private void duplicateRule() {
        CombatPlan.Rule selected = rules.sel;
        CombatPlan.Strategy strategy = editableSelectedStrategy();
        if(selected == null || strategy == null)
            return;
        selected = findRule(strategy.rules, selected.id);
        CombatPlan.Rule copy = selected.copy();
        copy.id = CombatPlan.id();
        copy.name = selected.name + " Copy";
        int index = strategy.rules.indexOf(selected);
        strategy.rules.add(index + 1, copy);
        changed("Duplicated rule.", strategy.id, copy.id);
    }

    private void moveRule(int direction) {
        CombatPlan.Rule selected = rules.sel;
        CombatPlan.Strategy strategy = editableSelectedStrategy();
        if(selected == null || strategy == null)
            return;
        selected = findRule(strategy.rules, selected.id);
        int from = strategy.rules.indexOf(selected);
        int to = from + direction;
        if(from < 0 || to < 0 || to >= strategy.rules.size())
            return;
        strategy.rules.remove(from);
        strategy.rules.add(to, selected);
        changed("Reordered rules.", strategy.id, selected.id);
    }

    private void toggleRule() {
        CombatPlan.Rule selected = editableSelectedRule();
        if(selected == null)
            return;
        selected.enabled = !selected.enabled;
        changed((selected.enabled ? "Enabled " : "Disabled ") + selected.name + ".",
            strategyId, selected.id);
    }

    private void deleteRule() {
        CombatPlan.Rule selected = rules.sel;
        CombatPlan.Strategy strategy = editableSelectedStrategy();
        if(selected == null || strategy == null)
            return;
        selected = findRule(strategy.rules, selected.id);
        strategy.rules.remove(selected);
        changed("Deleted rule.", strategy.id, null);
    }

    private void rebuildRuleEditor() {
        rebuildEditor = false;
        for(Widget child : new ArrayList<>(editorHost.children()))
            child.destroy();
        CombatPlan.Rule selected = rules.sel;
        editorRule = selected;
        if(selected == null) {
            editorHost.add(new Label("Select a rule to edit its conditions and action."), Coord.z);
            return;
        }
        editorHost.add(new RuleEditor(selected), Coord.z);
    }

    private void save() {
        List<String> errors = CombatPlanStore.validateCharacter(working);
        if(!errors.isEmpty()) {
            updateValidation("Cannot save: " + errors.get(0));
            return;
        }
        try {
            store.saveCharacter(gui.chrid, working);
            working = store.character(gui.chrid);
            dirty = false;
            rebuildTargets();
            rebuildStrategies(strategyId);
            updateValidation("Saved. The active strategy will be used on the next action cycle.");
        } catch(IOException | RuntimeException e) {
            updateValidation("Save failed: " + e.getMessage());
            gui.error("Combat planner save failed: " + e.getMessage());
        }
    }

    private void revert() {
        store.reload();
        working = store.character(gui.chrid);
        dirty = false;
        if(!working.profiles.containsKey(targetKey))
            targetKey = CombatPlan.ALL_COMBAT;
        rebuildTargets();
        rebuildStrategies(null);
        updateValidation(store.loadError() == null ? "Reverted to the last saved plan." : store.loadError());
    }

    private void changed(String message, String preferredStrategy, String preferredRule) {
        dirty = true;
        rebuildTargets();
        rebuildStrategies(preferredStrategy);
        refreshRules(preferredRule);
        updateValidation(message + " Save to apply it.");
    }

    private CombatPlan.TargetProfile shownProfile() {
        CombatPlan.TargetProfile explicit = explicitProfile();
        return(explicit != null && explicit.strategies != null && !explicit.strategies.isEmpty() ?
            explicit : working.allCombat());
    }

    private CombatPlan.TargetProfile explicitProfile() {
        return(working.profiles == null ? null : working.profiles.get(targetKey));
    }

    private CombatPlan.TargetProfile ensureProfile() {
        CombatPlan.TargetProfile profile = explicitProfile();
        if(profile != null)
            return(profile);
        profile = new CombatPlan.TargetProfile();
        profile.targetKey = targetKey;
        TargetOption option = findTarget(targetKey);
        profile.displayName = option == null ? CombatTargetCatalog.labelFor(targetKey) : option.label;
        working.profiles.put(targetKey, profile);
        return(profile);
    }

    private CombatPlan.Strategy editableSelectedStrategy() {
        CombatPlan.TargetProfile explicit = explicitProfile();
        CombatPlan.Strategy selected = selectedStrategy();
        if(selected == null)
            return(null);
        if(explicit != null && explicit.strategies != null && !explicit.strategies.isEmpty())
            return(findStrategy(explicit.strategies, selected.id));
        CombatPlan.TargetProfile profile = ensureProfile();
        CombatPlan.Strategy copy = selected.copy();
        copy.id = CombatPlan.id();
        profile.strategies.add(copy);
        profile.activeStrategyId = copy.id;
        strategyId = copy.id;
        return(copy);
    }

    private CombatPlan.Rule editableSelectedRule() {
        String ruleId = rules.sel == null ? null : rules.sel.id;
        CombatPlan.Strategy strategy = editableSelectedStrategy();
        if(strategy == null)
            return(null);
        CombatPlan.Rule rule = findRule(strategy.rules, ruleId);
        if(rule == null && !strategy.rules.isEmpty())
            rule = strategy.rules.get(0);
        return(rule);
    }

    private CombatPlan.Strategy selectedStrategy() {
        CombatPlan.TargetProfile profile = shownProfile();
        return(profile == null ? null : findStrategy(profile.strategies, strategyId));
    }

    private String strategyLabel(CombatPlan.Strategy strategy) {
        CombatPlan.TargetProfile profile = shownProfile();
        boolean active = profile != null && Objects.equals(profile.activeStrategyId, strategy.id);
        boolean inherited = explicitProfile() == null && !CombatPlan.ALL_COMBAT.equals(targetKey);
        return((active ? "* " : "  ") + strategy.name + (inherited ? " [inherited]" : ""));
    }

    private String requestedName(String fallback) {
        String text = strategyName.text() == null ? "" : strategyName.text().trim();
        return(text.isEmpty() ? fallback : text);
    }

    private static String uniqueStrategyName(CombatPlan.TargetProfile profile, String base) {
        String candidate = base;
        int suffix = 2;
        while(hasStrategyName(profile, candidate))
            candidate = base + " " + suffix++;
        return(candidate);
    }

    private static boolean hasStrategyName(CombatPlan.TargetProfile profile, String name) {
        for(CombatPlan.Strategy strategy : profile.strategies) {
            if(strategy.name != null && strategy.name.equalsIgnoreCase(name))
                return(true);
        }
        return(false);
    }

    private TargetOption findTarget(String key) {
        for(TargetOption option : targets.items) {
            if(Objects.equals(option.key, key))
                return(option);
        }
        return(null);
    }

    private static CombatPlan.Strategy findStrategy(Collection<CombatPlan.Strategy> strategies, String id) {
        if(strategies == null)
            return(null);
        for(CombatPlan.Strategy strategy : strategies) {
            if(strategy != null && Objects.equals(strategy.id, id))
                return(strategy);
        }
        return(null);
    }

    private static CombatPlan.Rule findRule(Collection<CombatPlan.Rule> rules, String id) {
        if(rules == null)
            return(null);
        for(CombatPlan.Rule rule : rules) {
            if(rule != null && Objects.equals(rule.id, id))
                return(rule);
        }
        return(null);
    }

    private void updateValidation(String text) {
        validation.settext((dirty ? "Unsaved — " : "") + text);
    }

    private void updateRuntime() {
        runtime.settext("Runtime: " + CombatAutomation.status());
        toggle.change(toggleText());
    }

    private String toggleText() {
        return(CombatAutomation.enabled() ? "Disable" : "Enable");
    }

    private String deckSignature() {
        StringBuilder signature = new StringBuilder();
        for(CombatAutomation.MoveInfo move : CombatAutomation.currentDeck(gui).values())
            signature.append(move.resource).append('|').append(move.name).append(';');
        return(signature.toString());
    }

    public static void toggle(GameUI gui) {
        if(gui == null)
            return;
        if(instance == null)
            instance = gui.add(new CombatAutomationWnd(gui));
        else {
            instance.reqdestroy();
            instance = null;
        }
    }

    @Override
    public void destroy() {
        super.destroy();
        instance = null;
    }

    private final class RuleEditor extends Widget {
        private final CombatPlan.Rule source;
        private final TextEntry ruleName;
        private final CheckBox enabled;
        private final ChoiceDrop<CombatPlan.ActionKind> actionKind;
        private final ChoiceDrop<MoveChoice> move;
        private final TextEntry threshold;
        private final ConditionList conditions;
        private final ChoiceDrop<CombatPlan.Metric> metric;
        private final ChoiceDrop<CombatPlan.Comparison> comparison;
        private final TextEntry value;
        private final StatusLabel moveDescription;

        RuleEditor(CombatPlan.Rule source) {
            super(Coord.of(UI.scale(WIDTH), UI.scale(265)));
            this.source = source;
            int y = 0;
            add(new Label("Rule name:"), 0, y + UI.scale(3));
            ruleName = add(new TextEntry(UI.scale(250), source.name), UI.scale(75), y);
            enabled = add(new CheckBox("Enabled"), UI.scale(340), y + UI.scale(2));
            enabled.a = source.enabled;
            add(new Button(UI.scale(90), "Apply Rule", this::applyRule), UI.scale(WIDTH - 90), y);
            y += ROW + UI.scale(5);

            add(new Label("Action:"), 0, y + UI.scale(3));
            actionKind = add(new ChoiceDrop<>(UI.scale(175), 2, ROW, kind -> kind.label), UI.scale(75), y);
            List<CombatPlan.ActionKind> kinds = new ArrayList<>();
            kinds.add(CombatPlan.ActionKind.MOVE);
            kinds.add(CombatPlan.ActionKind.AUTO_CLEAR);
            actionKind.setItems(kinds);
            actionKind.sel = source.action == null || source.action.kind == null ? CombatPlan.ActionKind.MOVE : source.action.kind;

            List<MoveChoice> moveChoices = moveChoices(source.action == null ? null : source.action.resource);
            move = add(new ChoiceDrop<>(UI.scale(300), 16, ROW, choice -> choice.label()), UI.scale(260), y);
            move.setItems(moveChoices);
            move.sel = findMove(moveChoices, source.action == null ? null : source.action.resource);
            add(new Label("Clear at %:"), UI.scale(570), y + UI.scale(3));
            threshold = add(new TextEntry(UI.scale(45), Integer.toString(
                source.action == null ? 40 : source.action.openingThreshold)), UI.scale(655), y);
            y += ROW + UI.scale(4);

            MoveChoice selectedMove = move.sel;
            moveDescription = add(new StatusLabel(selectedMove == null ? "Select an exact move." : selectedMove.description,
                UI.scale(WIDTH)), 0, y);
            move.onChange = selected -> moveDescription.settext(selected == null ? "" : selected.description);
            y += UI.scale(38);

            add(new Label("Conditions (all must match; no conditions means Always):"), 0, y);
            y += UI.scale(18);
            conditions = add(new ConditionList(UI.scale(360), 4,
                source.conditions == null ? new ArrayList<>() : source.conditions), 0, y);
            int x = UI.scale(370);
            metric = add(new ChoiceDrop<>(UI.scale(210), CombatPlan.Metric.values().length, ROW,
                item -> item.label), x, y);
            List<CombatPlan.Metric> metrics = new ArrayList<>();
            for(CombatPlan.Metric item : CombatPlan.Metric.values())
                metrics.add(item);
            metric.setItems(metrics);

            comparison = add(new ChoiceDrop<>(UI.scale(55), CombatPlan.Comparison.values().length, ROW,
                item -> item.symbol), x + UI.scale(215), y);
            List<CombatPlan.Comparison> comparisons = new ArrayList<>();
            for(CombatPlan.Comparison item : CombatPlan.Comparison.values())
                comparisons.add(item);
            comparison.setItems(comparisons);

            CombatPlan.Condition selectedCondition = conditions.sel;
            metric.sel = selectedCondition == null ? CombatPlan.Metric.OPPONENT_RED : selectedCondition.metric;
            comparison.sel = selectedCondition == null ? CombatPlan.Comparison.LT : selectedCondition.comparison;
            value = add(new TextEntry(UI.scale(60), selectedCondition == null ? "55" : formatNumber(selectedCondition.value)),
                x + UI.scale(275), y);
            conditions.onChange = condition -> {
                if(condition != null) {
                    metric.sel = condition.metric;
                    comparison.sel = condition.comparison;
                    value.settext(formatNumber(condition.value));
                }
            };
            add(new Button(UI.scale(75), "Apply", this::applyCondition), x + UI.scale(340), y);
            y += UI.scale(28);
            add(new Button(UI.scale(100), "Add condition", this::addCondition), x, y);
            add(new Button(UI.scale(110), "Delete condition", this::deleteCondition), x + UI.scale(105), y);
        }

        private void applyRule() {
            CombatPlan.Rule rule = editableRule();
            if(rule == null)
                return;
            String name = ruleName.text() == null ? "" : ruleName.text().trim();
            if(name.isEmpty()) {
                updateValidation("Rule name cannot be blank.");
                return;
            }
            CombatPlan.Action action = new CombatPlan.Action();
            action.kind = actionKind.sel;
            if(action.kind == CombatPlan.ActionKind.MOVE) {
                if(move.sel == null) {
                    updateValidation("Select an exact combat move.");
                    return;
                }
                action.resource = move.sel.resource;
            } else {
                try {
                    action.openingThreshold = Integer.parseInt(threshold.text().trim());
                } catch(NumberFormatException e) {
                    updateValidation("Auto-clear threshold must be a whole number from 0 to 100.");
                    return;
                }
                if(action.openingThreshold < 0 || action.openingThreshold > 100) {
                    updateValidation("Auto-clear threshold must be from 0 to 100.");
                    return;
                }
            }
            rule.name = name;
            rule.enabled = enabled.a;
            rule.action = action;
            changed("Updated rule " + name + ".", strategyId, rule.id);
        }

        private void addCondition() {
            CombatPlan.Rule rule = editableRule();
            if(rule == null)
                return;
            CombatPlan.Condition condition = new CombatPlan.Condition();
            rule.conditions.add(condition);
            changed("Added condition.", strategyId, rule.id);
            rebuildEditor = true;
        }

        private void deleteCondition() {
            CombatPlan.Rule rule = editableRule();
            if(rule == null || conditions.sel == null)
                return;
            int index = source.conditions.indexOf(conditions.sel);
            if(index >= 0 && index < rule.conditions.size())
                rule.conditions.remove(index);
            changed("Deleted condition.", strategyId, rule.id);
        }

        private void applyCondition() {
            CombatPlan.Rule rule = editableRule();
            if(rule == null || conditions.sel == null) {
                updateValidation("Select or add a condition first.");
                return;
            }
            double number;
            try {
                number = Double.parseDouble(value.text().trim());
            } catch(NumberFormatException e) {
                updateValidation("Condition value must be a number.");
                return;
            }
            int index = source.conditions.indexOf(conditions.sel);
            if(index < 0 || index >= rule.conditions.size())
                return;
            CombatPlan.Condition condition = rule.conditions.get(index);
            condition.metric = metric.sel;
            condition.comparison = comparison.sel;
            condition.value = number;
            changed("Updated condition.", strategyId, rule.id);
        }

        private CombatPlan.Rule editableRule() {
            String id = source.id;
            CombatPlan.Strategy strategy = editableSelectedStrategy();
            return(strategy == null ? null : findRule(strategy.rules, id));
        }
    }

    private List<MoveChoice> moveChoices(String requiredResource) {
        Map<String, CombatAutomation.MoveInfo> deck = CombatAutomation.currentDeck(gui);
        List<CombatMoveCatalog.Entry> discovered = new ArrayList<>();
        for(CombatAutomation.MoveInfo info : deck.values())
            discovered.add(CombatMoveCatalog.discovered(info.resource, info.name));
        List<MoveChoice> result = new ArrayList<>();
        for(CombatMoveCatalog.Entry entry : CombatMoveCatalog.withDiscovered(discovered)) {
            CombatAutomation.MoveInfo live = deck.get(entry.resource);
            String name = live == null ? entry.label : live.name;
            String description = live == null || live.description == null || live.description.trim().isEmpty() ?
                entry.category.label + " — " + entry.resource : live.description;
            result.add(new MoveChoice(entry.resource, name, description, live != null));
        }
        if(requiredResource != null && findMove(result, requiredResource) == null) {
            CombatAutomation.MoveInfo live = deck.get(requiredResource);
            result.add(new MoveChoice(requiredResource,
                live == null ? CombatMoveCatalog.label(requiredResource) : live.name,
                live == null ? requiredResource : live.description, live != null));
        }
        result.sort(Comparator.comparing((MoveChoice choice) -> !choice.equipped).thenComparing(choice -> choice.name));
        return(result);
    }

    private static MoveChoice findMove(Collection<MoveChoice> choices, String resource) {
        for(MoveChoice choice : choices) {
            if(Objects.equals(choice.resource, resource))
                return(choice);
        }
        return(null);
    }

    private static String formatNumber(double value) {
        return(value == Math.rint(value) ? Long.toString(Math.round(value)) : Double.toString(value));
    }

    private static String profileKey(String resource) {
        String normalized = CombatTargetCatalog.normalize(resource);
        CombatTargetCatalog.Entry known = CombatTargetCatalog.knownMatch(normalized);
        return(known == null ? normalized : known.key);
    }

    private static final class TargetOption {
        final String key;
        final String label;

        TargetOption(String key, String label) {
            this.key = key;
            this.label = label;
        }
    }

    private static final class MoveChoice {
        final String resource;
        final String name;
        final String description;
        final boolean equipped;

        MoveChoice(String resource, String name, String description, boolean equipped) {
            this.resource = resource;
            this.name = name;
            this.description = description;
            this.equipped = equipped;
        }

        String label() {
            return((equipped ? "[equipped] " : "[missing] ") + name);
        }
    }

    private static class ChoiceDrop<T> extends Dropbox<T> {
        final List<T> items = new ArrayList<>();
        final Function<T, String> label;
        java.util.function.Consumer<T> onChange;

        ChoiceDrop(int width, int rows, int rowHeight, Function<T, String> label) {
            super(width, rows, rowHeight);
            this.label = label;
        }

        void setItems(Collection<T> values) {
            items.clear();
            if(values != null)
                items.addAll(values);
            if(sel != null && !items.contains(sel))
                sel = null;
        }

        protected T listitem(int index) {
            return(items.get(index));
        }

        protected int listitems() {
            return(items.size());
        }

        protected void drawitem(GOut g, T item, int index) {
            g.text(item == null ? "" : label.apply(item), Coord.z);
        }

        @Override
        public void change(T item) {
            super.change(item);
            if(onChange != null)
                onChange.accept(sel);
        }
    }

    private final class RuleList extends Listbox<CombatPlan.Rule> {
        final List<CombatPlan.Rule> items = new ArrayList<>();
        java.util.function.Consumer<CombatPlan.Rule> onChange;

        RuleList(int width, int rows) {
            super(width, rows, ROW);
            bgcolor = new Color(0, 0, 0, 160);
        }

        void setItems(Collection<CombatPlan.Rule> rules) {
            items.clear();
            if(rules != null)
                items.addAll(rules);
        }

        protected CombatPlan.Rule listitem(int index) {
            return(items.get(index));
        }

        protected int listitems() {
            return(items.size());
        }

        protected void drawitem(GOut g, CombatPlan.Rule rule, int index) {
            String conditions = rule.conditions == null || rule.conditions.isEmpty() ? "Always" :
                rule.conditions.size() + (rule.conditions.size() == 1 ? " condition" : " conditions");
            String action = rule.action == null ? "missing action" :
                rule.action.kind == CombatPlan.ActionKind.AUTO_CLEAR ?
                    "Auto-clear >= " + rule.action.openingThreshold + "%" :
                    CombatMoveCatalog.label(rule.action.resource);
            g.text(String.format("%02d  %s  %s — %s -> %s", index + 1,
                rule.enabled ? "[on] " : "[off]", rule.name, conditions, action), Coord.z);
        }

        @Override
        public void change(CombatPlan.Rule item) {
            super.change(item);
            if(onChange != null)
                onChange.accept(sel);
        }
    }

    private static final class ConditionList extends Listbox<CombatPlan.Condition> {
        final List<CombatPlan.Condition> items = new ArrayList<>();
        java.util.function.Consumer<CombatPlan.Condition> onChange;

        ConditionList(int width, int rows, Collection<CombatPlan.Condition> values) {
            super(width, rows, ROW);
            if(values != null)
                items.addAll(values);
            if(!items.isEmpty())
                sel = items.get(0);
        }

        protected CombatPlan.Condition listitem(int index) {
            return(items.get(index));
        }

        protected int listitems() {
            return(items.size());
        }

        protected void drawitem(GOut g, CombatPlan.Condition condition, int index) {
            g.text((condition.metric == null ? "?" : condition.metric.label) + " " +
                (condition.comparison == null ? "?" : condition.comparison.symbol) + " " +
                formatNumber(condition.value), Coord.z);
        }

        @Override
        public void change(CombatPlan.Condition item) {
            super.change(item);
            if(onChange != null)
                onChange.accept(sel);
        }
    }

    private static final class StatusLabel extends Label {
        private final int width;

        StatusLabel(String text, int width) {
            super(text, width);
            this.width = width;
        }

        @Override
        public void settext(String text) {
            if(text.equals(this.texts))
                return;
            this.text.dispose();
            this.text = f.renderwrap(texts = text, col, width);
            resize(this.text.sz());
        }
    }
}
