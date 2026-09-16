package haven.pathfinding;

import auto.Bot;
import haven.CFG;
import haven.Coord2d;
import haven.GameUI;
import haven.GameUI.MsgType;
import haven.Gob;
import haven.Loading;
import haven.MCache;
import haven.OCache;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import me.ender.ClientUtils;
import me.ender.gob.KinInfo;

/** UI and console commands layered above the bot movement service. */
public final class PathfinderCommands {
   private PathfinderCommands() {
   }

   public static List<NearbyGob> nearby(GameUI gui, String query) {
      List<NearbyGob> out = new ArrayList<NearbyGob>();
      if (gui == null || gui.map == null || gui.ui == null || gui.ui.sess == null) return out;
      Gob player = gui.map.player();
      if (player == null || player.rc == null) return out;
      OCache cache = gui.ui.sess.glob.oc;
      synchronized (cache) {
         for (Gob gob : cache) {
            if (gob == null || gob == player || gob.virtual || gob.id < 0L || gob.rc == null) continue;
            double distance = gob.rc.dist(player.rc);
            if (distance > MovementScene.maxReach()) continue;
            try {
               String name = displayName(gob);
               String resid = gob.resid();
               if (matches(query, gob.id, name, resid)) {
                  out.add(new NearbyGob(gob.id, name, resid == null ? "" : resid, distance, gob.rc));
               }
            } catch (Loading ignored) {
            }
         }
      }
      out.sort(Comparator.comparingDouble((NearbyGob gob) -> gob.dist).thenComparingLong(gob -> gob.id));
      return out.size() <= 200 ? out : new ArrayList<NearbyGob>(out.subList(0, 200));
   }

   static boolean matches(String query, long id, String name, String resid) {
      if (query == null || query.trim().isEmpty()) return true;
      String value = query.trim().toLowerCase();
      return Long.toString(id).contains(value)
         || (name != null && name.toLowerCase().contains(value))
         || (resid != null && resid.toLowerCase().contains(value));
   }

   public static String displayName(Gob gob) {
      if (gob == null) return "???";
      try {
         KinInfo kin = gob.kin();
         if (kin != null && kin.name != null && !kin.name.isEmpty()) return kin.name;
      } catch (Loading ignored) {
      }
      try {
         String tooltip = gob.tooltip();
         if (tooltip != null && !tooltip.isEmpty() && !"???".equals(tooltip)) return tooltip;
      } catch (Loading ignored) {
      }
      try {
         String resid = gob.resid();
         if (resid != null) return ClientUtils.prettyResName(resid);
      } catch (Loading ignored) {
      }
      return "#" + gob.id;
   }

   public static void go(GameUI gui, Coord2d destination) {
      if (gui == null || gui.ui == null || gui.map == null || destination == null) {
         if (gui != null) gui.msg("Pathfinder: no destination", MsgType.ERROR);
         return;
      }
      final Coord2d goal = destination;
      gui.msg("Pathfinder: walking…", MsgType.INFO);
      Bot.execute((unused, bot) -> {
         PathfinderLog.setTarget(String.format("ground (%.1f, %.1f)", goal.x, goal.y));
         try {
            BotMovement.Result result = BotMovement.moveTo(gui, bot, goal, BotMovement.Mode.LAND, 120000L);
            gui.msg(result.status == BotMovement.Status.ARRIVED
                  ? "Pathfinder: arrived" : "Pathfinder: " + result.status + " " + result.detail,
               result.status == BotMovement.Status.ARRIVED ? MsgType.INFO : MsgType.ERROR);
         } finally {
            PathfinderLog.clearTarget();
         }
      }).start(gui.ui, true);
   }

   public static void goToGob(GameUI gui, long id) {
      if (gui == null || gui.ui == null || gui.ui.sess == null) return;
      Gob gob = gui.ui.sess.glob.oc.getgob(id);
      if (gob == null || gob.rc == null) {
         gui.msg("Pathfinder: that object is gone", MsgType.ERROR);
         return;
      }
      final String name = displayName(gob);
      gui.msg("Pathfinder: approaching " + name, MsgType.INFO);
      Bot.execute((unused, bot) -> {
         PathfinderLog.setTarget(name + " #" + id);
         try {
            Gob live = gui.ui.sess.glob.oc.getgob(id);
            BotMovement.Result result = BotMovement.approach(gui, bot, live, BotMovement.Mode.LAND);
            gui.msg(result.status == BotMovement.Status.READY_TO_INTERACT
                  ? "Pathfinder: ready beside " + name
                  : "Pathfinder: could not approach " + name + " (" + result.status + ")",
               result.status == BotMovement.Status.READY_TO_INTERACT ? MsgType.INFO : MsgType.ERROR);
         } finally {
            PathfinderLog.clearTarget();
         }
      }).start(gui.ui, true);
   }

