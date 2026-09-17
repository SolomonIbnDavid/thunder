package thunder.woodcut;

import auto.Bot;
import auto.InvHelper;
import auto.MapHelper;
import haven.*;
import haven.pathfinding.BotMovement;
import haven.pathfinding.WorldObjectRegistry;
import haven.pathfinding.StockpilePlacement;
import haven.pathfinding.ObjectOrganizer;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Cuts logs found loose in a selected area, or unloads them one at a time from
 * carts in that area, and transfers the products into stockpiles in an output area.
 */
public final class WoodCutBot {
    private static final double MIN_ENERGY = 0.40; // IMeter 0..1 == tooltip 0..10000%
    private static final long STEP_TIMEOUT = 6000L;
    private static final long CHOP_TIMEOUT = 120000L;
    private static final String LOG_PATH = "logs/log-cutter-debug.log";
    private static volatile boolean running;
    private static volatile String status = "Status: idle";
    private static Bot active;

    private WoodCutBot() {}

    public static boolean isRunning() {return running;}
    public static String status() {return status;}

    public static synchronized void start(GameUI gui, WoodCutRules.Product product, Area logs, Area output) {
        if(running) {gui.error("Log Cutter is already running."); return;}
        if(gui == null || gui.map == null || gui.menu == null) {return;}
        if(logs == null || output == null) {gui.error("Log Cutter: select both areas first."); return;}
        running = true;
        status = "Status: starting";
        Bot bot = Bot.execute((unused, b) -> {
            try {new Run(gui, b, product, logs, output).execute();}
            finally {
                running = false;
                active = null;
                try {if(gui.pathQueue != null) gui.pathQueue.clear();} catch(Exception ignored) {}
            }
        });
        active = bot;
        bot.start(gui.ui, true);
    }

    public static void stop() {
        Bot bot = active;
        if(bot != null) bot.cancel("Log Cutter stopped by user.");
        status = "Status: stopping";
    }

    /** Writes a point-in-time world/inventory snapshot next to the rolling diagnostic log. */
    public static void snapshot(GameUI gui, String reason) {
        if(gui == null) return;
        try {
            File dir = new File("logs");
            if(!dir.exists()) dir.mkdirs();
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
            File file = new File(dir, "log-cutter-snapshot-" + stamp + ".txt");
            try(PrintWriter out = new PrintWriter(new FileWriter(file))) {
                out.println("reason=" + reason);
                IMeter nrj = gui.getIMeter("nrj");
                out.println("energy=" + (nrj == null ? "unavailable" : nrj.meter(0)));
                Gob player = gui.map.player();
                out.println("player=" + (player == null ? "unavailable" : player.id + " @ " + player.rc));
                out.println("inventory_free=" + (gui.maininv == null ? -1 : gui.maininv.free()));
                out.println("hand_item=" + (gui.hand() == null ? "none" : safeRes(gui.hand().item)));
                synchronized(gui.ui.sess.glob.oc) {
                    for(Gob gob : gui.ui.sess.glob.oc) {
                        if(gob != null && gob.rc != null) {
                            Following f = gob.getattr(Following.class);
                            out.println("gob=" + gob.id + " res=" + safeRes(gob) + " rc=" + gob.rc +
                                " following=" + (f == null ? "none" : f.tgt));
                        }
                    }
                }
                dumpWidgets(out, gui.ui.root, 0);
            }
            gui.msg("Log Cutter snapshot: " + file.getPath(), GameUI.MsgType.INFO);
            diag("snapshot reason=%s file=%s", reason, file.getPath());
        } catch(Exception e) {
            gui.error("Log Cutter snapshot failed: " + e.getMessage());
        }
    }

    private static void dumpWidgets(PrintWriter out, Widget root, int depth) {
        if(root == null || depth > 12) return;
        for(Widget w = root.lchild; w != null; w = w.prev) {
            String extra = w instanceof Window ? " caption=" + ((Window)w).caption() :
                (w instanceof Button ? " text=" + (((Button)w).text == null ? "" : ((Button)w).text.text) : "");
            out.println("widget=" + depth + " " + w.getClass().getName() + extra);
            dumpWidgets(out, w, depth + 1);
        }
    }

    private static synchronized void diag(String format, Object... args) {
        try {
            File file = new File(LOG_PATH);
            File parent = file.getParentFile();
            if(parent != null && !parent.exists()) parent.mkdirs();
            try(PrintWriter out = new PrintWriter(new FileWriter(file, true))) {
                out.printf(Locale.ROOT, "%tFT%<tT.%<tL ", new Date());
                out.printf(Locale.ROOT, format, args);
                out.println();
            }
        } catch(Exception ignored) {}
    }

