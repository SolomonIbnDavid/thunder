package auto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MinerBotV3OverlayTest {
    @Test
    void alignedReachableLegacySupportStillRequestsVisualVerification() {
        assertEquals("Verify that the yellow legacy support belongs on the right of this heading.",
            MinerBotV3Overlay.warningFor(0, 0.5, false));
    }

    @Test
    void previewWarningExplainsEveryObservedMismatch() {
        String warning = MinerBotV3Overlay.warningFor(-2, 3.25, true);
        assertTrue(warning.contains("2 tiles left of the computed lane"));
        assertTrue(warning.contains("3.3 units off its assumed tile point"));
        assertTrue(warning.contains("computed anchor is not reachable"));
    }

    @Test
    void lockedAnchorDoesNotAskForALegacySupportCheck() {
        assertEquals("Session anchor is locked; the cyan tile will be used on Start.",
            MinerBotV3Overlay.warningFor(0, Double.NaN, false, false));
    }
}
