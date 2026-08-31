package haven.pathfinding;

import auto.Bot;
import haven.Coord;
import haven.Coord2d;
import haven.Following;
import haven.GameUI;
import haven.Gob;
import haven.Moving;
import haven.OCache;
import java.util.List;

public final class WaypointWalker {
   private WaypointWalker() {
   }

   public static WaypointWalker.Env liveEnv(final GameUI gui) {
      return new WaypointWalker.Env() {
         @Override
         public WaypointGate.Observation observe(boolean cancelled) {
            return WaypointWalker.observe(gui, cancelled);
         }

         @Override
         public void click(Coord2d waypoint) {
            gui.pathQueue.clear();
            gui.map.wdgmsg("click", new Object[]{Coord.z, waypoint.floor(OCache.posres), 1, 0});
         }

         @Override
         public long now() {
            return System.currentTimeMillis();
         }
      };
   }

   public static WaypointWalker.Result execute(GameUI gui, Bot bot, List<Coord2d> route, int replan, long walkBudgetMs, WaypointWalker.Listener l) throws InterruptedException {
      return execute(liveEnv(gui), bot, route, replan, walkBudgetMs, WaypointWalker.Params.DEFAULT, l);
   }

   public static WaypointWalker.Result execute(WaypointWalker.Env env, Bot bot, List<Coord2d> route, int replan, long walkBudgetMs, WaypointWalker.Listener l) throws InterruptedException {
      return execute(env, bot, route, replan, walkBudgetMs, WaypointWalker.Params.DEFAULT, l);
   }

   public static WaypointWalker.Result execute(
      WaypointWalker.Env env, Bot bot, List<Coord2d> route, int replan, long walkBudgetMs, WaypointWalker.Params params, WaypointWalker.Listener l
   ) throws InterruptedException {
      if (route != null && route.size() >= 2) {
         long deadline = env.now() + walkBudgetMs;
         int total = route.size() - 1;

         for (int i = 1; i < route.size(); i++) {
            WaypointGate.Observation pre = env.observe(false);
            if (pre == null) {
               return WaypointWalker.Result.REJECTED;
            }

            Coord2d waypoint = route.get(i);
            if (!(pre.pos.dist(waypoint) <= params.eps)) {
               Coord2d before = pre.pos;
               WaypointGate.Observation obs = env.observe(false);
               if (obs == null) {
                  l.fail("player gone before wp " + i + "/" + total);
                  return WaypointWalker.Result.REJECTED;
               }

               env.click(waypoint);
               PathfinderLog.setActiveWaypoint(waypoint);
               l.event(String.format("issue wp %d/%d replan=%d at=(%.1f,%.1f) from=(%.1f,%.1f)", i, total, replan, waypoint.x, waypoint.y, before.x, before.y));
               WaypointGate gate = new WaypointGate(params.request(waypoint));
               WaypointGate.Outcome oc = gate.start(obs);
               l.event(String.format("gate wp %d/%d start=%s %s", i, total, oc, obsBrief(obs)));
               long remaining = deadline - env.now();
               if (remaining <= 0L) {
                  l.fail(String.format("waypoint budget exhausted wp %d/%d (gate %s)", i, total, oc), oc, obsBrief(obs));
                  l.dumpStuck();
                  return WaypointWalker.Result.TIMEOUT;
               }

               l.beginWait("waypoint", remaining, String.format("wp %d/%d replan=%d", i, total, replan));

               while (!oc.isTerminal() && env.now() < deadline) {
                  try {
                     bot.checkCancelled();
                     Thread.sleep(50L);
                  } catch (InterruptedException var22) {
                     WaypointGate.Observation canc = env.observe(true);
                     if (canc == null) {
                        throw var22;
                     }

                     oc = gate.observe(canc);
                     break;
                  }

                  obs = env.observe(false);
                  if (obs == null) {
                     l.fail("player gone wp " + i + "/" + total);
                     return WaypointWalker.Result.REJECTED;
                  }

                  oc = gate.observe(obs);
               }

               if (!oc.isTerminal()) {
                  l.fail(String.format("waypoint budget exhausted wp %d/%d (gate %s)", i, total, oc), oc, obsBrief(obs));
                  l.dumpStuck();
                  return WaypointWalker.Result.TIMEOUT;
               }

               switch (classify(oc)) {
                  case COMPLETE:
                     l.event(String.format("gate reached wp %d/%d left=%.2f", i, total, gate.lastDistance()));
                     break;
                  case SHORT_STOP:
                     l.fail(String.format("short stop wp %d/%d left=%.2f", i, total, gate.lastDistance()), oc, obsBrief(obs));
                     return WaypointWalker.Result.SHORT_STOP;
                  case REJECTED:
                     l.fail(String.format("wp %d/%d replan=%d abandoned", i, total, replan), oc, obsBrief(obs));
                     l.dumpStuck();
                     return WaypointWalker.Result.REJECTED;
                  case TIMEOUT:
                     l.fail(String.format("wp %d/%d replan=%d timed out", i, total, replan), oc, obsBrief(obs));
                     l.dumpStuck();
                     return WaypointWalker.Result.TIMEOUT;
                  case ABORT:
                     l.fail(String.format("cancelled wp %d/%d", i, total), oc, obsBrief(obs));
                     throw new InterruptedException("Waypoint walk cancelled");
               }
            }
         }

         return WaypointWalker.Result.ARRIVED;
      } else {
         return WaypointWalker.Result.ARRIVED;
      }
   }