    private static String safeRes(Gob gob) {
        try {return gob == null ? null : gob.resid();} catch(Exception e) {return "<loading>";}
    }

    private static String safeRes(GItem item) {
        try {return item == null ? null : item.resname();} catch(Exception e) {return "<loading>";}
    }

    static boolean pileFootprintsConflict(Coord2d candidate, Coord2d existing, double fullWidth) {
        if(candidate == null || existing == null || !(fullWidth > 0)) return true;
        double epsilon = 0.01;
        return Math.abs(candidate.x - existing.x) <= fullWidth + epsilon &&
            Math.abs(candidate.y - existing.y) <= fullWidth + epsilon;
    }

    /** Prefer the source area, then the selected output area, then nearby ground. */
    static int temporaryDropTier(Coord tile, Area logs, Area output) {
        if(tile != null && logs != null && logs.contains(tile)) return 0;
        if(tile != null && output != null && output.contains(tile)) return 1;
        return 2;
    }

    private static final class StopRun extends Exception {
        final boolean success;
        StopRun(String message, boolean success) {super(message); this.success = success;}
    }

    private static final class Run {
        private final GameUI gui;
        private final Bot bot;
        private final WoodCutRules.Product product;
        private final Area logs;
        private final Area output;
        private final Set<String> cartSlotsTried = new HashSet<>();
        private int logsCompleted;
        private int pilesCreated;
        private String lastApproachFailure = "";

        Run(GameUI gui, Bot bot, WoodCutRules.Product product, Area logs, Area output) {
            this.gui = gui; this.bot = bot; this.product = product; this.logs = logs; this.output = output;
        }

        void execute() {
            diag("START product=%s logs=%s output=%s", product, logs, output);
            snapshot(gui, "run-start");
            try {
                loop();
            } catch(InterruptedException e) {
                diag("CANCEL completed=%d piles=%d", logsCompleted, pilesCreated);
                status = "Status: stopped (" + logsCompleted + " logs)";
            } catch(StopRun stop) {
                diag("STOP success=%b message=%s completed=%d piles=%d", stop.success, stop.getMessage(), logsCompleted, pilesCreated);
                status = "Status: " + stop.getMessage();
                if(stop.success) gui.msg("Log Cutter: " + stop.getMessage(), GameUI.MsgType.GOOD);
                else {snapshot(gui, "failure-" + stop.getMessage()); gui.error("Log Cutter: " + stop.getMessage());}
            } catch(Throwable t) {
                diag("CRASH %s: %s", t.getClass().getName(), t.getMessage());
                snapshot(gui, "exception-" + t.getClass().getSimpleName());
                status = "Status: error — see diagnostic log";
                gui.error("Log Cutter failed: " + t.getClass().getSimpleName() + ". Debug snapshot saved.");
            }
        }

        private void loop() throws InterruptedException, StopRun {
            safety();
            depositProducts(); // resume safely if a prior run left products in inventory
            requireInventorySpace("starting");
            while(true) {
                safety();
                Gob log = nearestLooseLog();
                if(log == null) log = unloadOneCartLog();
                if(log == null) {
                    depositProducts();
                    done("finished " + logsCompleted + " logs into " + pilesCreated + " new stockpiles");
                }
                cutLog(log);
                logsCompleted++;
                depositProducts();
            }
        }

        private void safety() throws StopRun {
            IMeter energy = gui.getIMeter("nrj");
            double e = energy == null ? -1 : energy.meter(0);
            boolean water = hasWater();
            diag("SAFETY energy=%.4f water=%b invFree=%d", e, water, gui.maininv == null ? -1 : gui.maininv.free());
            if(e >= 0 && e < MIN_ENERGY) fail("stopped: energy below 4,000% (" + Math.round(e * 10000) + "%)");
            if(!water) fail("stopped: no water remaining on the character");
        }

        private boolean hasWater() {
            return InvHelper.HANDS(gui).get().stream().anyMatch(w -> InvHelper.HAS_WATER.test(w)) ||
                InvHelper.POUCHES(gui).get().stream().anyMatch(w -> InvHelper.HAS_WATER.test(w)) ||
                InvHelper.INVENTORY(gui).get().stream().anyMatch(w -> InvHelper.HAS_WATER.test(w)) ||
                InvHelper.BELT(gui).get().stream().anyMatch(w -> InvHelper.HAS_WATER.test(w));
        }

