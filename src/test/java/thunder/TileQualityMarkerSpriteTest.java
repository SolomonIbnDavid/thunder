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
    void resolvesOreAliasesAndDynamicGemResource() {
        assertEquals("gfx/invobjs/magnetite",
            TileQualityMarkerSprite.parse("[TQ] Black Ore q81").resourceName);
        assertEquals("gfx/invobjs/gems/gemstone",
            TileQualityMarkerSprite.parse("[TQ] Sapphire q12.3").resourceName);
    }

    @Test
    void rejectsOrdinaryAndMalformedMarkers() {
        assertNull(TileQualityMarkerSprite.parse("Home"));
        assertNull(TileQualityMarkerSprite.parse("[TQ] Granite"));
        assertNull(TileQualityMarkerSprite.parse("[TQ] Granite qnope"));
        assertNull(TileQualityMarkerSprite.parse("[TQ] Granite q0"));
    }
}
