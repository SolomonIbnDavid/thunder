package thunder.combat;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CombatMoveCatalogTest {
    @Test void containsTheCompleteKnownMoveCatalog() {
        assertEquals(42, CombatMoveCatalog.known().size());
        assertEquals("Quick Barrage", CombatMoveCatalog.label("paginae/atk/barrage"));
        assertEquals("Cleave", CombatMoveCatalog.label("paginae/atk/cleave"));
    }

    @Test void marksKnownDistanceMovesAndTreatsUnknownMovesAsMelee() {
        assertTrue(CombatMoveCatalog.distanceCapable("paginae/atk/dash"));
        assertTrue(CombatMoveCatalog.distanceCapable("paginae/atk/artevade"));
        assertTrue(CombatMoveCatalog.distanceCapable("paginae/atk/zigzag"));
        assertFalse(CombatMoveCatalog.distanceCapable("paginae/atk/cleave"));
        assertFalse(CombatMoveCatalog.distanceCapable("paginae/atk/newmove"));
    }

    @Test void mergesUnknownDeckMovesWithoutDuplicatingKnownOnes() {
        List<CombatMoveCatalog.Entry> merged = CombatMoveCatalog.withDiscovered(Collections.singletonList(
            CombatMoveCatalog.discovered("paginae/atk/newmove", "Localized New Move")));
        assertEquals(43, merged.size());
        assertEquals("Localized New Move", merged.stream()
            .filter(entry -> entry.resource.equals("paginae/atk/newmove"))
            .findFirst().get().label);
    }
}