        private Gob nearestLooseLog() {
            Gob player = gui.map.player();
            List<Gob> found = new ArrayList<>();
            WorldObjectRegistry.Snapshot world = WorldObjectRegistry.snapshot(gui, player, 0);
            for(WorldObjectRegistry.Entry e : world.objects) {
                Gob g = e.gob;
                if(!logs.contains(e.position.floor(MCache.tilesz))) continue;
                if(e.category == WorldObjectRegistry.Category.LOG && g.getattr(Following.class) == null) found.add(g);
            }
            if(world.unresolved > 0) diag("WORLD scan purpose=loose-logs unresolved=%d total=%d", world.unresolved, world.objects.size());
            found.sort(Comparator.comparingDouble(g -> player == null ? 0 : player.rc.dist(g.rc)));
            return found.isEmpty() ? null : found.get(0);
        }

        private List<Gob> carts() {
            List<Gob> found = new ArrayList<>();
            Gob player = gui.map.player();
            WorldObjectRegistry.Snapshot world = WorldObjectRegistry.snapshot(gui, player, 0);
            for(WorldObjectRegistry.Entry e : world.objects) {
                if(e.category == WorldObjectRegistry.Category.VEHICLE && logs.contains(e.position.floor(MCache.tilesz)) && WoodCutRules.isCart(e.resource)) found.add(e.gob);
            }
            if(world.unresolved > 0) diag("WORLD scan purpose=carts unresolved=%d total=%d", world.unresolved, world.objects.size());
            found.sort(Comparator.comparingDouble(g -> player == null ? 0 : player.rc.dist(g.rc)));
            return found;
        }

        private Gob unloadOneCartLog() throws InterruptedException, StopRun {
            for(Gob cart : carts()) {
                if(!approach(cart)) continue;
                List<Integer> occupied = occupiedCartSlots(cart);
                diag("CART cargo-state cart=%d model=%d occupiedSlots=%s", cart.id, cartModelState(cart), occupied);
                for(int slot : occupied) {
                    String key = cart.id + ":" + slot;
                    if(!cartSlotsTried.add(key)) continue;
                    safety();
                    diag("CART try cart=%d res=%s slot=%d", cart.id, safeRes(cart), slot);
                    Coord mc = cart.rc.floor(OCache.posres);
                    gui.map.wdgmsg("click", Coord.z, mc, 3, 0, 0, (int)cart.id, mc, 0, slot);
                    Gob carried = waitCarriedObject(STEP_TIMEOUT);
                    if(carried == cart || (carried != null && WoodCutRules.isCart(safeRes(carried)))) {
                        Coord2d original = cart.rc;
                        gui.map.wdgmsg("click", Coord.z, original.floor(OCache.posres), 3, 0);
                        waitFor(STEP_TIMEOUT, () -> cart.getattr(Following.class) == null);
                        fail("cart cargo interaction lifted the cart; it was put back and the run was stopped");
                    }
                    if(carried == null || !WoodCutRules.isLog(safeRes(carried))) {
                        diag("CART slot rejected cart=%d slot=%d carried=%s", cart.id, slot, carried == null ? "none" : safeRes(carried));
                        continue;
                    }
                    Coord2d drop = walkToOpenDropPoint(cart, carried);
                    if(drop == null) fail("could not find and reach an open point near cart " + cart.id + " for its log");
                    gui.map.wdgmsg("click", Coord.z, drop.floor(OCache.posres), 3, 0);
                    if(!waitFor(STEP_TIMEOUT, () -> carried.getattr(Following.class) == null)) fail("cart log could not be placed on the ground");
                    diag("CART unloaded cart=%d slot=%d log=%d drop=%s", cart.id, slot, carried.id, carried.rc);
                    return carried;
                }
            }
            return null;
        }

        private int cartModelState(Gob cart) {
            try {
                Drawable drawable = cart.getattr(Drawable.class);
                return drawable instanceof ResDrawable ? ((ResDrawable)drawable).sdtnum() : -1;
            } catch(Exception e) {
                return -1;
            }
        }

        /** Cart cargo bits map to interaction flags 2..7 (verified by CartOut and the capture). */
        private List<Integer> occupiedCartSlots(Gob cart) {
            return WoodCutRules.occupiedCartSlots(cartModelState(cart));
        }

        private Gob waitCarriedObject(long timeout) throws InterruptedException {
            final Gob[] result = new Gob[1];
            waitFor(timeout, () -> {
                Gob player = gui.map.player();
                if(player == null) return false;
                synchronized(gui.ui.sess.glob.oc) {
                    for(Gob g : gui.ui.sess.glob.oc) {
                        Following f = g == null ? null : g.getattr(Following.class);
                        if(g != null && f != null && f.tgt == player.id) {
                            result[0] = g; return true;
                        }
                    }
                }
                return false;
            });
            return result[0];
        }

