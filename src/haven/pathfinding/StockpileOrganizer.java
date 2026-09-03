package haven.pathfinding;

import auto.Bot;
import haven.Coord;
import haven.Coord2d;
import haven.GameUI;
import haven.GItem;
import haven.Gob;
import haven.HackThread;
import haven.MCache;
import haven.FlowerMenu;
import haven.layout.LayoutFootprint;
import haven.layout.LayoutPlanResult;
import haven.layout.LayoutPlacement;
import haven.layout.LayoutRequest;
import java.util.HashSet;
import java.util.Set;

/** Opt-in v1 organizer: plans and creates one stockpile, never fills or moves it. */
public final class StockpileOrganizer {
   private static final long STEP_TIMEOUT_MS = 2500L;
   private static final long WALK_TIMEOUT_MS = 20000L;
   private static final LayoutFootprint STOCKPILE_FOOTPRINT = LayoutFootprint.rect(2, 2, true);

   private StockpileOrganizer() {}

   public static void start(final GameUI gui, final OrganizerAreaSelector.Selection area) {
      if (gui == null || gui.map == null || area == null) {
         if (gui != null) gui.msg("Stockpile: choose a complete area first", GameUI.MsgType.ERROR);
         return;
      }
      if (Bot.hasCurrent()) {
         gui.msg("Stockpile: another bot is running", GameUI.MsgType.ERROR);
         return;
      }
      final Bot bot = Bot.execute((unused, b) -> run(gui, area, b));
      bot.start(gui.ui);
   }

   private static void run(GameUI gui, OrganizerAreaSelector.Selection area, Bot bot) throws InterruptedException {
      if (gui.map.player() == null || gui.map.player().rc == null) fail(bot, "player position unavailable");
      GameUI.DraggedItem held = gui.hand();
      if (held == null || held.item == null) fail(bot, "hold the item to stockpile first");
      String stockpile = stockpileResource(held.item.resname());
      if (stockpile == null) fail(bot, "held item is not a supported stockpile item");

      gui.msg("Stockpile: planning one " + stockpile.substring(stockpile.lastIndexOf('-') + 1) + " pile…", GameUI.MsgType.INFO);
      PrototypePathfinder.Scene scene = PrototypePathfinder.observe(gui, false);
      if (scene == null || scene.occupancy == null) fail(bot, "local occupancy unavailable; nothing placed");
      LayoutRequest request = LayoutRequest.builder(
         scene.occupancy, STOCKPILE_FOOTPRINT, gui.map.player().rc, 1
      ).area(area.min, area.max).pitch(MCache.tilesz.x).build();
      LayoutPlanResult plan = haven.layout.LayoutPlanner.plan(request);
      if (plan.status != LayoutPlanResult.Status.PLANNED || plan.placements.size() != 1)
         fail(bot, "area is not plannable (" + (plan.reason == null || plan.reason.isEmpty() ? "no free space" : plan.reason) + "); nothing placed");

      LayoutPlacement placement = plan.placements.get(0);
      if (placement.standRoute == null || placement.standRoute.isEmpty()) fail(bot, "no reachable interaction lane; nothing placed");
      gui.msg("Stockpile: walking to planned interaction side…", GameUI.MsgType.INFO);
      WaypointWalker.Result walked = WaypointWalker.execute(
         gui, bot, placement.standRoute, 2, WALK_TIMEOUT_MS, listener(gui)
      );
      if (walked != WaypointWalker.Result.ARRIVED && walked != WaypointWalker.Result.READY_TO_INTERACT)
         fail(bot, "could not reach planned interaction side (" + walked + "); nothing placed");

      if (gui.hand() == null || gui.hand().item != held.item) fail(bot, "held item changed before action; nothing placed");
      Set<Long> before = pileIds(gui, stockpile, placement.world.add(MCache.tilesz));
      StockpileProtocol protocol = new StockpileProtocol();
      if (!protocol.accept(StockpileProtocol.Signal.HELD_CONFIRMED)) fail(bot, "protocol rejected held item");
      Coord2d center = placement.world.add(MCache.tilesz);
      if (!gui.map.itemactAt(center, gui.ui.modflags())) fail(bot, "itemact could not be sent; nothing placed");
      if (!protocol.accept(StockpileProtocol.Signal.ITEMACT_ACK)) fail(bot, "itemact protocol acknowledgement failed");

      FlowerMenu menu = waitFlower(gui, bot);
      if (menu == null) fail(bot, "no flower menu acknowledgement; nothing placed");
      if (!protocol.accept(StockpileProtocol.Signal.FLOWER_OPEN)) fail(bot, "flower protocol acknowledgement failed");
      int pile = flowerOption(menu, "Pile");
      if (pile < 0) fail(bot, "flower menu has no Pile action; nothing placed");
      menu.choose(menu.opts[pile]);
      if (!protocol.accept(StockpileProtocol.Signal.FLOWER_SELECTED)) fail(bot, "flower selection protocol failed");
      if (!waitNoFlower(gui, bot)) fail(bot, "flower selection was not acknowledged; nothing placed");
      if (!waitPreview(gui, bot)) fail(bot, "no placement preview acknowledgement; nothing placed");
      if (!protocol.placeSent() || !gui.map.placeCurrent(1, gui.ui.modflags())) fail(bot, "place could not be sent; nothing placed");
      if (!waitNewPile(gui, bot, stockpile, center, before)) fail(bot, "stockpile placement was not acknowledged; failed closed");
      if (!protocol.accept(StockpileProtocol.Signal.PLACE_ACK)) fail(bot, "place protocol acknowledgement failed");
      gui.msg("Stockpile: created one pile (not filled)", GameUI.MsgType.INFO);
   }