   public static void goToNearest(GameUI gui, String query) {
      List<NearbyGob> matches = nearby(gui, query);
      if (matches.isEmpty()) gui.msg("Pathfinder: no nearby object matches \"" + query + "\"", MsgType.ERROR);
      else goToGob(gui, matches.get(0).id);
   }

   public static void console(GameUI gui, String[] args) throws Exception {
      if (args.length >= 2 && "catalog".equals(args[1])) {
         if (args.length >= 3 && "log".equals(args[2])) {
            CatalogDebug.printLog(gui.ui.cons.out);
            gui.msg("Catalog debug: " + CatalogDebug.logFile(), MsgType.INFO);
         } else if (args.length >= 3 && "cancel".equals(args[2])) {
            Bot.cancelCurrent();
            gui.msg("Catalog cancelled", MsgType.INFO);
         } else CupboardBot.start(gui);
      } else if (args.length >= 2 && "debug".equals(args[1])) {
         boolean on = args.length >= 3 ? parseBool(args[2]) : !Boolean.TRUE.equals(CFG.DEBUG_PATHFIND.get());
         CFG.DEBUG_PATHFIND.set(on);
         gui.msg("Pathfinder debug " + (on ? "ON (cyan route overlay)" : "off"), MsgType.INFO);
      } else if (args.length >= 2 && "log".equals(args[1])) {
         PathfinderDebug.printLog(gui.ui.cons.out);
      } else if (args.length >= 2 && "stuck".equals(args[1])) {
         PathfinderDebug.dumpStuck(gui, gui.ui.cons.out);
      } else if (args.length >= 2 && "probe".equals(args[1])) {
         MechanicsProbe.console(gui, args);
      } else if (args.length >= 2 && "gob".equals(args[1])) {
         if (args.length == 2) gui.togglePathfinder();
         else {
            String value = join(args, 2);
            try { goToGob(gui, Long.parseLong(value.trim())); }
            catch (NumberFormatException ignored) { goToNearest(gui, value); }
         }
      } else if (args.length == 1) {
         gui.togglePathfinder();
      } else if (args.length == 3 || (args.length == 4 && "rel".equals(args[1]))) {
         int offset = args.length == 4 ? 2 : 1;
         Coord2d destination = Coord2d.of(Double.parseDouble(args[offset]), Double.parseDouble(args[offset + 1]));
         Gob player = gui.map == null ? null : gui.map.player();
         if (player == null) throw new Exception("Player is not available");
         go(gui, args.length == 4 ? player.rc.add(destination) : destination);
      } else {
         throw new Exception("Usage: pf | pf [rel] <x> <y> | pf gob [id|name] | pf catalog [log|cancel] | pf probe [room|click|step|last] | pf debug | pf log | pf stuck");
      }
   }

   private static boolean parseBool(String value) {
      String normalized = value.trim().toLowerCase();
      if (normalized.equals("true") || normalized.equals("on") || normalized.equals("1") || normalized.equals("yes")) return true;
      if (normalized.equals("false") || normalized.equals("off") || normalized.equals("0") || normalized.equals("no")) return false;
      return !Boolean.TRUE.equals(CFG.DEBUG_PATHFIND.get());
   }

   private static String join(String[] values, int from) {
      StringBuilder out = new StringBuilder();
      for (int i = from; i < values.length; i++) {
         if (out.length() > 0) out.append(' ');
         out.append(values[i]);
      }
      return out.toString();
   }

   public static final class NearbyGob {
      public final long id;
      public final String name;
      public final String resid;
      public final double dist;
      public final Coord2d rc;

      NearbyGob(long id, String name, String resid, double dist, Coord2d rc) {
         this.id = id;
         this.name = name;
         this.resid = resid;
         this.dist = dist;
         this.rc = rc;
      }

      public String label() {
         return String.format("%s  %.1ft", name, dist / MCache.tilesz.x);
      }
   }
}
