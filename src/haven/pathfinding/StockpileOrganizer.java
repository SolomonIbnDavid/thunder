package haven.pathfinding;

import auto.Bot;
import haven.Area;
import haven.Coord2d;
import haven.GameUI;
import haven.GItem;
import haven.Loading;
import haven.WItem;
import haven.layout.LayoutFootprint;

/** Opt-in v1 organizer: plans and creates stockpiles in a selected area, never fills or moves them. */
public final class StockpileOrganizer {
   private static final long STEP_TIMEOUT_MS = 2500L;
   private static final long WALK_TIMEOUT_MS = 20000L;
   private static final int MAX_PILES = 128;

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
      String itemRes = held.item.resname();
      String stockpile = stockpileResource(itemRes);
      if (stockpile == null) fail(bot, "held item is not a supported stockpile item");
      String kind = stockpile.substring(stockpile.lastIndexOf('-') + 1);
      gui.msg("Stockpile: planning " + kind + " piles with exact placement geometry…", GameUI.MsgType.INFO);
      Area tiles = new Area(area.minTile, area.maxTile.add(1, 1));
      int created = 0;
      while (created < MAX_PILES) {
         bot.checkCancelled();
         // Re-plan from the newly grounded world after every server-accepted
         // pile instead of trusting a stale batch of predicted obstacles.
         ObjectOrganizer.ExactPlan plan = StockpilePlacement.planExact(gui, stockpile, tiles, 1);
         if (plan.placements.isEmpty()) {
            if (created == 0)
               fail(bot, "area is not plannable (" + plan.reason + "); nothing placed");
            break;
         }
         ObjectOrganizer.ExactPlacement placement = plan.placements.get(0);
         if (gui.hand() == null || gui.hand().item == null) {
            if (!takeItem(gui, bot, itemRes)) fail(bot, "ran out of matching items after " + created + " piles");
         }

         gui.msg("Stockpile: pile " + (created + 1) + " walking…", GameUI.MsgType.INFO);
         StockpilePlacement.Result result = StockpilePlacement.createHeld(
            gui, bot, stockpile, tiles, placement, WALK_TIMEOUT_MS, STEP_TIMEOUT_MS, listener(gui));
         if (!result.success) fail(bot, "pile " + (created + 1) + " failed: " + result.reason + "; created " + created + " piles");
         created++;
      }
      gui.msg("Stockpile: created " + created + " " + kind + " piles (not filled)", GameUI.MsgType.INFO);
   }

   private static void fail(Bot bot, String reason) throws InterruptedException {
      bot.cancel("Stockpile: " + reason);
      throw new InterruptedException(reason);
   }

   /** Lifts one matching item from the main inventory into hand; false if none left. */
   private static boolean takeItem(GameUI gui, Bot bot, String itemRes) throws InterruptedException {
      if (gui.maininv == null) return false;
      final WItem[] found = new WItem[1];
      gui.maininv.forEachItem((gitem, witem) -> {
         if (found[0] == null && witem != null && itemRes.equals(safeResname(gitem))) found[0] = witem;
      });
      if (found[0] == null) return false;
      found[0].take();
      long end = System.currentTimeMillis() + STEP_TIMEOUT_MS;
      while (System.currentTimeMillis() < end) {
         bot.checkCancelled();
         GameUI.DraggedItem h = gui.hand();
         if (h != null && h.item != null) return true;
         Thread.sleep(50L);
      }
      return false;
   }

   private static String safeResname(GItem g) {
      if (g == null) return null;
      try { return g.resname(); } catch (Loading e) { return null; }
   }

   static String stockpileResource(String item) {
      if (item == null || !item.startsWith("gfx/invobjs/")) return null;
      String name = item.substring("gfx/invobjs/".length());
      if (name.length() == 0 || name.indexOf('/') >= 0) return null;
      // Planks are "board-<woodtype>" (there is no bare board); metal bars are "bar-<metal>".
      // Match by prefix so every wood/metal variant works; "board-" and "bar-" avoid
      // colliding with unrelated names like bark/boardgame.
      if (name.equals("board") || name.startsWith("board-")) return "gfx/terobjs/stockpile-board";
      if (name.equals("bar") || name.startsWith("bar-")) return "gfx/terobjs/stockpile-metal";
      if (name.equals("soil") || name.equals("worm")) return "gfx/terobjs/stockpile-soil";
      if (name.equals("pumpkin")) return "gfx/terobjs/stockpile-pumpkin";
      if (name.equals("straw")) return "gfx/terobjs/stockpile-straw";
      if (name.equals("brick")) return "gfx/terobjs/stockpile-brick";
      if (name.equals("leaf") || name.equals("leaves")) return "gfx/terobjs/stockpile-leaf";
      return null;
   }

   /**
    * Reserved world footprint for a stockpile resource, derived from its real
    * collision geometry (see {@link ObjectFootprints}); falls back to the
    * per-type table in {@link #fallbackHalfExtents(String)} when unreadable.
    */
   @Deprecated
   public static LayoutFootprint stockpileFootprint(String stockpileRes) {
      return ObjectFootprints.footprintFor(stockpileRes, fallbackHalfExtents(stockpileRes));
   }

   static Coord2d fallbackHalfExtents(String resname) {
      if (resname == null) return Coord2d.of(11, 11);
      if (resname.endsWith("-wblock")) return Coord2d.of(5.5, 5.5);
      if (resname.endsWith("-metal")) return Coord2d.of(5.5, 8.25);
      if (resname.endsWith("-straw") || resname.endsWith("-leaf")) return Coord2d.of(8.25, 8.25);
      if (resname.endsWith("-brick")) return Coord2d.of(11, 5.5);
      return Coord2d.of(11, 11); // board / soil / pumpkin / generic
   }

   private static MovementListener listener(final GameUI gui) {
      return new MovementListener() {
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