        /**
         * A carried log cannot be dropped inside the cart/player collision cluster.
         * Probe nearby points, nearest first, require enough world-space clearance
         * for a log, and actually walk to the point before issuing the drop click.
         */
        private Coord2d walkToOpenDropPoint(Gob cart, Gob carried) throws InterruptedException {
            Gob player = gui.map.player();
            if(player == null || player.rc == null) return null;
            List<List<Coord2d>> tiers = nearbyDropCandidateTiers(cart.rc, player.rc);
            int total = tiers.stream().mapToInt(List::size).sum();
            diag("CART drop-search cart=%d carried=%d origin=%s candidates=%d", cart.id, carried.id, player.rc, total);
            String[] names = {"source", "output", "nearby"};
            for(int tier = 0; tier < tiers.size(); tier++) {
                bot.checkCancelled();
                List<Coord2d> clear = new ArrayList<>();
                for(Coord2d point : tiers.get(tier)) {
                    if(dropPointClear(point, cart, carried)) clear.add(point);
                }
                diag("CART drop-tier name=%s candidates=%d clear=%d", names[tier], tiers.get(tier).size(), clear.size());
                if(clear.isEmpty()) continue;
                BotMovement.Result movement = BotMovement.moveToAny(
                    gui, bot, clear, BotMovement.Avoidance.NONE, BotMovement.Mode.LAND, 8000L);
                Gob now = gui.map.player();
                boolean arrived = movement.status == BotMovement.Status.ARRIVED &&
                    movement.selectedGoal != null && now != null && now.rc != null &&
                    now.rc.dist(movement.selectedGoal) <= MCache.tilesz.x * 0.65;
                diag("CART drop-tier name=%s result=%s detail=%s goal=%s arrived=%b player=%s",
                    names[tier], movement.status, movement.detail, movement.selectedGoal, arrived,
                    now == null ? "unavailable" : now.rc);
                if(arrived) {
                    now = gui.map.player();
                    long end = System.currentTimeMillis() + 3000L;
                    while(now != null && now.getattr(Moving.class) != null && System.currentTimeMillis() < end) {
                        bot.checkCancelled();
                        Thread.sleep(50L);
                    }
                    return movement.selectedGoal;
                }
            }
            return null;
        }

        private List<List<Coord2d>> nearbyDropCandidateTiers(Coord2d cart, Coord2d player) {
            List<Coord2d> source = new ArrayList<>();
            List<Coord2d> stagedOutput = new ArrayList<>();
            List<Coord2d> nearby = new ArrayList<>();
            // Prefer the designated source area. The selected output area is a
            // safe temporary fallback because this log is fully cut before any
            // stockpile placement is planned. Only then try other nearby ground.
            double tile = MCache.tilesz.x;
            for(int radiusTiles = 3; radiusTiles <= 10; radiusTiles++) {
                double r = radiusTiles * tile;
                for(int i = 0; i < 16; i++) {
                    double a = (Math.PI * 2.0 * i) / 16.0;
                    Coord2d p = cart.add(Math.cos(a) * r, Math.sin(a) * r);
                    int tier = temporaryDropTier(p.floor(MCache.tilesz), logs, output);
                    if(tier == 0) source.add(p);
                    else if(tier == 1) stagedOutput.add(p);
                    else nearby.add(p);
                }
            }
            source.sort(Comparator.comparingDouble(player::dist));
            stagedOutput.sort(Comparator.comparingDouble(player::dist));
            nearby.sort(Comparator.comparingDouble(player::dist));
            diag("CART drop-candidates source=%d output=%d nearby=%d",
                source.size(), stagedOutput.size(), nearby.size());
            return Arrays.asList(source, stagedOutput, nearby);
        }

        private boolean dropPointClear(Coord2d point, Gob cart, Gob carried) {
            double clearance = MCache.tilesz.x * 1.8;
            synchronized(gui.ui.sess.glob.oc) {
                for(Gob g : gui.ui.sess.glob.oc) {
                    if(g == null || g == carried || g.rc == null || g.disposed()) continue;
                    if(g == gui.map.player()) continue;
                    // The cart must obey the same clearance rule; this is precisely
                    // what prevents the former drop-at-player/cart failure.
                    double needed = (g == cart) ? MCache.tilesz.x * 2.5 : clearance;
                    if(g.rc.dist(point) < needed) return false;
                }
            }
            return true;
        }

