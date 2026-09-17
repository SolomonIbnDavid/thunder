package thunder.combat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import haven.Config;
import haven.Warning;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Versioned, atomic per-character persistence for combat plans. */
public final class CombatPlanStore {
    public static final int SCHEMA_VERSION = 1;
    public static final String FILENAME = "combat-plans.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static CombatPlanStore instance;

    static final class Root {
        int version = SCHEMA_VERSION;
        Map<String, CombatPlan.CharacterPlans> characters = new LinkedHashMap<>();
    }

    private final Path file;
    private Root root;
    private String loadError;
    private long revision;

    CombatPlanStore(Path file) {
        this.file = file;
        reload();
    }

    public static synchronized CombatPlanStore get() {
        if(instance == null)
            instance = new CombatPlanStore(Config.getFile(FILENAME).toPath());
        return(instance);
    }

    public synchronized void reload() {
        loadError = null;
        if(!Files.exists(file)) {
            root = new Root();
            revision++;
            return;
        }
        try {
            String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            JsonElement parsed = JsonParser.parseString(json);
            if(!parsed.isJsonObject())
                throw new IllegalArgumentException("root must be a JSON object");
            JsonObject object = parsed.getAsJsonObject();
            if(!object.has("version") || !object.get("version").isJsonPrimitive())
                throw new IllegalArgumentException("missing schema version");
            double rawVersion = object.get("version").getAsDouble();
            int version = object.get("version").getAsInt();
            if(rawVersion != version || version != SCHEMA_VERSION)
                throw new UnsupportedSchemaException("unsupported schema version " + object.get("version"));
            Root loaded = GSON.fromJson(object, Root.class);
            if(loaded == null || loaded.characters == null)
                throw new IllegalArgumentException("missing characters map");
            List<String> errors = validateRoot(loaded);
            if(!errors.isEmpty())
                throw new IllegalArgumentException(errors.get(0));
            root = loaded;
            revision++;
        } catch(Exception e) {
            root = new Root();
            loadError = "Cannot safely read " + file + ": " + e.getMessage();
            new Warning(e, "combat planner: refusing to overwrite unreadable data at " + file).issue();
        }
    }

    public synchronized CombatPlan.CharacterPlans character(String characterId) {
        String id = characterId(characterId);
        CombatPlan.CharacterPlans plans = root.characters.get(id);
        return(plans == null ? CombatPlan.defaults() : plans.copy());
    }

    public synchronized CombatPlan.Resolution resolve(String characterId, String targetResource) {
        return(CombatPlan.resolve(character(characterId), targetResource));
    }

    public synchronized long revision() {
        return(revision);
    }

    public synchronized String loadError() {
        return(loadError);
    }

    public synchronized boolean writeBlocked() {
        return(loadError != null);
    }

    public Path file() {
        return(file);
    }

    public synchronized void saveCharacter(String characterId, CombatPlan.CharacterPlans plans) throws IOException {
        if(loadError != null)
            throw new IOException(loadError + ". Correct or move that file, then use Revert to reload it.");
        String id = characterId(characterId);
        CombatPlan.CharacterPlans copy = plans == null ? null : plans.copy();
        List<String> errors = validateCharacter(copy);
        if(!errors.isEmpty())
            throw new IllegalArgumentException(errors.get(0));
        Root next = copyRoot(root);
        next.characters.put(id, copy);
        write(next);
        root = next;
        revision++;
    }

    public synchronized boolean observeTarget(String characterId, String resource, String displayName) {
        if(loadError != null)
            return(false);
        String normalized = CombatTargetCatalog.normalize(resource);
        if(normalized.isEmpty() || CombatTargetCatalog.knownMatch(normalized) != null)
            return(false);
        CombatPlan.CharacterPlans plans = character(characterId);
        if(plans.observedTargets.containsKey(normalized))
            return(false);
        plans.observedTargets.put(normalized,
            displayName == null || displayName.trim().isEmpty() ? CombatTargetCatalog.labelFor(normalized) : displayName);
        try {
            saveCharacter(characterId, plans);
            return(true);
        } catch(IOException | RuntimeException e) {
            new Warning(e, "combat planner: could not save observed target " + normalized).issue();
            return(false);
        }
    }

    public static List<String> validateCharacter(CombatPlan.CharacterPlans plans) {
        List<String> errors = new ArrayList<>();
        if(plans == null) {
            errors.add("Character plans are missing");
            return(errors);
        }
        if(plans.profiles == null) {
            errors.add("Target profiles are missing");
            return(errors);
        }
        if(plans.observedTargets == null)
            errors.add("Observed-target map is missing");
        CombatPlan.TargetProfile all = plans.profiles.get(CombatPlan.ALL_COMBAT);
        if(all == null)
            errors.add("All Combat profile is required");
        for(Map.Entry<String, CombatPlan.TargetProfile> entry : plans.profiles.entrySet())
            validateProfile(entry.getKey(), entry.getValue(), errors);
        return(errors);
    }

    private static List<String> validateRoot(Root root) {
        List<String> errors = new ArrayList<>();
        if(root.version != SCHEMA_VERSION)
            errors.add("Unsupported schema version " + root.version);
        if(root.characters == null) {
            errors.add("Characters map is missing");
            return(errors);
        }
        for(Map.Entry<String, CombatPlan.CharacterPlans> entry : root.characters.entrySet()) {
            if(entry.getKey() == null || entry.getKey().trim().isEmpty())
                errors.add("Character ID is blank");
            List<String> characterErrors = validateCharacter(entry.getValue());
            for(String error : characterErrors)
                errors.add("Character " + entry.getKey() + ": " + error);
        }
        return(errors);
    }

