package haven.pathfinding;

import haven.Coord;
import haven.Coord2d;
import haven.nav.NavDecision;
import haven.nav.NavObservation;
import haven.nav.NavOutcome;
import haven.nav.NavPlan;
import haven.nav.NavPlanStatus;
import java.util.ArrayList;
import java.util.List;

/**
 * Streaming surface executor. Picks the farthest legal smoothed waypoint,
 * hands off without idle on intermediate legs, and runs bounded recovery.
 * Does not talk to the game; Thunder only executes the returned decisions.
 */
public final class SurfaceController {
   public static final int MAX_RECOVERY = SurfaceStream.MAX_RECOVERY;

   public final Coord2d originalGoal;
   public final boolean requireIdleForFinal;
   public final double eps;
   public final double moveProgress;
   public final long startTimeoutMs;
   public final long walkTimeoutMs;
   public final long jiggleWindowMs;

   private List<Coord2d> smoothed;
   private NavPlanStatus planStatus;
   private OccupancyGrid occupancy;
   private Coord2d lastSent;
   private Coord2d sentBeforeLast;
   private Coord2d lastPos;
   private long lastT;
   private boolean lastMoving;
   private boolean snapshotPending;
   private long vehicle0 = Long.MIN_VALUE;
   private boolean passenger0;
   private Coord2d lastProgressPos;
   private long lastProgressT;
   private long t0 = -1L;
   private int recoveryCount;
   private boolean awaitingEscape;
   private Coord2d escapeTarget;
   private Coord2d failedFrom;
   private Coord2d failedTo;
   private Coord2d lastHorizonPos;
   private SurfaceStream.Farthest lastPick;
   private SurfaceStream.Reason lastReason = SurfaceStream.Reason.STREAM;
   private long planMs;

   public SurfaceController(
      Coord2d originalGoal,
      List<Coord2d> smoothed,
      NavPlanStatus planStatus,
      double eps,
      double moveProgress,
      long startTimeoutMs,
      long walkTimeoutMs,
      long jiggleWindowMs,
      boolean requireIdleForFinal
   ) {
      if (originalGoal == null) {
         throw new IllegalArgumentException("originalGoal");
      }
      this.originalGoal = originalGoal;
      this.smoothed = smoothed == null ? new ArrayList<Coord2d>() : new ArrayList<Coord2d>(smoothed);
      this.planStatus = planStatus == null ? NavPlanStatus.REACHED : planStatus;
      this.eps = eps;
      this.moveProgress = moveProgress;
      this.startTimeoutMs = startTimeoutMs;
      this.walkTimeoutMs = walkTimeoutMs;
      this.jiggleWindowMs = jiggleWindowMs;
      this.requireIdleForFinal = requireIdleForFinal;
   }

   public static SurfaceController of(Coord2d goal, List<Coord2d> smoothed, NavPlanStatus status, double eps, double moveProgress, long startTimeoutMs, long walkTimeoutMs, long jiggleWindowMs) {
      Coord2d g = goal;
      if (g == null && smoothed != null && !smoothed.isEmpty()) {
         g = smoothed.get(smoothed.size() - 1);
      }
      return new SurfaceController(g, smoothed, status, eps, moveProgress, startTimeoutMs, walkTimeoutMs, jiggleWindowMs, true);
   }

   public SurfaceStream.Farthest lastPick() {
      return this.lastPick;
   }

   public int recoveryCount() {
      return this.recoveryCount;
   }

   public SurfaceStream.Reason lastReason() {
      return this.lastReason;
   }

   public Coord2d lastSent() {
      return this.lastSent;
   }

   public long lastPlanMs() {
      return this.planMs;
   }

