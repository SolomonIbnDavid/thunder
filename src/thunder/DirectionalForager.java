package thunder;

import auto.Bot;
import auto.BotUtil;
import auto.GobTarget;
import haven.*;
import haven.pathfinding.BotMovement;
import java.util.*;

/** Long-running, player-started compass forager. */
public final class DirectionalForager {
    private static final double FORWARD_STEP = MCache.tilesz.x * 10.0;
    private static final double DANGER_RADIUS = MCache.tilesz.x * 12.0;
    private static volatile boolean running;
    private static volatile String status = "Status: idle";
    private static volatile int collected;
    private static Bot active;

    private DirectionalForager() {}
    public static boolean isRunning() {return running;}
    public static String status() {return status;}
    public static int collected() {return collected;}

    public static synchronized void start(GameUI gui, DirectionalForagerLogic.Direction direction, Set<String> whitelist, boolean caveMode) {
        if(running) {gui.error("Directional Forager is already running."); return;}
        if(gui == null || gui.map == null || gui.map.player() == null) return;
        if(whitelist == null || whitelist.isEmpty()) {gui.error("Directional Forager: select at least one forageable."); return;}
        if(Bot.hasCurrent()) {gui.error("Directional Forager: another task is already running."); return;}
        final Set<String> selected = new HashSet<>(whitelist);
        running = true; collected = 0; status = "Status: starting";
        Bot bot = Bot.execute((unused, b) -> {
            try {new Run(gui, b, direction, selected, caveMode).execute();}
            finally {
                running = false; active = null;
                try {if(gui.pathQueue != null) gui.pathQueue.clear();} catch(Exception ignored) {}
                if(status.equals("Status: stopping")) status = "Status: stopped";
            }
        });
        active = bot;
        bot.start(gui.ui, true);
    }

    public static void stop() {
        Bot bot = active;
        if(bot == null || !running) return;
        status = "Status: stopping";
        bot.cancel("Directional Forager stopped by user.");
    }

    private static final class Run {
        final GameUI gui;
        final Bot bot;
        final DirectionalForagerLogic.Direction direction;
        final Set<String> selected;
        final Map<Long, Long> retryAfter = new HashMap<>();
        final boolean caveMode;
        final Map<Coord, Integer> caveVisits = new HashMap<>();

        Run(GameUI gui, Bot bot, DirectionalForagerLogic.Direction direction, Set<String> selected, boolean caveMode) {
            this.gui = gui; this.bot = bot; this.direction = direction; this.selected = selected; this.caveMode = caveMode;
        }

        void execute() throws InterruptedException {
            while(true) {
                bot.checkCancelled();
                Gob player = gui.map.player();
                if(player == null || player.rc == null) stopWith("Status: player unavailable");
                observeCatalog();
                Gob target = nearest(player.rc);
                if(target != null) {
                    if(!hasCollectionSpace()) stopWith(fullMessage());
                    status = "Status: collecting " + display(target);
                    if(collect(target)) collected++;
                    else if(target.rc != null && safeFromDanger(target.rc))
                        retryAfter.put(target.id, System.currentTimeMillis() + 30000L);
                    continue;
                }
                if(caveMode) {
                    status = "Status: following cave " + direction.name().toLowerCase(Locale.ROOT);
                    rememberCavePosition(player.rc);
                    if(!walkCave(player.rc)) {
                        if(!dangerCenters().isEmpty()) {
                            status = "Status: avoiding danger — looking for another cave passage";
                            Thread.sleep(750L);
                            continue;
                        }
                        stopWith("Status: stopped — no reachable cave passage");
                    }
                    Gob after = gui.map.player();
                    if(after != null && after.rc != null) rememberCavePosition(after.rc);
                } else {
                    status = "Status: walking " + direction.name().toLowerCase(Locale.ROOT);
                    Coord2d next = DirectionalForagerLogic.forward(player.rc, direction, FORWARD_STEP);
                    if(!walk(next)) {
                        if(!dangerCenters().isEmpty()) {
                            status = "Status: avoiding danger — finding a safe way forward";
                            Thread.sleep(750L);
                            continue;
                        }
                        stopWith("Status: stopped — no route forward");
                    }
                }
            }
        }