        private void cutLog(Gob log) throws InterruptedException, StopRun {
            while(log != null) {
                Coord2d workingPoint = log.rc;
                safety();
                if(!hasProductSpace()) {
                    depositProducts();
                    requireInventorySpace("continuing the current log");
                    continue;
                }
                if(!approach(log)) fail("could not reach log " + log.id + lastApproachFailure);
                status = "Status: cutting log " + (logsCompleted + 1) + " into " + product.name().toLowerCase(Locale.ROOT);
                int before = countPendingProducts();
                FlowerMenu menu = openMenu(log);
                if(menu == null) fail("log action menu did not appear");
                FlowerMenu.Petal petal = petal(menu, product.flower);
                if(petal == null) {menu.choose(null); fail("missing action ‘" + product.flower + "’ (check tool equipped)");}
                diag("CUT log=%d action=%s productsBefore=%d", log.id, product.flower, before);
                menu.choose(petal);
                long end = System.currentTimeMillis() + CHOP_TIMEOUT;
                boolean sawProgress = false;
                while(System.currentTimeMillis() < end && !log.disposed()) {
                    bot.checkCancelled();
                    safety();
                    if(gui.prog != null) sawProgress = true;
                    if(!hasProductSpace()) {
                        Gob player = gui.map.player();
                        if(player != null) gui.map.wdgmsg("click", Coord.z, player.rc.floor(OCache.posres), 1, 0);
                        waitFor(2000, () -> gui.prog == null);
                        depositProducts();
                        requireInventorySpace("continuing the current log");
                        break;
                    }
                    if(sawProgress && gui.prog == null && countPendingProducts() > before) break;
                    Thread.sleep(100L);
                }
                if(!log.disposed() && !sawProgress && countPendingProducts() == before) fail("cutting never started for log " + log.id);
                diag("CUT result log=%d disposed=%b products=%d free=%d", log.id, log.disposed(), countPendingProducts(), gui.maininv == null ? -1 : gui.maininv.free());
                if(log.disposed()) {
                    Gob replacement = waitReplacementLog(workingPoint, log.id, 2500L);
                    if(replacement == null) return;
                    diag("CUT continuation oldLog=%d replacementLog=%d at=%s", log.id, replacement.id, replacement.rc);
                    log = replacement;
                }
            }
        }

        private void requireInventorySpace(String context) throws StopRun {
            if(gui.maininv == null) fail("main inventory is unavailable");
            int free = gui.maininv.free();
            Coord fit = gui.maininv.findPlaceFor(product.inventorySize);
            if(fit == null) {
                diag("INVENTORY blocked context=%s free=%d required=%dx%d products=%d", context, free,
                    product.inventorySize.x, product.inventorySize.y, countPendingProducts());
                fail("not enough inventory space for " + product.name().toLowerCase(Locale.ROOT) +
                    "; clear a contiguous " + product.inventorySize.x + "x" + product.inventorySize.y +
                    " space before " + context);
            }
        }

        private boolean hasProductSpace() {
            return gui.maininv != null && gui.maininv.findPlaceFor(product.inventorySize) != null;
        }

        /** Chopping can replace a shortened log with a new gob id at the same point. */
        private Gob waitReplacementLog(Coord2d point, long oldId, long timeout) throws InterruptedException {
            final Gob[] replacement = new Gob[1];
            waitFor(timeout, () -> {
                synchronized(gui.ui.sess.glob.oc) {
                    for(Gob g : gui.ui.sess.glob.oc) {
                        if(g != null && g.id != oldId && !g.disposed() && g.rc != null &&
                            WoodCutRules.isLog(safeRes(g)) && g.rc.dist(point) <= MCache.tilesz.x * 1.5) {
                            replacement[0] = g;
                            return true;
                        }
                    }
                }
                return false;
            });
            return replacement[0];
        }

        private FlowerMenu openMenu(Gob gob) throws InterruptedException {
            Set<Widget> before = widgets(gui.ui.root);
            FlowerMenu.lastGob(gob);
            Coord mc = gob.rc.floor(OCache.posres);
            gui.map.wdgmsg("click", Coord.z, mc, 3, 0, 0, (int)gob.id, mc, 0, -1);
            final FlowerMenu[] result = new FlowerMenu[1];
            waitFor(STEP_TIMEOUT, () -> {result[0] = newWidget(gui.ui.root, FlowerMenu.class, before); return result[0] != null;});
            return result[0];
        }

        private FlowerMenu.Petal petal(FlowerMenu menu, String text) {
            if(menu.opts == null) return null;
            for(FlowerMenu.Petal p : menu.opts) if(text.equals(p.name)) return p;
            return null;
        }

