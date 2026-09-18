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

    @Test
    void minerV3KeepsTravelBehindBotMovement() throws Exception {
        String bot = Files.readString(Path.of("src/auto/MinerBotV3.java"));
        String navigator = Files.readString(Path.of("src/auto/MinerBotV3Navigator.java"));
        String sources = bot + navigator;

        assertFalse(sources.contains("MapHelper.walkTo("));
        assertFalse(sources.contains("wdgmsg(\"click\""),
            "V3 must not implement travel by sending map clicks");
        assertTrue(sources.contains("BotMovement.Mode.CAVE"));
        assertTrue(sources.contains("BotMovement.moveTo("));
        assertTrue(navigator.contains("CaveRoutePlanner.planTo("),
            "off-screen strategy must use the saved-map goal planner");
    }

    @Test
    void minerV3ConsolidatesOnlyEligibleBars() throws Exception {
        String bot = Files.readString(Path.of("src/auto/MinerBotV3.java"));
        String stacker = Files.readString(Path.of("src/auto/StackAllItems.java"));
        String materials = Files.readString(Path.of("src/auto/MiningMaterials.java"));

        assertTrue(bot.contains("StackAllItems.stackMatchingNow(gui.maininv, MiningMaterials::isHardBar)"));
        assertTrue(stacker.contains("if(itemFilter != null && !itemFilter.test(w))"),
            "the synchronous consolidation scan must exclude unrelated inventory items");
        assertTrue(materials.contains(".map(w -> w.quantity.get())"),
            "minimum checks must sum item quantities rather than widget count");
    }
}
