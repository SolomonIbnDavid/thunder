package thunder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MiningQualityCatalogTest {
    @Test
    void catalogContainsEveryConfiguredMaterialFamily() {
        assertEquals(52, MiningQualityCatalog.entries(MiningQualityCatalog.Category.STONE).size());
        assertEquals(18, MiningQualityCatalog.entries(MiningQualityCatalog.Category.ORE).size());
        assertEquals(12, MiningQualityCatalog.entries(MiningQualityCatalog.Category.GEM).size());
    }

    @Test
    void displayNamesClassifyStoneAndOreSeparately() {
        assertEquals("stone/granite", MiningQualityCatalog.keyForMinedName("Granite"));
        assertEquals("ore/black-ore", MiningQualityCatalog.keyForMinedName("Black Ore, stack of"));
        assertEquals("ore/wine-glance", MiningQualityCatalog.keyForMinedName("Wine Glance"));
        assertNull(MiningQualityCatalog.keyForMinedName("Earthworm"));
    }

    @Test
    void dynamicGemNamesResolveAllCutsAndSizes() {
        assertEquals("gem/sapphire", MiningQualityCatalog.keyForGemName("Tiny Rough Sapphire"));
        assertEquals("gem/moonstone", MiningQualityCatalog.keyForGemName("Jotun Brilliant Moonstone"));
        assertEquals("gem/onyx", MiningQualityCatalog.keyForGemName("Fair Smooth Onyx"));
    }

    @Test
    void oldResourceSlugKeysMigrateToCanonicalCategories() {
        assertEquals("ore/black-ore", MiningQualityCatalog.normalizeKey("stone/magnetite"));
        assertEquals("ore/direvein", MiningQualityCatalog.normalizeKey("stone/petzite"));
        assertEquals("stone/rock-salt", MiningQualityCatalog.normalizeKey("stone/halite"));
        assertEquals("gem/ruby", MiningQualityCatalog.normalizeKey("ruby"));
        assertEquals("stone/shard-of-conch", MiningQualityCatalog.normalizeKey("shell"));
        assertEquals("stone/quarryartz", MiningQualityCatalog.normalizeKey("quartz"));
        assertEquals("stone/cat-gold", MiningQualityCatalog.normalizeKey("catgold"));
    }

    @Test
    void canonicalKeysHaveStableDisplayNames() {
        assertEquals("Iron Ochre", MiningQualityCatalog.displayName("ore/iron-ochre"));
        assertEquals("Shard of Conch", MiningQualityCatalog.displayName("stone/shard-of-conch"));
        assertEquals("Turquoise", MiningQualityCatalog.displayName("gem/turquoise"));
    }
}
