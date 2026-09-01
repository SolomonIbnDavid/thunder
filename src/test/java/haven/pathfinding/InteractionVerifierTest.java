package haven.pathfinding;

import haven.Coord2d;
import haven.nav.InteractionSpec;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class InteractionVerifierTest {
   @Test
   void forageSuccessRequiresDisappearanceOrInventory() {
      Assertions.assertTrue(InteractionVerifier.confirmed(InteractionVerifier.TARGET_GONE, false, false, false, false));
      Assertions.assertTrue(InteractionVerifier.confirmed(InteractionVerifier.TARGET_GONE, true, true, false, false));
      Assertions.assertFalse(InteractionVerifier.confirmed(InteractionVerifier.TARGET_GONE, true, false, false, false));
   }

   @Test
   void containerSuccessRequiresWindow() {
      Assertions.assertTrue(InteractionVerifier.confirmed(InteractionVerifier.WINDOW_OPENED, true, false, true, false));
      Assertions.assertFalse(InteractionVerifier.confirmed(InteractionVerifier.WINDOW_OPENED, true, false, false, false));
      Assertions.assertFalse(InteractionVerifier.confirmed(InteractionVerifier.WINDOW_OPENED, false, false, false, false));
   }

   @Test
   void doorSuccessRequiresStateChange() {
      Assertions.assertTrue(InteractionVerifier.confirmed(InteractionVerifier.STATE_CHANGED, true, false, false, true));
      Assertions.assertFalse(InteractionVerifier.confirmed(InteractionVerifier.STATE_CHANGED, true, false, false, false));
   }

   @Test
   void standingBesideIsNotSuccess() {
      Assertions.assertFalse(InteractionVerifier.confirmed(InteractionVerifier.STATE_CHANGED, true, false, false, false));
      Assertions.assertFalse(InteractionVerifier.mayInteract(false));
   }

   @Test
   void noInteractionBeforeAuthoritativeArrival() {
      Assertions.assertFalse(InteractionVerifier.mayInteract(false));
      Assertions.assertTrue(InteractionVerifier.mayInteract(true));
      Assertions.assertFalse(InteractionVerifier.arrivedConfirmed(false, Coord2d.of(0, 0), Coord2d.of(0, 0), 2.475));
      Assertions.assertTrue(InteractionVerifier.arrivedConfirmed(true, Coord2d.of(1, 0), Coord2d.of(1.5, 0), 2.475));
   }

   @Test
   void targetGoneAndFootprintChangeInvalidatePose() {
      InteractionSpec spec = new InteractionSpec("t", Coord2d.of(10, 10), Coord2d.of(5.5, 5.5), InteractionSpec.ALL_SIDES, 0.5, 16.5, 0, null, "window_opened");
      Coord2d pose = Coord2d.of(20, 10);
      Assertions.assertFalse(InteractionVerifier.poseStillValid(pose, pose, 2.475, spec, spec.origin, spec.half, false));
      Assertions.assertFalse(InteractionVerifier.poseStillValid(pose, pose, 2.475, spec, Coord2d.of(40, 10), spec.half, true));
      Assertions.assertTrue(InteractionVerifier.poseStillValid(pose, pose, 2.475, spec, spec.origin, spec.half, true));
   }

   @Test
   void boundedRetryAndTimeout() {
      Assertions.assertTrue(InteractionVerifier.retryAllowed(0));
      Assertions.assertTrue(InteractionVerifier.retryAllowed(1));
      Assertions.assertFalse(InteractionVerifier.retryAllowed(2));
      Assertions.assertTrue(InteractionVerifier.timedOut(2500L, 2500L));
      Assertions.assertFalse(InteractionVerifier.timedOut(100L, 2500L));
   }
}
