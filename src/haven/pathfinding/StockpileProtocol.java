package haven.pathfinding;

/**
 * Fail-closed seam for the real stockpile creation wire sequence. It has no
 * UI dependencies, so illegal acknowledgements are testable without a game.
 */
public final class StockpileProtocol {
   public enum State { IDLE, HELD, ITEMACT_SENT, FLOWER_OPEN, FLOWER_SELECTED, PLACE_SENT, ACKNOWLEDGED, FAILED }
   public enum Signal { HELD_CONFIRMED, ITEMACT_ACK, FLOWER_OPEN, FLOWER_SELECTED, PLACE_ACK, TIMEOUT, CANCELLED }

   private State state = State.IDLE;
   private String failure;

   public State state() { return state; }
   public String failure() { return failure; }

   public boolean accept(Signal signal) {
      if (signal == null || state == State.FAILED || state == State.ACKNOWLEDGED) return false;
      switch (signal) {
         case HELD_CONFIRMED:
            return move(State.IDLE, State.HELD);
         case ITEMACT_ACK:
            return move(State.HELD, State.ITEMACT_SENT);
         case FLOWER_OPEN:
            return move(State.ITEMACT_SENT, State.FLOWER_OPEN);
         case FLOWER_SELECTED:
            return move(State.FLOWER_OPEN, State.FLOWER_SELECTED);
         case PLACE_ACK:
            return move(State.PLACE_SENT, State.ACKNOWLEDGED);
         case TIMEOUT:
            return fail("timeout while " + state);
         case CANCELLED:
            return fail("cancelled while " + state);
         default:
            return false;
      }
   }

   /** Marks the outbound place message only after an actual preview exists. */
   public boolean placeSent() {
      return move(State.FLOWER_SELECTED, State.PLACE_SENT);
   }

   public boolean fail(String reason) {
      if (state == State.ACKNOWLEDGED || state == State.FAILED) return false;
      state = State.FAILED;
      failure = reason == null || reason.isEmpty() ? "protocol failure" : reason;
      return true;
   }

   private boolean move(State expected, State next) {
      if (state != expected) return false;
      state = next;
      return true;
   }
}
