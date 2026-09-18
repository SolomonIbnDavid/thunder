package thunder;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import haven.CFG;

import java.util.*;

/** Persistent and clipboard-shareable material quality thresholds. */
public final class TileQualityThresholds {
    public static final String SHARE_TYPE = "thunder-tile-quality-thresholds";
    public static final int SHARE_VERSION = 1;

    private TileQualityThresholds() {}

    public static final class Profile {
        public final int anyRockQualityX10;
        public final Map<String, Integer> thresholds;

        Profile(int anyRockQualityX10, Map<String, Integer> thresholds) {
            this.anyRockQualityX10 = sanitizeQuality(anyRockQualityX10);
            this.thresholds = Collections.unmodifiableMap(new LinkedHashMap<>(sanitize(thresholds)));
        }
    }

    public static Map<String, Integer> snapshot() {
        return sanitize(CFG.TILE_QUALITY_THRESHOLDS.get());
    }

    public static int get(String key) {
        Integer value = snapshot().get(MiningQualityCatalog.normalizeKey(key));
        return value == null ? 0 : value;
    }

    public static int anyRock() {
        Integer value = CFG.TILE_QUALITY_ANY_ROCK_THRESHOLD.get();
        return sanitizeQuality(value == null ? 0 : value);
    }

    public static void setAnyRock(int qualityX10) {
        CFG.TILE_QUALITY_ANY_ROCK_THRESHOLD.set(sanitizeQuality(qualityX10));
        TileQuality.onThresholdsChanged();
    }

    public static void set(String key, int qualityX10) {
        Map<String, Integer> values = snapshot();
        String normalized = MiningQualityCatalog.normalizeKey(key);
        if(!thresholdKey(normalized)) {throw new IllegalArgumentException("Not a stone/ore key: " + key);}
        if(qualityX10 <= 0) {
            values.remove(normalized);
        } else {
            values.put(normalized, Math.min(qualityX10, Short.MAX_VALUE));
        }
        apply(values);
    }

    public static void replace(Map<String, Integer> values) {
        apply(sanitize(values));
    }

    public static void replace(Profile profile) {
        if(profile == null) {throw new IllegalArgumentException("Missing quality settings profile");}
        CFG.TILE_QUALITY_THRESHOLDS.set(new LinkedHashMap<>(sanitize(profile.thresholds)));
        CFG.TILE_QUALITY_ANY_ROCK_THRESHOLD.set(sanitizeQuality(profile.anyRockQualityX10));
        TileQuality.onThresholdsChanged();
    }

    private static void apply(Map<String, Integer> values) {
        CFG.TILE_QUALITY_THRESHOLDS.set(new LinkedHashMap<>(values));
        TileQuality.onThresholdsChanged();
    }

    public static boolean qualifies(String key, short qualityX10) {
        return qualifies(key, qualityX10, snapshot(), anyRock());
    }

    static boolean qualifies(String key, short qualityX10, Map<String, Integer> values) {
        return qualifies(key, qualityX10, values, 0);
    }

    static boolean qualifies(String key, short qualityX10, Map<String, Integer> values, int anyRockQualityX10) {
        String normalized = MiningQualityCatalog.normalizeKey(key);
        MiningQualityCatalog.Category category = MiningQualityCatalog.categoryOf(normalized);
        if(category == MiningQualityCatalog.Category.GEM) {return qualityX10 > 0;}
        if(category != MiningQualityCatalog.Category.STONE && category != MiningQualityCatalog.Category.ORE) {
            return false;
        }
        Integer configured = sanitize(values).get(normalized);
        int threshold = configured == null ? 0 : configured;
        int anyRock = sanitizeQuality(anyRockQualityX10);
        return (anyRock > 0 && qualityX10 >= anyRock)
            || (threshold > 0 && qualityX10 >= threshold);
    }