   private static void fail(Bot bot, String reason) throws InterruptedException {
      bot.cancel("Stockpile: " + reason);
      throw new InterruptedException(reason);
   }

   private static String stockpileResource(String item) {
      if (item == null || !item.startsWith("gfx/invobjs/")) return null;
      String name = item.substring("gfx/invobjs/".length());
      if (name.length() == 0 || name.indexOf('/') >= 0) return null;
      // Keep the test deliberately conservative: only types with known v1 piles.
      if (!(name.equals("board") || name.equals("brick") || name.equals("leaf") || name.equals("metal")
         || name.equals("soil") || name.equals("straw") || name.equals("pumpkin"))) return null;
      return "gfx/terobjs/stockpile-" + name;
   }

   private static FlowerMenu findFlower(GameUI gui) {
      if (gui == null || gui.ui == null || gui.ui.root == null) return null;
      for (haven.Widget w = gui.ui.root.lchild; w != null; w = w.prev)
         if (w instanceof FlowerMenu) return (FlowerMenu)w;
      return null;
   }

   private static FlowerMenu waitFlower(GameUI gui, Bot bot) throws InterruptedException {
      long end = System.currentTimeMillis() + STEP_TIMEOUT_MS;
      while (System.currentTimeMillis() < end) {
         bot.checkCancelled();
         FlowerMenu menu = findFlower(gui);
         if (menu != null) return menu;
         Thread.sleep(50L);
      }
      return null;
   }

   private static boolean waitNoFlower(GameUI gui, Bot bot) throws InterruptedException {
      long end = System.currentTimeMillis() + STEP_TIMEOUT_MS;
      while (System.currentTimeMillis() < end) {
         bot.checkCancelled();
         if (findFlower(gui) == null) return true;
         Thread.sleep(50L);
      }
      return false;
   }

   private static boolean waitPreview(GameUI gui, Bot bot) throws InterruptedException {
      long end = System.currentTimeMillis() + STEP_TIMEOUT_MS;
      while (System.currentTimeMillis() < end) {
         bot.checkCancelled();
         if (gui.map.hasPlacementPreview()) return true;
         Thread.sleep(50L);
      }
      return false;
   }

   private static boolean waitNewPile(GameUI gui, Bot bot, String res, haven.Coord2d center, Set<Long> before) throws InterruptedException {
      long end = System.currentTimeMillis() + STEP_TIMEOUT_MS;
      while (System.currentTimeMillis() < end) {
         bot.checkCancelled();
         if (!pileIds(gui, res, center).equals(before)) return true;
         Thread.sleep(50L);
      }
      return false;
   }

   private static Set<Long> pileIds(GameUI gui, String res, haven.Coord2d center) {
      Set<Long> ids = new HashSet<Long>();
      synchronized (gui.ui.sess.glob.oc) {
         for (Gob gob : gui.ui.sess.glob.oc) {
            if (gob == null || gob.rc == null || gob.id < 0L || gob.rc.dist(center) > MCache.tilesz.x * 2.5) continue;
            try { if (res.equals(gob.resid())) ids.add(Long.valueOf(gob.id)); }
            catch (haven.Loading ignored) {}
         }
      }
      return ids;
   }

   private static int flowerOption(FlowerMenu menu, String name) {
      if (menu.options == null || menu.opts == null) return -1;
      for (int i = 0; i < menu.options.length && i < menu.opts.length; i++)
         if (name.equals(menu.options[i]) && menu.opts[i] != null) return i;
      return -1;
   }

   private static WaypointWalker.Listener listener(final GameUI gui) {
      return new WaypointWalker.Listener() {
         public void event(String s) {
            gui.msg("Stockpile: " + s, GameUI.MsgType.INFO);
         }
         public void fail(String s) {
            gui.msg("Stockpile: " + s, GameUI.MsgType.ERROR);
         }
         public void fail(String s, WaypointGate.Outcome o, String brief) {
            gui.msg("Stockpile: " + s + " (" + o + ")", GameUI.MsgType.ERROR);
         }
         public void beginWait(String kind, long budget, String detail) {
         }
         public void dumpStuck() {
         }
      };
   }
}
