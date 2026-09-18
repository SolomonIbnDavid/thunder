package thunder;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TileQualityThresholdsTest {
    @Test
    void qualityTextUsesTenthsWithoutNoise() {
        assertEquals(0, TileQualityThresholds.parseQuality(""));
        assertEquals(0, TileQualityThresholds.parseQuality("0"));
        assertEquals(427, TileQualityThresholds.parseQuality("42.7"));
        assertEquals("Off", TileQualityThresholds.formatQuality(0));
        assertEquals("50", TileQualityThresholds.formatQuality(500));
        assertEquals("42.7", TileQualityThresholds.formatQuality(427));
        assertThrows(NumberFormatException.class, () -> TileQualityThresholds.parseQuality("-1"));
    }

    @Test
    void thresholdQualificationIsInclusiveAndGemsAreAlwaysImportant() {
        Map<String, Integer> values = new LinkedHashMap<>();
        values.put("stone/granite", 500);
        values.put("ore/black-ore", 725);

        assertFalse(TileQualityThresholds.qualifies("stone/granite", (short)499, values));
        assertTrue(TileQualityThresholds.qualifies("stone/granite", (short)500, values));
        assertFalse(TileQualityThresholds.qualifies("ore/black-ore", (short)724, values));
        assertTrue(TileQualityThresholds.qualifies("ore/black-ore", (short)725, values));
        assertTrue(TileQualityThresholds.qualifies("gem/opal", (short)1, values));
        assertFalse(TileQualityThresholds.qualifies(TileQuality.KEY_CRYSTAL, (short)900, values));
    }

    @Test
    void sharePayloadRoundTripsAndNormalizesLegacyKeys() {
        Map<String, Integer> values = new LinkedHashMap<>();
        values.put("stone/magnetite", 613);
        values.put("stone/granite", 500);
        values.put("gem/ruby", 100); // gems have no configurable threshold

        String json = TileQualityThresholds.exportJson(values);
        Map<String, Integer> imported = TileQualityThresholds.importJson(json);

        assertEquals(2, imported.size());
        assertEquals(613, imported.get("ore/black-ore"));
        assertEquals(500, imported.get("stone/granite"));
        assertFalse(imported.containsKey("gem/ruby"));
    }

    @Test
    void rejectsUnrelatedClipboardJson() {
        assertThrows(IllegalArgumentException.class,
            () -> TileQualityThresholds.importJson("{\"type\":\"something-else\",\"version\":1,\"thresholds\":{}}"));
    }
}