        /** Try forward candidates together, then broaden to side and back exits. */
        boolean walkCave(Coord2d from) throws InterruptedException {
            final double[][] angleGroups = {
                {0, Math.PI / 8, -Math.PI / 8, Math.PI / 4, -Math.PI / 4},
                {3 * Math.PI / 8, -3 * Math.PI / 8, Math.PI / 2, -Math.PI / 2},
                {5 * Math.PI / 8, -5 * Math.PI / 8, 3 * Math.PI / 4, -3 * Math.PI / 4, Math.PI}
            };
            final double[] distances = {MCache.tilesz.x * 8.0, MCache.tilesz.x * 6.0, MCache.tilesz.x * 4.0};
            for(int group = 0; group < angleGroups.length; group++) {
                bot.checkCancelled();
                List<Coord2d> requested = new ArrayList<>();
                for(double angle : angleGroups[group]) {
                    Coord2d candidate = DirectionalForagerLogic.probe(from, direction, distances[group], angle);
                    if(caveVisits.getOrDefault(caveCell(candidate), 0) < 3) requested.add(candidate);
                }
                if(!requested.isEmpty() && walkAny(requested)) return true;
            }
            return false;
        }

        Coord caveCell(Coord2d position) {
            return position.floor(MCache.tilesz);
        }

        void rememberCavePosition(Coord2d position) {
            Coord cell = caveCell(position);
            caveVisits.put(cell, caveVisits.getOrDefault(cell, 0) + 1);
        }

        void observeCatalog() {
            synchronized(gui.ui.sess.glob.oc) {
                for(Gob gob : gui.ui.sess.glob.oc) ForageCatalog.observe(gob);
            }
        }

        Gob nearest(Coord2d at) {
            List<Gob> gobs = new ArrayList<>();
            List<Coord2d> dangers = dangerCenters();
            long now = System.currentTimeMillis();
            synchronized(gui.ui.sess.glob.oc) {
                for(Gob gob : gui.ui.sess.glob.oc) {
                    if(gob != null && gob.rc != null && !gob.disposed() && retryAfter.getOrDefault(gob.id, 0L) <= now
                        && ForageCatalog.isSelected(gob, selected) && safeFromDanger(gob.rc, dangers)) gobs.add(gob);
                }
            }
            gobs.sort(Comparator.comparingDouble(g -> at.dist(g.rc)));
            return gobs.isEmpty() ? null : gobs.get(0);
        }

        boolean collect(Gob gob) throws InterruptedException {
            if(gob == null || gob.disposed() || gob.rc == null || !safeFromDanger(gob.rc)) return false;
            if(!makeMainRoom()) return false;
            Gob player = gui.map.player();
            if(player == null || player.rc == null) return false;
            BotMovement.Avoidance avoidance = BotMovement.Avoidance.dynamic(this::dangerCenters, DANGER_RADIUS);
            BotMovement.Result result = BotMovement.approach(
                gui, bot, gob, BotMovement.Mode.LAND, avoidance);
            if(result == null || result.status != BotMovement.Status.READY_TO_INTERACT) return false;
            if(gob.disposed() || gob.rc == null || !safeFromDanger(gob.rc)) return false;
            long before = storageSignature();
            BotUtil.rclickAndSelectFlower("Pick").call(new GobTarget(gob), bot);
            long deadline = System.currentTimeMillis() + 12000L;
            while(System.currentTimeMillis() < deadline) {
                bot.checkCancelled();
                boolean gone = gui.map.glob.oc.getgob(gob.id) == null;
                boolean changed = storageSignature() != before;
                if(gone && changed) return true;
                if(gone && !changed) return false;
                Thread.sleep(100L);
            }
            return false;
        }

        boolean walk(Coord2d destination) throws InterruptedException {
            return walkAny(Collections.singletonList(destination));
        }

        boolean walkAny(List<Coord2d> destinations) throws InterruptedException {
            BotMovement.Avoidance avoidance = BotMovement.Avoidance.dynamic(this::dangerCenters, DANGER_RADIUS);
            BotMovement.Result result = BotMovement.moveToAny(
                gui, bot, destinations, avoidance, BotMovement.Mode.LAND, 45000L);
            return result != null && result.status == BotMovement.Status.ARRIVED;
        }

