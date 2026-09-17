package thunder.combat;

import java.util.EnumSet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class CombatMoveMetadataTest {
    @Test void readsEachOpeningFromTheMovesOwnColorMetadata() {
        assertEquals(EnumSet.of(CombatAutomationRules.Opening.GREEN),
            CombatMoveMetadata.reducedOpenings(
                "Reduces: 20% · µ $col[128,255,160]{Striking}\nCooldown: 25"));
        assertEquals(EnumSet.of(CombatAutomationRules.Opening.YELLOW),
            CombatMoveMetadata.reducedOpenings(
                "Reduces: 20% · µ $col[255,255,128]{Sweeping}\nCooldown: 25"));
        assertEquals(EnumSet.of(CombatAutomationRules.Opening.RED),
            CombatMoveMetadata.reducedOpenings(
                "Reduces: 20% · µ $col[255,128,128]{Oppressive}\nCooldown: 25"));
        assertEquals(EnumSet.of(CombatAutomationRules.Opening.BLUE),
            CombatMoveMetadata.reducedOpenings(
                "Reduces: 20% · µ $col[128,192,255]{Backhanded}\nCooldown: 25"));
    }

    @Test void supportsMovesThatClearMultipleColors() {
        String text = "Reduces: 20% · µ $col[128,255,160]{Striking}\n" +
            "20% · µ $col[255,255,128]{Sweeping}\nCooldown: 35";
        assertEquals(EnumSet.of(CombatAutomationRules.Opening.GREEN,
                                CombatAutomationRules.Opening.YELLOW),
            CombatMoveMetadata.reducedOpenings(text));
    }

    @Test void readsLocalizedMetadataAndOptionalColorAlpha() {
        String text = "Réductions : 10% - µ $col[128,192,255]{Amélioration}, " +
            "10% - µ $col[255,128,128,128]{Suppression}\\n" +
            "Brèches de l'adversaire : +15% $col[128,192,255]{assommer}";
        assertEquals(EnumSet.of(CombatAutomationRules.Opening.BLUE,
                                CombatAutomationRules.Opening.RED),
            CombatMoveMetadata.reducedOpenings(text));
    }

    @Test void ignoresOpeningColorsThatAreNotMarkedAsReductions() {
        String text = "破防降低： 30% • µ $col[128,255,160]{绿攻}, " +
            "30% • µ $col[255,128,128]{红攻}\\n" +
            "破防度: +10% $col[128,192,255]{蓝攻}, +10% $col[255,255,128]{黄攻}";
        assertEquals(EnumSet.of(CombatAutomationRules.Opening.GREEN,
                                CombatAutomationRules.Opening.RED),
            CombatMoveMetadata.reducedOpenings(text));
    }

    @Test void doesNotMistakeAnAttackForAnOpeningClear() {
        assertEquals(EnumSet.noneOf(CombatAutomationRules.Opening.class),
            CombatMoveMetadata.reducedOpenings(
                "Attack type: $col[128,192,255]{Backhanded}\nOpenings: +15% Dizzy\nCooldown: 40"));
    }
}