   public Tick step(NavObservation obs, OccupancyGrid occ) {
      if (obs == null || obs.position == null) {
         return terminate(NavOutcome.UNAVAILABLE, SurfaceStream.Reason.STUCK, "no_observation");
      }
      if (obs.cancelled) {
         return terminate(NavOutcome.CANCELLED, SurfaceStream.Reason.CANCELLED, "cancelled");
      }
      this.lastT = obs.tMs;
      if (this.vehicle0 == Long.MIN_VALUE) {
         this.vehicle0 = obs.vehicleId;
         this.passenger0 = obs.passenger;
         this.lastPos = obs.position;
         this.lastMoving = obs.moving;
         this.lastProgressPos = obs.position;
         this.lastProgressT = obs.tMs;
      } else if (obs.vehicleId != this.vehicle0 || obs.passenger != this.passenger0) {
         this.vehicle0 = obs.vehicleId;
         this.passenger0 = obs.passenger;
         this.lastPos = obs.position;
         return replan(SurfaceStream.Reason.MOBILITY_CHANGED, "mobility_changed");
      }
      OccupancyGrid prevOcc = this.occupancy;
      if (occ != null) {
         this.occupancy = occ;
      }
      Coord2d pos = obs.position;
      if (this.snapshotPending) {
         this.snapshotPending = false;
         this.lastPos = pos;
         return recoverContinue(pos, this.lastReason, this.lastReason == null ? "recovery" : this.lastReason.name());
      }
      if (SurfaceStream.arrived(pos, this.originalGoal, this.eps, obs.moving, this.requireIdleForFinal)) {
         this.lastPos = pos;
         return terminate(NavOutcome.REACHED, SurfaceStream.Reason.ARRIVED, "server_confirmed");
      }
      if (this.awaitingEscape && this.escapeTarget != null && pos.dist(this.escapeTarget) <= this.eps && !obs.moving) {
         this.awaitingEscape = false;
         this.lastPos = pos;
         return replanOriginal(pos, SurfaceStream.Reason.RECOVERY, "escape_complete");
      }
      if (this.lastSent != null && prevOcc != null && this.occupancy != null && this.lastSent != null) {
         if (SurfaceStream.corridorChanged(prevOcc, this.occupancy, pos, this.lastSent)) {
            this.lastPos = pos;
            return recover(pos, SurfaceStream.Reason.OBSTACLE_CHANGED, "obstacle_changed");
         }
         if (!SurfaceStream.corridorClear(pos, this.lastSent, this.occupancy)) {
            this.lastPos = pos;
            return recover(pos, SurfaceStream.Reason.CORRIDOR_INVALID, "corridor_invalid");
         }
      }
      if (this.lastSent != null && obs.moving && this.lastProgressPos != null) {
         double off = distToSegment(pos, this.lastProgressPos, this.lastSent);
         if (off > SurfaceStream.DEVIATION) {
            this.lastPos = pos;
            return recover(pos, SurfaceStream.Reason.DEVIATED, "deviated");
         }
      }
      if (this.t0 >= 0L && obs.tMs - this.t0 >= this.walkTimeoutMs) {
         this.lastPos = pos;
         return terminate(NavOutcome.BUDGET_EXHAUSTED, SurfaceStream.Reason.WALK_TIMEOUT, "walk_timeout");
      }
      if (this.lastSent != null && obs.moving) {
         double moved = this.lastProgressPos == null ? 0.0 : pos.dist(this.lastProgressPos);
         if (moved >= this.moveProgress) {
            this.lastProgressT = obs.tMs;
            this.lastProgressPos = pos;
         } else if (obs.tMs - this.lastProgressT >= this.jiggleWindowMs) {
            this.lastPos = pos;
            return recover(pos, SurfaceStream.Reason.NO_PROGRESS, "no_progress");
         }
      }
      if (this.lastSent != null && this.lastMoving && !obs.moving && pos.dist(this.originalGoal) > this.eps) {
         this.lastPos = pos;
         return recover(pos, SurfaceStream.Reason.STOPPED_EARLY, "stopped_early");
      }
      if (this.lastSent != null && !obs.moving && this.t0 >= 0L && pos.dist(this.lastSent) > this.eps && pos.dist(this.lastProgressPos == null ? pos : this.lastProgressPos) < this.moveProgress && obs.tMs - this.t0 >= this.startTimeoutMs) {
         this.lastPos = pos;
         return terminate(NavOutcome.UNAVAILABLE, SurfaceStream.Reason.START_TIMEOUT, "start_timeout");
      }
      SurfaceStream.Farthest pick = SurfaceStream.farthestValid(pos, this.smoothed, this.occupancy);
      this.lastPick = pick;
      if (pick.selected == null) {
         this.lastPos = pos;
         return recover(pos, SurfaceStream.Reason.CORRIDOR_INVALID, pick.whyShorter == null ? "no_legal_corridor" : pick.whyShorter);
      }
      if (!this.smoothed.isEmpty()
         && SurfaceStream.wouldBeFalseSuccess(this.planStatus, pos, this.originalGoal, this.eps)
         && pos.dist(this.smoothed.get(this.smoothed.size() - 1)) <= this.eps
         && !obs.moving) {
         this.lastPos = pos;
         if (this.lastHorizonPos != null && pos.dist(this.lastHorizonPos) <= this.eps) {
            return terminate(NavOutcome.BLOCKED, SurfaceStream.Reason.HORIZON_ADVANCE, "horizon_is_not_success");
         }
         this.lastHorizonPos = pos;
         return replanOriginal(pos, SurfaceStream.Reason.HORIZON_ADVANCE, "horizon_is_not_success");
      }
      if (SurfaceStream.sameCommand(this.lastSent, pick.selected)) {
         this.lastPos = pos;
         this.lastMoving = obs.moving;
         this.lastReason = pick.whyShorter == null ? SurfaceStream.Reason.STREAM : SurfaceStream.Reason.HANDOFF;
         return new Tick(NavDecision.wait(this.lastReason.name()), this.lastReason, pick, this.recoveryCount, this.planMs, null);
      }
      if (SurfaceStream.abaOscillation(this.sentBeforeLast, this.lastSent, pick.selected, this.eps)) {
         this.lastPos = pos;
         return terminate(NavOutcome.STUCK, SurfaceStream.Reason.STUCK, "aba_oscillation");
      }
      boolean handoff = this.lastSent != null && obs.moving;
      this.sentBeforeLast = this.lastSent;
      this.lastSent = pick.selected;
      if (this.t0 < 0L) {
         this.t0 = obs.tMs;
      }
      this.lastPos = pos;
      this.lastMoving = obs.moving;
      this.lastReason = handoff ? SurfaceStream.Reason.HANDOFF : SurfaceStream.Reason.STREAM;
      return new Tick(NavDecision.sendMovement(pick.selected, this.lastReason.name()), this.lastReason, pick, this.recoveryCount, this.planMs, null);
   }