    public static int parseQuality(String text) {
        if(text == null || text.trim().isEmpty()) {return 0;}
        double value = Double.parseDouble(text.trim());
        if(!Double.isFinite(value) || value < 0) {throw new NumberFormatException("Quality must be zero or greater");}
        return (int)Math.min(Math.round(value * 10.0), Short.MAX_VALUE);
    }

    public static String formatQuality(int qualityX10) {
        if(qualityX10 <= 0) {return "Off";}
        if(qualityX10 % 10 == 0) {return Integer.toString(qualityX10 / 10);}
        return String.format(Locale.ROOT, "%.1f", qualityX10 / 10.0);
    }

    public static String exportJson(Map<String, Integer> values) {
        return exportJson(values, anyRock());
    }

    static String exportJson(Map<String, Integer> values, int anyRockQualityX10) {
        JsonObject root = new JsonObject();
        root.addProperty("type", SHARE_TYPE);
        root.addProperty("version", SHARE_VERSION);
        root.addProperty("anyRock", sanitizeQuality(anyRockQualityX10) / 10.0);
        JsonObject thresholds = new JsonObject();
        for(Map.Entry<String, Integer> entry : sanitize(values).entrySet()) {
            thresholds.addProperty(entry.getKey(), entry.getValue() / 10.0);
        }
        root.add("thresholds", thresholds);
        return CFG.gson.toJson(root);
    }

    public static Map<String, Integer> importJson(String json) {
        return importProfileJson(json).thresholds;
    }

    public static Profile importProfileJson(String json) {
        JsonElement parsed = com.google.gson.JsonParser.parseString(json);
        if(!parsed.isJsonObject()) {throw new IllegalArgumentException("Quality settings must be a JSON object");}
        JsonObject root = parsed.getAsJsonObject();
        if(!root.has("type") || !SHARE_TYPE.equals(root.get("type").getAsString())) {
            throw new IllegalArgumentException("Clipboard does not contain Thunder quality settings");
        }
        int version = root.has("version") ? root.get("version").getAsInt() : 0;
        if(version != SHARE_VERSION) {throw new IllegalArgumentException("Unsupported quality settings version " + version);}
        if(!root.has("thresholds") || !root.get("thresholds").isJsonObject()) {
            throw new IllegalArgumentException("Quality settings have no thresholds");
        }
        Map<String, Integer> values = new LinkedHashMap<>();
        for(Map.Entry<String, JsonElement> entry : root.getAsJsonObject("thresholds").entrySet()) {
            String key = MiningQualityCatalog.normalizeKey(entry.getKey());
            if(!thresholdKey(key)) {continue;}
            double q = entry.getValue().getAsDouble();
            if(Double.isFinite(q) && q > 0) {
                values.put(key, (int)Math.min(Math.round(q * 10.0), Short.MAX_VALUE));
            }
        }
        int anyRock = 0;
        if(root.has("anyRock")) {
            double q = root.get("anyRock").getAsDouble();
            if(Double.isFinite(q) && q > 0) {
                anyRock = (int)Math.min(Math.round(q * 10.0), Short.MAX_VALUE);
            }
        }
        return new Profile(anyRock, values);
    }

    static Map<String, Integer> sanitize(Map<String, Integer> values) {
        Map<String, Integer> out = new TreeMap<>();
        if(values == null) {return out;}
        for(Map.Entry<String, Integer> entry : values.entrySet()) {
            String key = MiningQualityCatalog.normalizeKey(entry.getKey());
            Integer value = entry.getValue();
            if(thresholdKey(key) && value != null && value > 0) {
                out.put(key, Math.min(value, (int)Short.MAX_VALUE));
            }
        }
        return out;
    }

    private static boolean thresholdKey(String key) {
        MiningQualityCatalog.Category category = MiningQualityCatalog.categoryOf(key);
        return category == MiningQualityCatalog.Category.STONE || category == MiningQualityCatalog.Category.ORE;
    }

    private static int sanitizeQuality(int value) {
        return value <= 0 ? 0 : Math.min(value, (int)Short.MAX_VALUE);
    }
}
