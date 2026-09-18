package thunder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TileQualityMarkerSpriteTest {
    @Test
    void parsesStoneIconAndQualityFromPersistentMarkerName() {
        TileQualityMarkerSprite.Visual visual = TileQualityMarkerSprite.parse("[TQ] Granite q72.5");
        assertNotNull(visual);
        assertEquals("Granite", visual.materialName);
        assertEquals("gfx/invobjs/granite", visual.resourceName);
        assertEquals("72.5", visual.qualityText);
    }

    @Test
    void resolvesOreAliasesAndDynamicGemResources() {
        assertEquals("gfx/invobjs/magnetite",
            TileQualityMarkerSprite.parse("[TQ] Black Ore q81").resourceName);
        TileQualityMarkerSprite.Visual sapphire =
            TileQualityMarkerSprite.parse("[TQ] Sapphire q12.3");
        assertEquals("gfx/invobjs/gems/gemstone", sapphire.resourceName);
        assertEquals("gfx/terobjs/bumlings/sapphire", sapphire.gemTextureResourceName);
    }

    @Test
    void everyGemTypeUsesItsSpecificGameTexture() {
        for(MiningQualityCatalog.Entry entry :
                MiningQualityCatalog.entries(MiningQualityCatalog.Category.GEM)) {
            TileQualityMarkerSprite.Visual visual =
                TileQualityMarkerSprite.parse("[TQ] " + entry.name + " q1");
            assertNotNull(visual, entry.name);
            assertEquals("gfx/terobjs/bumlings/" + entry.name.toLowerCase().replace(" ", ""),
                visual.gemTextureResourceName, entry.name);
        }
    }

    @Test
    void rejectsOrdinaryAndMalformedMarkers() {
        assertNull(TileQualityMarkerSprite.parse("Home"));
        assertNull(TileQualityMarkerSprite.parse("[TQ] Granite"));
        assertNull(TileQualityMarkerSprite.parse("[TQ] Granite qnope"));
        assertNull(TileQualityMarkerSprite.parse("[TQ] Granite q0"));
    }
}