   public void acceptPlan(NavPlan plan) {
      if (plan != null && plan.smoothedRoute != null && plan.smoothedRoute.size() >= 2) {
         this.smoothed = new ArrayList<Coord2d>(plan.smoothedRoute);
         this.planStatus = plan.status;
      }
   }

   private Tick recover(Coord2d pos, SurfaceStream.Reason reason, String detail) {
      this.lastReason = reason;
      this.failedFrom = this.lastProgressPos != null ? this.lastProgressPos : pos;
      this.failedTo = this.lastSent != null ? this.lastSent : this.originalGoal;
      if (this.occupancy == null) {
         if (reason == SurfaceStream.Reason.STOPPED_EARLY || reason == SurfaceStream.Reason.NO_PROGRESS) {
            return new Tick(NavDecision.replan(reason.name()), reason, this.lastPick, this.recoveryCount, this.planMs, null);
         }
         return terminate(NavOutcome.UNAVAILABLE, reason, detail);
      }
      if (this.recoveryCount >= MAX_RECOVERY) {
         return terminate(NavOutcome.STUCK, SurfaceStream.Reason.STUCK, "recovery_exhausted");
      }
      this.snapshotPending = true;
      return new Tick(NavDecision.replan(reason.name()), reason, this.lastPick, this.recoveryCount, this.planMs, null);
   }

   private Tick recoverContinue(Coord2d pos, SurfaceStream.Reason reason, String detail) {
      this.lastReason = reason == null ? SurfaceStream.Reason.RECOVERY : reason;
      if (this.occupancy == null) {
         return recover(pos, this.lastReason, detail);
      }
      if (this.recoveryCount >= MAX_RECOVERY) {
         return terminate(NavOutcome.STUCK, SurfaceStream.Reason.STUCK, "recovery_exhausted");
      }
      this.recoveryCount++;
      long t0ns = System.nanoTime();
      OccupancyGrid black = SurfaceStream.blacklistSegment(this.occupancy, this.failedFrom, this.failedTo);
      NavPlan planned = SurfaceStream.replan(pos, this.originalGoal, this.occupancy, black);
      this.planMs = (System.nanoTime() - t0ns) / 1000000L;
      if (planned != null && planned.smoothedRoute != null && planned.smoothedRoute.size() >= 2) {
         this.smoothed = new ArrayList<Coord2d>(planned.smoothedRoute);
         this.planStatus = planned.status;
         Coord2d prevSent = this.lastSent;
         this.lastSent = null;
         this.awaitingEscape = false;
         SurfaceStream.Farthest pick = SurfaceStream.farthestValid(pos, this.smoothed, this.occupancy);
         this.lastPick = pick;
         if (pick.selected != null && !SurfaceStream.abaOscillation(this.sentBeforeLast, prevSent, pick.selected, this.eps)) {
            return sendRecovery(pick.selected, pick, null, prevSent);
         }
      }
      Coord escape = SurfaceStream.pickEscape(this.occupancy, pos);
      if (escape != null) {
         Coord2d world = this.occupancy.world(escape.x, escape.y);
         if (SurfaceStream.abaOscillation(this.sentBeforeLast, this.lastSent, world, this.eps)) {
            return terminate(NavOutcome.STUCK, SurfaceStream.Reason.STUCK, "aba_oscillation");
         }
         this.awaitingEscape = true;
         this.escapeTarget = world;
         return sendRecovery(world, this.lastPick, escape, this.lastSent);
      }
      if (this.recoveryCount >= MAX_RECOVERY) {
         return terminate(NavOutcome.STUCK, SurfaceStream.Reason.STUCK, "recovery_exhausted");
      }
      NavPlan original = SurfaceStream.replan(pos, this.originalGoal, this.occupancy, null);
      if (original != null && original.smoothedRoute != null && original.smoothedRoute.size() >= 2) {
         this.smoothed = new ArrayList<Coord2d>(original.smoothedRoute);
         this.planStatus = original.status;
         this.lastSent = null;
         return new Tick(NavDecision.replan(SurfaceStream.Reason.RECOVERY.name()), SurfaceStream.Reason.RECOVERY, this.lastPick, this.recoveryCount, this.planMs, null);
      }
      return terminate(NavOutcome.STUCK, SurfaceStream.Reason.STUCK, "recovery_exhausted");
   }

