package thunder.cellar;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CellarDiggerRulesTest {
    @Test public void recognizesOnlyTheExactCellarDoor() {
        assertTrue(CellarDiggerRules.isCellarDoor("gfx/terobjs/arch/cellardoor"));
        assertTrue(CellarDiggerRules.isCellarDoor("gfx/terobjs/arch/cellardoor[3]"));
        assertFalse(CellarDiggerRules.isCellarDoor("gfx/terobjs/arch/cellarstairs"));
        assertFalse(CellarDiggerRules.isCellarDoor("gfx/terobjs/arch/cellardoorstep"));
        assertFalse(CellarDiggerRules.isCellarDoor("gfx/terobjs/arch/cellardoors"));
    }

    @Test public void recognizesAllCellarTransitionsAndNoNearMisses() {
        assertTrue(CellarDiggerRules.isCellarTransition("gfx/terobjs/arch/cellarstairs"));
        assertTrue(CellarDiggerRules.isCellarTransition("gfx/terobjs/arch/downstairs[1]"));
        assertTrue(CellarDiggerRules.isCellarTransition("gfx/terobjs/arch/upstairs"));
        assertFalse(CellarDiggerRules.isCellarTransition("gfx/terobjs/arch/cellardoor"));
        assertFalse(CellarDiggerRules.isCellarTransition("gfx/terobjs/arch/downstairs-extra"));
    }

    @Test public void acceptsOnlyStagedBumlings() {
        assertTrue(CellarDiggerRules.isBumling("gfx/terobjs/bumlings/rhyolite0"));
        assertTrue(CellarDiggerRules.isBumling("gfx/terobjs/bumlings/feldspar1[2]"));
        assertTrue(CellarDiggerRules.isBumling("GFX/TEROBJS/BUMLINGS/CINNABAR1"));
        assertFalse(CellarDiggerRules.isBumling("gfx/terobjs/bumlings/"));
        assertFalse(CellarDiggerRules.isBumling("gfx/terobjs/boulder"));
        assertFalse(CellarDiggerRules.isBumling("gfx/terobjs/items/granite"));
        assertFalse(CellarDiggerRules.isBumling("gfx/invobjs/rhyolite"));
    }

    @Test public void meterBoundariesAreExact() {
        assertTrue(CellarDiggerRules.energyTooLow(0.2499));
        assertFalse(CellarDiggerRules.energyTooLow(0.25));
        assertTrue(CellarDiggerRules.staminaNeedsRecovery(0.3999));
        assertFalse(CellarDiggerRules.staminaNeedsRecovery(0.40));
        assertFalse(CellarDiggerRules.staminaRecovered(0.7999));
        assertTrue(CellarDiggerRules.staminaRecovered(0.80));
        assertFalse(CellarDiggerRules.autoDrinkThresholdSafe(39));
        assertTrue(CellarDiggerRules.autoDrinkThresholdSafe(40));
    }

    @Test public void doorOutcomeRequiresAnAuthoritativeSignal() {
        assertEquals(CellarDiggerRules.DoorOutcome.CONTINUE,
            CellarDiggerRules.doorOutcome(true, true, false));
        assertEquals(CellarDiggerRules.DoorOutcome.COMPLETE,
            CellarDiggerRules.doorOutcome(false, false, true));
        assertEquals(CellarDiggerRules.DoorOutcome.RETRY,
            CellarDiggerRules.doorOutcome(false, true, false));
        assertEquals(CellarDiggerRules.DoorOutcome.RETRY,
            CellarDiggerRules.doorOutcome(false, false, false));
        assertEquals(CellarDiggerRules.DoorOutcome.RETRY,
            CellarDiggerRules.doorOutcome(false, true, true));
    }

    @Test public void safetyCapsStayBounded() {
        assertEquals(3, CellarDiggerRules.MAX_ATTEMPTS);
        assertEquals(500, CellarDiggerRules.MAX_CHIPS_PER_BOULDER);
        assertEquals(128, CellarDiggerRules.MAX_DOOR_CYCLES);
    }

    @Test public void groundedBoulderRequiresThreeStableSamples() {
        assertFalse(CellarDiggerRules.groundedBoulderStable(0));
        assertFalse(CellarDiggerRules.groundedBoulderStable(2));
        assertTrue(CellarDiggerRules.groundedBoulderStable(3));
        assertTrue(CellarDiggerRules.groundedBoulderStable(4));
    }
}
