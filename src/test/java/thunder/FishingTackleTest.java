package thunder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class FishingTackleTest {
    @Test
    void pikeLookup() {
	FishingTackle.Combo c = FishingTackle.suggest("pike");
	assertNotNull(c);
	assertEquals("Pike", c.fish);
	assertTrue(c.lures.contains("lobster"));
	assertFalse(c.wikiW10);
    }

    @Test
    void rosefishAlias() {
	assertNotNull(FishingTackle.suggest("Rose Fish"));
	assertEquals("Rosefish", FishingTackle.suggest("rosefish").fish);
    }

    @Test
    void serverRowColonDoesNotChangeSpecies() {
	assertEquals("eel", FishingTackle.norm("Eel:"));
	assertNotNull(FishingTackle.suggest("Eel:"));
    }

    @Test
    void wikiW10Flagged() {
	FishingTackle.Combo c = FishingTackle.suggest("Cod");
	assertTrue(c.wikiW10);
	assertTrue(c.hint().contains("W10"));
    }

    @Test
    void unknownIsNull() {
	assertNull(FishingTackle.suggest("Troutfish"));
	assertNull(FishingTackle.suggest(""));
    }
}
