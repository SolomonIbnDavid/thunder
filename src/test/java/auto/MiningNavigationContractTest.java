package auto;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the documented bot-movement ownership boundary for MiningBot. */
public class MiningNavigationContractTest {
    @Test
    void allMiningTravelUsesTheCaveNavCoreBoundary() throws Exception {
        String bot = Files.readString(Path.of("src/auto/MiningBot.java"));
        String materials = Files.readString(Path.of("src/auto/MiningMaterials.java"));
        String miningSources = bot + materials;

        assertFalse(miningSources.contains("MapHelper.walkTo("),
            "MiningBot travel must not regress to direct map-click movement");
        assertTrue(bot.contains("BotMovement.Mode.CAVE"),
            "underground point travel must use the cave terrain policy");
        assertTrue(bot.contains("BotMovement.moveTo("),
            "empty tile and point travel must go through BotMovement.moveTo");
        assertTrue(bot.contains("BotMovement.moveToAny("),
            "multi-candidate zone loading must go through BotMovement.moveToAny");
        assertTrue(bot.contains("BotMovement.approach("),
            "solid supply objects must use legal-footprint BotMovement.approach");
    }
}