        private void depositProducts() throws InterruptedException, StopRun {
            while(countProducts() > 0 || hasHeldProduct()) {
                safety();
                observeOutputArea();
                Gob pile = availablePile();
                if(pile == null) pile = createPile();
                if(pile == null) fail("no free stockpile space in the selected output area");
                if(!approach(pile)) fail("could not reach output stockpile " + pile.id + lastApproachFailure);

                // Inventory packing can leave the last produced board/block on
                // the cursor (for example after a Cruel Splinter occupies the
                // intended cells). Treat it as output rather than as corruption.
                if(hasHeldProduct()) {
                    pile.itemact(0);
                    if(!waitFor(1200L, () -> gui.hand() == null)) {
                        diag("DEPOSIT pile=%d held-product rejected", pile.id);
                        fullPiles.add(pile.id);
                        continue;
                    }
                    diag("DEPOSIT pile=%d held-product accepted", pile.id);
                    if(countProducts() <= 0) continue;
                }
                Window win = openWindow(pile);
                if(win == null) fail("stockpile window did not open");
                int before = countProducts();
                boolean transferStalled = false;
                try {
                    for(WItem item : productItems()) {
                        bot.checkCancelled();
                        int itemBefore = countProducts();
                        item.item.wdgmsg("transfer", item.sz, 1);
                        boolean moved = waitFor(1200, () -> item.disposed() || countProducts() < itemBefore);
                        before = countProducts();
                        if(!moved || before >= itemBefore) {
                            transferStalled = true;
                            diag("DEPOSIT pile=%d full-or-blocked remaining=%d", pile.id, before);
                            break;
                        }
                    }
                } finally {win.reqdestroy();}
                diag("DEPOSIT pile=%d remaining=%d", pile.id, countProducts());
                if(transferStalled && !productItems().isEmpty()) {
                    // A full stockpile stays recognizable; exclude it for this run.
                    fullPiles.add(pile.id);
                }
            }
        }

        /**
         * The object cache is proximity-driven. In particular, a pile selected on a
         * previous run may not exist in the cache while the player is back at the
         * carts. Walk to the outside edge of the output area and allow the scene to
         * settle before deciding that a placement slot is empty.
         */
        private void observeOutputArea() throws InterruptedException, StopRun {
            Gob player = gui.map.player();
            if(player == null || player.rc == null) fail("player position unavailable while checking output area");

            List<Coord2d> points = new ArrayList<>();
            int left = output.ul.x - 1;
            int right = output.br.x;
            int top = output.ul.y - 1;
            int bottom = output.br.y;
            for(int x = output.ul.x; x < output.br.x; x++) {
                points.add(tileCenter(x, top));
                points.add(tileCenter(x, bottom));
            }
            for(int y = output.ul.y; y < output.br.y; y++) {
                points.add(tileCenter(left, y));
                points.add(tileCenter(right, y));
            }
            points.sort(Comparator.comparingDouble(player.rc::dist));

            boolean nearby = distanceToArea(player.rc, output) <= MCache.tilesz.x * 3.0;
            boolean walked = nearby;
            for(Coord2d point : points) {
                if(walked) break;
                if(!observationPointClear(point)) continue;
                walked = plannedWalkTo(point, 12000L, MCache.tilesz.x * 0.75);
                diag("PILE observe walk target=%s result=%b", point, walked);
            }
            if(!walked) fail("could not reach the stockpile area to inspect existing piles");
            waitForMovementSettled(gui.map.player());
            Thread.sleep(500L);
            logOutputPiles();
        }

        private Coord2d tileCenter(int x, int y) {
            return MCache.tilesz.mul(x, y).add(MCache.tilesz.x * 0.5, MCache.tilesz.y * 0.5);
        }

        /** Route point-to-point bot movement around carts, piles, and furniture. */
        private boolean plannedWalkTo(Coord2d point, long timeout, double tolerance) throws InterruptedException {
            Gob player = gui.map.player();
            if(player == null || player.rc == null || point == null) return false;
            if(player.rc.dist(point) <= tolerance) return true;

            BotMovement.Result result = BotMovement.moveTo(
                gui, bot, point, BotMovement.Mode.LAND, timeout);
            player = gui.map.player();
            boolean arrived = result.status == BotMovement.Status.ARRIVED && player != null &&
                player.rc != null && player.rc.dist(point) <= tolerance;
            diag("WALK target=%s result=%s arrived=%b", point, result.status, arrived);
            return arrived;
        }