   private Tick sendRecovery(Coord2d target, SurfaceStream.Farthest pick, Coord escape, Coord2d prevSent) {
      this.sentBeforeLast = prevSent;
      this.lastSent = target;
      this.lastMoving = false;
      this.t0 = this.lastT;
      this.lastProgressPos = this.lastPos;
      this.lastProgressT = this.lastT;
      this.lastReason = SurfaceStream.Reason.RECOVERY;
      return new Tick(NavDecision.sendMovement(target, SurfaceStream.Reason.RECOVERY.name()), SurfaceStream.Reason.RECOVERY, pick, this.recoveryCount, this.planMs, escape);
   }

   private Tick replanOriginal(Coord2d pos, SurfaceStream.Reason reason, String detail) {
      this.lastReason = reason;
      long t0ns = System.nanoTime();
      NavPlan planned = SurfaceStream.replan(pos, this.originalGoal, this.occupancy, null);
      this.planMs = (System.nanoTime() - t0ns) / 1000000L;
      if (planned != null && planned.smoothedRoute != null && planned.smoothedRoute.size() >= 2) {
         this.smoothed = new ArrayList<Coord2d>(planned.smoothedRoute);
         this.planStatus = planned.status;
         this.lastSent = null;
         return new Tick(NavDecision.replan(reason.name()), reason, this.lastPick, this.recoveryCount, this.planMs, null);
      }
      if (this.recoveryCount >= MAX_RECOVERY) {
         return terminate(NavOutcome.STUCK, SurfaceStream.Reason.STUCK, detail);
      }
      return recover(pos, reason, detail);
   }

   private Tick replan(SurfaceStream.Reason reason, String detail) {
      this.lastReason = reason;
      return new Tick(NavDecision.replan(reason.name()), reason, this.lastPick, this.recoveryCount, this.planMs, null);
   }

   private Tick terminate(NavOutcome outcome, SurfaceStream.Reason reason, String detail) {
      this.lastReason = reason;
      NavDecision d = new NavDecision(NavDecision.Kind.TERMINATE, this.lastPos, detail == null ? reason.name() : detail, outcome);
      return new Tick(d, reason, this.lastPick, this.recoveryCount, this.planMs, null);
   }

   static double distToSegment(Coord2d p, Coord2d a, Coord2d b) {
      if (p == null || a == null || b == null) {
         return 0.0;
      }
      double dx = b.x - a.x;
      double dy = b.y - a.y;
      double l2 = dx * dx + dy * dy;
      if (l2 < 1.0E-9) {
         return p.dist(a);
      }
      double t = ((p.x - a.x) * dx + (p.y - a.y) * dy) / l2;
      t = Math.max(0.0, Math.min(1.0, t));
      return p.dist(Coord2d.of(a.x + t * dx, a.y + t * dy));
   }

   public static final class Tick {
      public final NavDecision decision;
      public final SurfaceStream.Reason reason;
      public final SurfaceStream.Farthest pick;
      public final int recoveryCount;
      public final long planMs;
      public final Coord escapeCell;

      public Tick(NavDecision decision, SurfaceStream.Reason reason, SurfaceStream.Farthest pick, int recoveryCount, long planMs, Coord escapeCell) {
         this.decision = decision;
         this.reason = reason;
         this.pick = pick;
         this.recoveryCount = recoveryCount;
         this.planMs = planMs;
         this.escapeCell = escapeCell;
      }
   }
}