   public static WaypointGate.Observation observe(GameUI gui) {
      return observe(gui, false);
   }

   public static WaypointGate.Observation observe(GameUI gui, boolean cancelled) {
      Gob me = gui != null && gui.map != null ? gui.map.player() : null;
      if (me != null && me.rc != null) {
         Moving mv = (Moving)me.getattr(Moving.class);
         boolean passenger = false;
         if (mv instanceof Following) {
            passenger = isPassenger((Following)mv);
         }

         PathfinderLog.recordConfirmedPos(me.rc);
         return new WaypointGate.Observation(System.currentTimeMillis(), me.rc, mv != null, me.vehicleId(), passenger, cancelled);
      } else {
         return null;
      }
   }

   private static boolean isPassenger(Following follow) {
      if (follow != null && follow.tgt() != null) {
         String id = follow.tgt().resid();
         if (id == null) {
            return false;
         } else {
            String pos = follow.xfname;
            if (id.contains("/vehicle/snekkja")) {
               return !"m0".equals(pos);
            } else if (id.contains("/vehicle/knarr")) {
               return !"m9".equals(pos);
            } else if (id.contains("/vehicle/rowboat")) {
               return !"d".equals(pos);
            } else if (id.contains("/vehicle/spark")) {
               return !"d".equals(pos);
            } else {
               return id.contains("/vehicle/wagon") ? !"d0".equals(pos) : false;
            }
         }
      } else {
         return false;
      }
   }

   public static WaypointWalker.GateClass classify(WaypointGate.Outcome oc) {
      switch (oc) {
         case WAYPOINT_COMPLETE:
            return WaypointWalker.GateClass.COMPLETE;
         case STOPPED_SHORT:
            return WaypointWalker.GateClass.SHORT_STOP;
         case START_TIMEOUT:
         case VEHICLE_STATE_CHANGED:
            return WaypointWalker.GateClass.REJECTED;
         case WALK_TIMEOUT:
         case NO_PROGRESS:
            return WaypointWalker.GateClass.TIMEOUT;
         case CANCELLED:
            return WaypointWalker.GateClass.ABORT;
         default:
            throw new IllegalArgumentException("non-terminal outcome " + oc);
      }
   }

   public static String obsBrief(WaypointGate.Observation o) {
      return o == null
         ? "obs=none"
         : String.format("at=(%.1f,%.1f) moving=%s veh=%d pass=%s", o.pos.x, o.pos.y, o.moving ? "yes" : "no", o.vehicleId, o.passenger ? "yes" : "no");
   }

   public static String gateLabel(WaypointGate.Outcome outcome, String reason, String obs) {
      StringBuilder sb = new StringBuilder();
      if (outcome != null) {
         sb.append("gate ").append(outcome);
      }

      if (reason != null && !reason.isEmpty()) {
         if (sb.length() > 0) {
            sb.append("  ");
         }

         sb.append(reason);
      }

      if (obs != null && !obs.isEmpty()) {
         if (sb.length() > 0) {
            sb.append("  ");
         }

         sb.append(obs);
      }

      return sb.toString();
   }

   public interface Env {
      WaypointGate.Observation observe(boolean var1);

      void click(Coord2d var1);

      long now();
   }

   public static enum GateClass {
      COMPLETE,
      SHORT_STOP,
      REJECTED,
      TIMEOUT,
      ABORT;
   }

   public interface Listener {
      void event(String var1);

      void fail(String var1);

      void fail(String var1, WaypointGate.Outcome var2, String var3);

      void beginWait(String var1, long var2, String var4);

      void dumpStuck();
   }

   public static final class Params {
      public final double eps;
      public final double moveProgress;
      public final long startTimeoutMs;
      public final long walkTimeoutMs;
      public final long jiggleWindowMs;
      public static final WaypointWalker.Params DEFAULT = new WaypointWalker.Params(2.475, 0.6875, 800L, 20000L, 3000L);

      public Params(double eps, double moveProgress, long startTimeoutMs, long walkTimeoutMs, long jiggleWindowMs) {
         if (!(eps > 0.0)) {
            throw new IllegalArgumentException("eps must be > 0: " + eps);
         } else if (!(moveProgress > 0.0)) {
            throw new IllegalArgumentException("moveProgress must be > 0: " + moveProgress);
         } else if (startTimeoutMs < 0L) {
            throw new IllegalArgumentException("startTimeoutMs must be >= 0: " + startTimeoutMs);
         } else if (walkTimeoutMs < startTimeoutMs) {
            throw new IllegalArgumentException("walkTimeoutMs must be >= startTimeoutMs: " + walkTimeoutMs + " < " + startTimeoutMs);
         } else if (jiggleWindowMs <= 0L) {
            throw new IllegalArgumentException("jiggleWindowMs must be > 0: " + jiggleWindowMs);
         } else {
            this.eps = eps;
            this.moveProgress = moveProgress;
            this.startTimeoutMs = startTimeoutMs;
            this.walkTimeoutMs = walkTimeoutMs;
            this.jiggleWindowMs = jiggleWindowMs;
         }
      }

      public WaypointGate.Request request(Coord2d target) {
         return new WaypointGate.Request(target, this.eps, this.moveProgress, this.startTimeoutMs, this.walkTimeoutMs, this.jiggleWindowMs);
      }
   }

   public static enum Result {
      ARRIVED,
      REJECTED,
      SHORT_STOP,
      TIMEOUT;
   }
}