        private double distanceToArea(Coord2d point, Area area) {
            double minX = area.ul.x * MCache.tilesz.x;
            double maxX = area.br.x * MCache.tilesz.x;
            double minY = area.ul.y * MCache.tilesz.y;
            double maxY = area.br.y * MCache.tilesz.y;
            double dx = Math.max(minX - point.x, Math.max(0, point.x - maxX));
            double dy = Math.max(minY - point.y, Math.max(0, point.y - maxY));
            return Math.hypot(dx, dy);
        }

        private boolean observationPointClear(Coord2d point) {
            synchronized(gui.ui.sess.glob.oc) {
                for(Gob g : gui.ui.sess.glob.oc) {
                    if(g == null || g.rc == null || g.disposed() || g == gui.map.player()) continue;
                    if(g.rc.dist(point) < MCache.tilesz.x * 0.8) return false;
                }
            }
            return true;
        }

        private void logOutputPiles() {
            int count = 0;
            synchronized(gui.ui.sess.glob.oc) {
                for(Gob g : gui.ui.sess.glob.oc) {
                    if(g == null || g.rc == null || g.disposed() || !product.stockpile.equals(safeRes(g))) continue;
                    if(distanceToArea(g.rc, output) <= MCache.tilesz.x * 3.0) {
                        diag("PILE observed id=%d at=%s inside=%b excludedFull=%b", g.id, g.rc,
                            output.contains(g.rc.floor(MCache.tilesz)), fullPiles.contains(g.id));
                        count++;
                    }
                }
            }
            diag("PILE observe complete nearby=%d", count);
        }

        private final Set<Long> fullPiles = new HashSet<>();

        private Gob availablePile() {
            Gob player = gui.map.player();
            List<Gob> piles = new ArrayList<>();
            WorldObjectRegistry.Snapshot world = WorldObjectRegistry.snapshot(gui, player, 0);
            for(WorldObjectRegistry.Entry e : world.objects) {
                if(e.category == WorldObjectRegistry.Category.STOCKPILE && !fullPiles.contains(e.id) &&
                    output.contains(e.position.floor(MCache.tilesz)) && product.stockpile.equals(e.resource)) piles.add(e.gob);
            }
            if(world.unresolved > 0) diag("WORLD scan purpose=available-piles unresolved=%d total=%d", world.unresolved, world.objects.size());
            piles.sort(Comparator.comparingDouble(g -> player == null ? 0 : player.rc.dist(g.rc)));
            return piles.isEmpty() ? null : piles.get(0);
        }

        private Gob createPile() throws InterruptedException, StopRun {
            WItem first = firstProduct();
            if(first == null && !hasHeldProduct()) return null;
            ObjectOrganizer.ExactPlacement placement = nextPilePlacement();
            if(placement == null) return null;
            if(gui.hand() != null && !hasHeldProduct()) fail("cursor holds an unrelated item before stockpile creation");
            if(gui.hand() == null) {
                first.take();
                if(!waitFor(STEP_TIMEOUT, () -> gui.hand() != null)) fail("could not lift first stockpile item");
            }
            StockpilePlacement.Result result = StockpilePlacement.createHeld(
                gui, bot, product.stockpile, output, placement, 20000L, STEP_TIMEOUT, null);
            if(!result.success) fail("stockpile creation failed: " + result.reason);
            for(Window window : allWidgets(gui, Window.class)) {
                if("Stockpile".equals(window.caption())) window.reqdestroy();
            }
            Thread.sleep(150L);
            pilesCreated++;
            diag("PILE created id=%d res=%s at=%s planned=%s", result.gob.id, product.stockpile,
                result.gob.rc, result.center);
            return result.gob;
        }

        /** Uses the shared exact physical-placement planner. */
        private ObjectOrganizer.ExactPlacement nextPilePlacement() {
            Gob player = gui.map.player();
            if(player == null || player.rc == null) return null;
            ObjectOrganizer.ExactPlan plan = StockpilePlacement.planExact(
                gui, product.stockpile, output, 1);
            if(plan == null) {
                diag("PILE plan failed reason=no-occupancy");
                return null;
            }
            diag("PILE exact-plan reason=%s footprint=%.3fx%.3f gap=%.3f placements=%d",
                plan.reason,
                plan.footprint == null || plan.footprint.bounds == null ? 0.0 : plan.footprint.bounds.width(),
                plan.footprint == null || plan.footprint.bounds == null ? 0.0 : plan.footprint.bounds.height(),
                plan.gap, plan.placements == null ? 0 : plan.placements.size());
            return plan.placements == null || plan.placements.isEmpty() ? null : plan.placements.get(0);
        }