    private static void validateProfile(String key, CombatPlan.TargetProfile profile, List<String> errors) {
        if(profile == null) {
            errors.add("Profile " + key + " is missing");
            return;
        }
        if(key == null || key.trim().isEmpty())
            errors.add("Profile key is blank");
        if(profile.targetKey == null || !profile.targetKey.equals(key))
            errors.add("Profile key does not match its target key: " + key);
        validateName(profile.displayName, "Profile name", errors);
        if(profile.strategies == null) {
            errors.add("Strategies are missing for " + profile.displayName);
            return;
        }
        if(profile.strategies.isEmpty()) {
            errors.add("Profile must contain at least one strategy: " + profile.displayName);
            return;
        }
        Set<String> ids = new HashSet<>();
        Set<String> names = new HashSet<>();
        boolean activeFound = false;
        for(CombatPlan.Strategy strategy : profile.strategies) {
            if(strategy == null) {
                errors.add("Null strategy in " + profile.displayName);
                continue;
            }
            validateName(strategy.name, "Strategy name", errors);
            if(strategy.name != null && !names.add(strategy.name.trim().toLowerCase(Locale.ROOT)))
                errors.add("Duplicate strategy name: " + strategy.name);
            if(strategy.id == null || strategy.id.trim().isEmpty() || !ids.add(strategy.id))
                errors.add("Strategy IDs must be nonblank and unique in " + profile.displayName);
            if(strategy.id != null && strategy.id.equals(profile.activeStrategyId))
                activeFound = true;
            validateStrategy(strategy, errors);
        }
        if(profile.activeStrategyId == null || !activeFound)
            errors.add("Active strategy is missing or invalid for " + profile.displayName);
    }

    private static void validateStrategy(CombatPlan.Strategy strategy, List<String> errors) {
        if(strategy.rules == null) {
            errors.add("Rules are missing for strategy " + strategy.name);
            return;
        }
        Set<String> ids = new HashSet<>();
        for(CombatPlan.Rule rule : strategy.rules) {
            if(rule == null) {
                errors.add("Null rule in strategy " + strategy.name);
                continue;
            }
            validateName(rule.name, "Rule name", errors);
            if(rule.id == null || rule.id.trim().isEmpty() || !ids.add(rule.id))
                errors.add("Rule IDs must be nonblank and unique in " + strategy.name);
            if(rule.conditions == null) {
                errors.add("Conditions are missing for rule " + rule.name);
            } else {
                for(CombatPlan.Condition condition : rule.conditions)
                    validateCondition(condition, rule.name, errors);
            }
            validateAction(rule.action, rule.name, errors);
        }
    }

    private static void validateCondition(CombatPlan.Condition condition, String rule, List<String> errors) {
        if(condition == null || condition.metric == null || condition.comparison == null) {
            errors.add("Incomplete condition in rule " + rule);
            return;
        }
        if(!Double.isFinite(condition.value) || condition.value < 0)
            errors.add("Condition value must be a nonnegative number in rule " + rule);
        else if(condition.metric.percent && condition.value > 100)
            errors.add("Opening percentage must be between 0 and 100 in rule " + rule);
    }

    private static void validateAction(CombatPlan.Action action, String rule, List<String> errors) {
        if(action == null || action.kind == null) {
            errors.add("Action is missing in rule " + rule);
            return;
        }
        if(action.kind == CombatPlan.ActionKind.MOVE) {
            if(action.resource == null || action.resource.trim().isEmpty())
                errors.add("Exact move is missing in rule " + rule);
            else if(!action.resource.equals(action.resource.trim()) || !action.resource.startsWith("paginae/atk/"))
                errors.add("Exact move must use a stable paginae/atk resource ID in rule " + rule);
        } else if(action.openingThreshold < 0 || action.openingThreshold > 100) {
            errors.add("Auto-clear threshold must be between 0 and 100 in rule " + rule);
        }
    }

    private static void validateName(String name, String field, List<String> errors) {
        if(name == null || name.trim().isEmpty())
            errors.add(field + " cannot be blank");
        else if(name.trim().length() > 80)
            errors.add(field + " cannot exceed 80 characters");
    }

    private void write(Root next) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if(parent != null)
            Files.createDirectories(parent);
        Path temporary = file.resolveSibling(file.getFileName().toString() + ".next");
        Files.write(temporary, GSON.toJson(next).getBytes(StandardCharsets.UTF_8),
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch(AtomicMoveNotSupportedException e) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Root copyRoot(Root source) {
        Root copy = new Root();
        copy.version = source.version;
        for(Map.Entry<String, CombatPlan.CharacterPlans> entry : source.characters.entrySet())
            copy.characters.put(entry.getKey(), entry.getValue().copy());
        return(copy);
    }

    private static String characterId(String id) {
        return(id == null || id.trim().isEmpty() ? "unknown-character" : id.trim());
    }

    private static final class UnsupportedSchemaException extends Exception {
        UnsupportedSchemaException(String message) {
            super(message);
        }
    }
}