        List<Coord2d> dangerCenters() {
            List<Coord2d> dangers = new ArrayList<>();
            synchronized(gui.ui.sess.glob.oc) {
                for(Gob gob : gui.ui.sess.glob.oc) {
                    if(gob != null && gob.rc != null && !gob.disposed()
                        && gob.is(GobTag.AGGRESSIVE) && !gob.anyOf(GobTag.DEAD, GobTag.KO)) dangers.add(gob.rc);
                }
            }
            return dangers;
        }

        boolean safeFromDanger(Coord2d point) {
            return safeFromDanger(point, dangerCenters());
        }

        boolean safeFromDanger(Coord2d point, Collection<Coord2d> dangers) {
            return DirectionalForagerLogic.safeFrom(point, dangers, DANGER_RADIUS);
        }

        boolean hasCollectionSpace() {
            if(gui.maininv != null && gui.maininv.free() > 0) return true;
            Inventory basket = pickingBasket();
            return basket != null && basket.free() > 0;
        }

        boolean makeMainRoom() throws InterruptedException {
            if(gui.maininv != null && gui.maininv.free() > 0) return true;
            Inventory basket = pickingBasket();
            if(basket == null || basket.free() <= 0 || gui.maininv == null) return false;
            for(WItem item : new ArrayList<>(gui.maininv.children(WItem.class))) {
                String resid;
                try {resid = item.item.resname();} catch(Loading e) {continue;}
                if(!ForageCatalog.isSelectedInventoryResource(resid, selected)) continue;
                Coord place = basket.findPlaceFor(item.lsz);
                if(place == null) continue;
                item.take();
                if(!waitForHand(true, 2500L)) return false;
                basket.wdgmsg("drop", place);
                if(!waitForHand(false, 2500L)) return false;
                return gui.maininv.free() > 0;
            }
            return false;
        }

        boolean waitForHand(boolean occupied, long timeout) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeout;
            while(System.currentTimeMillis() < deadline) {
                bot.checkCancelled();
                if((gui.hand() != null) == occupied) return true;
                Thread.sleep(50L);
            }
            return (gui.hand() != null) == occupied;
        }

        Inventory pickingBasket() {
            for(GItem.ContentsWindow wnd : gui.children(GItem.ContentsWindow.class)) {
                String resid;
                try {resid = wnd.cont.resname();} catch(Loading e) {continue;}
                if(resid == null || !resid.endsWith("/pickingbasket")) continue;
                if(wnd.inv instanceof Inventory) return (Inventory)wnd.inv;
                for(Inventory inv : wnd.inv.children(Inventory.class)) return inv;
            }
            return null;
        }

        long storageSignature() {
            long sig = inventorySignature(gui.maininv);
            Inventory basket = pickingBasket();
            return sig * 1000003L + inventorySignature(basket);
        }

        long inventorySignature(Inventory inv) {
            if(inv == null) return 0;
            long sig = inv.free();
            for(WItem item : inv.children(WItem.class)) {
                try {
                    sig = sig * 31L + item.item.resname().hashCode();
                    Float quantity = item.quantity.get();
                    sig = sig * 31L + (quantity == null ? 0 : Float.floatToIntBits(quantity));
                }
                catch(Loading e) {sig = sig * 31L + 1;}
            }
            return sig;
        }

        String fullMessage() {
            return equippedBasketClosed() ? "Status: stopped — open the equipped Picking Basket" : "Status: stopped — carrying space is full";
        }

        boolean equippedBasketClosed() {
            if(gui.equipory == null || pickingBasket() != null) return false;
            for(Equipory.SLOTS slot : new Equipory.SLOTS[]{Equipory.SLOTS.HAND_LEFT, Equipory.SLOTS.HAND_RIGHT}) {
                WItem item = gui.equipory.slot(slot);
                try {if(item != null && item.item.resname().endsWith("/pickingbasket")) return true;} catch(Loading ignored) {}
            }
            return false;
        }

        String display(Gob gob) {
            try {
                String key = ForageCatalog.key(gob.resid());
                for(ForageCatalog.Entry entry : ForageCatalog.entries())
                    if(Objects.equals(entry.key, key)) return entry.name;
                return key == null ? "forageable" : key;
            } catch(Exception e) {return "forageable";}
        }

        void stopWith(String message) throws InterruptedException {
            status = message;
            bot.cancel(message.replace("Status: ", "Directional Forager: "));
            bot.checkCancelled();
        }
    }
}