        private Window openWindow(Gob gob) throws InterruptedException {
            Set<Widget> before = widgets(gui);
            Coord mc = gob.rc.floor(OCache.posres);
            gui.map.wdgmsg("click", Coord.z, mc, 3, 0, 0, (int)gob.id, mc, 0, -1);
            final Window[] result = new Window[1];
            waitFor(STEP_TIMEOUT, () -> {result[0] = newWidget(gui, Window.class, before); return result[0] != null;});
            return result[0];
        }

        private boolean approach(Gob gob) throws InterruptedException {
            lastApproachFailure = "";
            status = "Status: walking to " + safeRes(gob);
            Gob player = gui.map.player();
            if(player != null && player.rc != null && gob != null && gob.rc != null) {
                double distance = player.rc.dist(gob.rc);
                // Immediately after dropping a carried log, the player is normally
                // already beside (or visually over) its long collision footprint.
                // Asking the object approach planner to route from that occupied cell correctly
                // reports UNREACHABLE even though the gob is directly interactable.
                if(distance <= MCache.tilesz.x * 1.5) {
                    waitForMovementSettled(player);
                    diag("APPROACH gob=%d res=%s already-near distance=%.2f", gob.id, safeRes(gob), distance);
                    return true;
                }
            }
            BotMovement.Result r = BotMovement.approach(gui, bot, gob, BotMovement.Mode.LAND);
            boolean ok = r != null && r.status == BotMovement.Status.READY_TO_INTERACT;
            if(ok) {
                waitForMovementSettled(gui.map.player());
            } else {
                String reason = r == null ? "no movement result" : r.status.name()
                    + (r.detail.isEmpty() ? "" : ": " + r.detail);
                lastApproachFailure = " (" + reason + ")";
            }
            diag("APPROACH gob=%d res=%s result=%s detail=%s end=%s goal=%s replans=%d",
                gob.id, safeRes(gob), r == null ? "null" : r.status,
                r == null ? "" : r.detail, r == null ? null : r.end,
                r == null ? null : r.selectedGoal, r == null ? -1 : r.replans);
            return ok;
        }

        private void waitForMovementSettled(Gob player) throws InterruptedException {
            long end = System.currentTimeMillis() + 3000L;
            while(player != null && player.getattr(Moving.class) != null && System.currentTimeMillis() < end) {
                bot.checkCancelled();
                Thread.sleep(50L);
            }
        }

        private List<WItem> productItems() {
            List<WItem> out = new ArrayList<>();
            if(gui.maininv != null) for(WItem w : gui.maininv.children(WItem.class)) if(WoodCutRules.isProduct(safeRes(w.item), product)) out.add(w);
            return out;
        }

        private WItem firstProduct() {List<WItem> items = productItems(); return items.isEmpty() ? null : items.get(0);}
        private int countProducts() {return productItems().size();}
        private int countPendingProducts() {return countProducts() + (hasHeldProduct() ? 1 : 0);}
        private boolean hasHeldProduct() {
            GameUI.DraggedItem held = gui.hand();
            return held != null && WoodCutRules.isProduct(safeRes(held.item), product);
        }

        private Set<Widget> widgets(Widget root) {
            Set<Widget> out = new HashSet<>();
            if(root == null) return out;
            for(Widget w = root.lchild; w != null; w = w.prev) {out.add(w); out.addAll(widgets(w));}
            return out;
        }

        private <T extends Widget> T newWidget(Widget root, Class<T> type, Set<Widget> before) {
            if(root == null) return null;
            for(Widget w = root.lchild; w != null; w = w.prev) {
                if(type.isInstance(w) && !before.contains(w)) return type.cast(w);
                T nested = newWidget(w, type, before);
                if(nested != null) return nested;
            }
            return null;
        }

        private <T extends Widget> List<T> allWidgets(Widget root, Class<T> type) {
            List<T> out = new ArrayList<>();
            if(root == null) return out;
            for(Widget w = root.lchild; w != null; w = w.prev) {
                if(type.isInstance(w)) out.add(type.cast(w));
                out.addAll(allWidgets(w, type));
            }
            return out;
        }

        private interface Check {boolean get();}
        private boolean waitFor(long timeout, Check check) throws InterruptedException {
            long end = System.currentTimeMillis() + timeout;
            while(System.currentTimeMillis() < end) {bot.checkCancelled(); if(check.get()) return true; Thread.sleep(75L);}
            return check.get();
        }

        private void done(String message) throws StopRun {throw new StopRun(message, true);}
        private void fail(String message) throws StopRun {throw new StopRun(message, false);}
    }
}
