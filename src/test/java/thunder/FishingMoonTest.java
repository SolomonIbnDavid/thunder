package thunder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class FishingMoonTest {
    @Test
    void phaseNames() {
	assertEquals("Full Moon", FishingMoon.phaseName(0.5));
	assertEquals("New Moon", FishingMoon.phaseName(0.0));
    }

    @Test
    void waxingBecomesStaleAtFull() {
	assertFalse(FishingMoon.staleAfterFullMoon(0.40, 0.45));
	assertTrue(FishingMoon.staleAfterFullMoon(0.40, 0.50));
    }

    @Test
    void pinOnFullMoonWaitsForNext() {
	assertFalse(FishingMoon.staleAfterFullMoon(0.50, 0.50));
	assertFalse(FishingMoon.staleAfterFullMoon(0.50, 0.60));
	assertFalse(FishingMoon.staleAfterFullMoon(0.50, 0.40));
	assertTrue(FishingMoon.staleAfterFullMoon(0.51, 0.50));
    }

    @Test
    void sameMomentNotStaleUnlessWrapped() {
	assertFalse(FishingMoon.staleAfterFullMoon(0.20, 0.20));
    }
}
