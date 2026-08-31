package haven.nav;

import haven.Coord2d;

public final class NavDecision {
   public enum Kind {
      WAIT,
      SEND_MOVEMENT,
      INTERACT,
      REPLAN,
      TRANSITION,
      TERMINATE;
   }

   public final Kind kind;
   public final Coord2d target;
   public final String reason;

   public NavDecision(Kind kind, Coord2d target, String reason) {
      if (kind == null) {
         throw new IllegalArgumentException("kind");
      }
      this.kind = kind;
      this.target = target;
      this.reason = reason == null ? "" : reason;
   }

   public static NavDecision wait(String reason) {
      return new NavDecision(Kind.WAIT, null, reason);
   }

   public static NavDecision sendMovement(Coord2d target, String reason) {
      return new NavDecision(Kind.SEND_MOVEMENT, target, reason);
   }

   public static NavDecision replan(String reason) {
      return new NavDecision(Kind.REPLAN, null, reason);
   }

   public static NavDecision interact(Coord2d target, String reason) {
      return new NavDecision(Kind.INTERACT, target, reason);
   }

   public static NavDecision transition(String reason) {
      return new NavDecision(Kind.TRANSITION, null, reason);
   }

   public static NavDecision terminate(String reason) {
      return new NavDecision(Kind.TERMINATE, null, reason);
   }
}
