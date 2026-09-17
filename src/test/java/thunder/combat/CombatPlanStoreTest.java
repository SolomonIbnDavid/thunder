package thunder.combat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CombatPlanStoreTest {
    @TempDir Path temporary;

    @Test void roundTripsPlansAtomicallyAndIsolatesCharacters() throws Exception {
        Path file = temporary.resolve("combat-plans.json");
        CombatPlanStore store = new CombatPlanStore(file);
        CombatPlan.CharacterPlans alice = store.character("alice");
        alice.allCombat().activeStrategy().name = "Alice Default";
        store.saveCharacter("alice", alice);

        CombatPlanStore reloaded = new CombatPlanStore(file);
        assertEquals("Alice Default", reloaded.character("alice").allCombat().activeStrategy().name);
        assertEquals("Default", reloaded.character("bob").allCombat().activeStrategy().name);
        assertFalse(Files.exists(file.resolveSibling("combat-plans.json.next")));
    }

    @Test void persistsMultipleNamedStrategiesAndTheirActiveChoice() throws Exception {
        Path file = temporary.resolve("combat-plans.json");
        CombatPlanStore store = new CombatPlanStore(file);
        CombatPlan.CharacterPlans plans = store.character("character");
        CombatPlan.TargetProfile bear = profile("gfx/kritter/bear/", "Bear",
            strategy("Safe"), strategy("Fast"));
        bear.activeStrategyId = bear.strategies.get(1).id;
        plans.profiles.put(bear.targetKey, bear);
        store.saveCharacter("character", plans);

        CombatPlan.Resolution resolved = new CombatPlanStore(file).resolve(
            "character", "gfx/kritter/bear/bear");
        assertEquals("Bear", resolved.profile.displayName);
        assertEquals("Fast", resolved.strategy.name);
        assertFalse(resolved.fallback);
    }

    @Test void usesLongestTargetPrefixAndFallsBackForUnknownOrNonAnimalTargets() {
        CombatPlan.CharacterPlans plans = CombatPlan.defaults();
        CombatPlan.TargetProfile broad = profile("gfx/kritter/", "Any Kritter", strategy("Broad"));
        CombatPlan.TargetProfile bear = profile("gfx/kritter/bear/", "Bear", strategy("Bear Plan"));
        plans.profiles.put(broad.targetKey, broad);
        plans.profiles.put(bear.targetKey, bear);
        assertEquals("Bear Plan", CombatPlan.resolve(plans, "GFX/KRITTER/BEAR/bear[12]").strategy.name);
        assertEquals("Broad", CombatPlan.resolve(plans, "gfx/kritter/fox/fox").strategy.name);
        assertEquals("Default", CombatPlan.resolve(plans, "gfx/borka/body").strategy.name);
    }

    @Test void emptyAnimalProfileInheritsTheActiveAllCombatStrategy() {
        CombatPlan.CharacterPlans plans = CombatPlan.defaults();
        plans.allCombat().activeStrategy().name = "Universal";
        CombatPlan.TargetProfile bear = new CombatPlan.TargetProfile();
        bear.targetKey = "gfx/kritter/bear/";
        bear.displayName = "Bear";
        plans.profiles.put(bear.targetKey, bear);
        CombatPlan.Resolution resolved = CombatPlan.resolve(plans, "gfx/kritter/bear/bear");
        assertEquals("Universal", resolved.strategy.name);
        assertTrue(resolved.fallback);
    }

    @Test void malformedDataBlocksWritesAndIsNeverOverwritten() throws Exception {
        Path file = temporary.resolve("combat-plans.json");
        String malformed = "{this is not JSON";
        Files.write(file, malformed.getBytes(StandardCharsets.UTF_8));
        CombatPlanStore store = new CombatPlanStore(file);
        assertTrue(store.writeBlocked());
        assertNotNull(store.loadError());
        assertThrows(IOException.class, () -> store.saveCharacter("character", CombatPlan.defaults()));
        assertEquals(malformed, new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
    }

    @Test void unsupportedSchemaBlocksWritesAndIsNeverOverwritten() throws Exception {
        Path file = temporary.resolve("combat-plans.json");
        String future = "{\"version\":999,\"characters\":{}}";
        Files.write(file, future.getBytes(StandardCharsets.UTF_8));
        CombatPlanStore store = new CombatPlanStore(file);
        assertTrue(store.writeBlocked());
        assertThrows(IOException.class, () -> store.saveCharacter("character", CombatPlan.defaults()));
        assertEquals(future, new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
    }

    @Test void validationRejectsBadNamesActionsThresholdsActiveChoicesAndRuleStructure() {
        CombatPlan.CharacterPlans plans = CombatPlan.defaults();
        CombatPlan.TargetProfile all = plans.allCombat();
        CombatPlan.Strategy strategy = all.activeStrategy();
        strategy.name = " ";
        strategy.rules.get(0).action.openingThreshold = 101;
        strategy.rules.get(1).action.resource = "";
        strategy.rules.get(2).conditions = null;
        all.activeStrategyId = "missing";
        List<String> errors = CombatPlanStore.validateCharacter(plans);
        assertTrue(errors.stream().anyMatch(error -> error.contains("Strategy name")));
        assertTrue(errors.stream().anyMatch(error -> error.contains("threshold")));
        assertTrue(errors.stream().anyMatch(error -> error.contains("Exact move")));
        assertTrue(errors.stream().anyMatch(error -> error.contains("Conditions")));
        assertTrue(errors.stream().anyMatch(error -> error.contains("Active strategy")));
    }

    @Test void observationIsCharacterSpecificAndPersistsOnce() {
        Path file = temporary.resolve("combat-plans.json");
        CombatPlanStore store = new CombatPlanStore(file);
        assertTrue(store.observeTarget("alice", "gfx/custom/monster", "Monster"));
        assertFalse(store.observeTarget("alice", "gfx/custom/monster", "Monster"));
        assertFalse(store.character("bob").observedTargets.containsKey("gfx/custom/monster"));
        assertEquals("Monster", new CombatPlanStore(file).character("alice").observedTargets.get("gfx/custom/monster"));
    }

    private static CombatPlan.TargetProfile profile(String key, String name,
                                                    CombatPlan.Strategy... strategies) {
        CombatPlan.TargetProfile profile = new CombatPlan.TargetProfile();
        profile.targetKey = key;
        profile.displayName = name;
        profile.strategies.addAll(Arrays.asList(strategies));
        profile.activeStrategyId = profile.strategies.isEmpty() ? null : profile.strategies.get(0).id;
        return profile;
    }

    private static CombatPlan.Strategy strategy(String name) {
        CombatPlan.Strategy strategy = new CombatPlan.Strategy();
        strategy.name = name;
        CombatPlan.Rule rule = new CombatPlan.Rule();
        rule.name = "Attack";
        rule.action = CombatPlan.Action.move(CombatPlan.FULL_CIRCLE);
        strategy.rules.add(rule);
        return strategy;
    }
}
