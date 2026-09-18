package auto;

import haven.Area;
import haven.Coord;
import haven.Coord2d;
import haven.Debug;
import haven.GameUI;
import haven.GItem;
import haven.Gob;
import haven.GobTag;
import haven.IMeter;
import haven.Loading;
import haven.MCache;
import haven.pathfinding.BotMovement;
import haven.pathfinding.PathfinderLog;
import haven.rx.Reactor;
import thunder.mining.MinerBotV3ZoneStore;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Miner Bot V3: fixed eleven-tile straight exploratory mining. */
public final class MinerBotV3 {
    private static final long MINE_TOTAL_TIMEOUT_MS = 10 * 60 * 1000L;
    private static final long MINE_START_TIMEOUT_MS = 5000L;
    private static final long MINE_QUIET_MS = 2500L;
    private static final double LOOSE_STONE_SCAN_RADIUS = MCache.tilesz.x * 8.0;
    private static final int TRAIL_LEG_TILES = 8;

    private static volatile boolean running;
    private static volatile String status = "Status: idle";
    private static Bot active;
    private static PrintWriter log;

    private MinerBotV3() {}

    public static boolean isRunning() {return running;}
    public static String status() {return status;}

    public static synchronized void start(GameUI gui, MinerBotV3Logic.Direction direction,
                                          int barsTarget, int segmentCap) {
        if(running) {gui.error("Miner Bot V3 is already running."); return;}
        if(gui == null || gui.map == null || gui.menu == null || gui.map.player() == null) return;
        if(Bot.hasCurrent()) {gui.error("Miner Bot V3: another automation task is already running."); return;}
        if(direction == null) {gui.error("Miner Bot V3: choose a direction."); return;}
        if(barsTarget < 1) {gui.error("Miner Bot V3: bars to carry must be at least 1."); return;}
        if(segmentCap < 0) {gui.error("Miner Bot V3: safety cap must be 0 or greater."); return;}

        MinerBotV3Navigator.Context context = MinerBotV3Navigator.context(gui);
        if(context == null) {
            gui.error("Miner Bot V3: the saved cave map is not ready yet.");
            return;
        }
        MinerBotV3ZoneStore store = MinerBotV3ZoneStore.get();
        store.bind(gui.ui.sess);
        for(String role : zoneRoles()) {
            MinerBotV3ZoneStore.SavedArea area = store.get(gui.ui.sess, role);
            if(area != null && area.segmentId != context.segment) {
                gui.error("Miner Bot V3: " + roleName(role) + " is on another map segment; reselect it on this cave level.");
                return;
            }
            if(area == null)
                gui.msg("Miner Bot V3 warning: no " + roleName(role) + " selected; the run will stop if it becomes necessary.",
                    GameUI.MsgType.INFO);
        }

        Gob player = gui.map.player();
        Gob support = MiningBot.findNearestSupport(gui, player);
        if(support == null) {
            gui.error("Miner Bot V3: no mine support is visible; start within sight of an existing support.");
            return;
        }
        Coord origin = support.rc.floor(MCache.tilesz).sub(direction.right().step());
        while(true) {
            Coord expected = MinerBotV3Logic.columnTile(origin, direction);
            if(MiningBot.findSupportNear(gui, expected) == null) break;
            origin = MinerBotV3Logic.endpoint(origin, direction);
        }

        int cap = segmentCap <= 0 ? Integer.MAX_VALUE : segmentCap;
        final Coord start = new Coord(origin);
        final int targetBars = barsTarget;
        running = true;
        status = "Status: starting";
        openLog();
        diag("START direction=%s origin=%s bars=%d cap=%s segment=%x",
            direction, start, targetBars, cap == Integer.MAX_VALUE ? "unlimited" : Integer.toString(cap), context.segment);
        Bot task = Bot.execute((ignored, bot) -> {
            try {
                MiningBot.prewarmSupportResource(gui, bot);
                new Run(gui, bot, direction, targetBars, cap, context.segment, start).execute();
            } finally {
                running = false;
                active = null;
                if(status.startsWith("Status: stopping —"))
                    status = "Status: stopped —" + status.substring("Status: stopping —".length());
                else if(!status.startsWith("Status: complete") && !status.startsWith("Status: stopped"))
                    status = "Status: stopped";
                diag("STOP %s", status);
                closeLog();
            }
        });
        active = task;
        task.start(gui.ui, true);
    }

    public static synchronized void stop() {abort("Stopped by user.");}

    public static synchronized void abort(String reason) {
        Bot bot = active;
        if(bot != null && running) {
            status = "Status: stopping — " + reason;
            bot.cancel(reason);
        }
    }

    static synchronized void diag(String format, Object... args) {
        String line = String.format(Locale.ROOT, "[miner-v3] " + format, args);
        Debug.log.println(line);
        Debug.log.flush();
        if(log != null) {log.println(line); log.flush();}
    }

    static void logMovement(String operation, String phase, BotMovement.Result result) {
        if(result == null) {
            diag("MOVE op=%s phase=%s result=null", operation, phase);
            return;
        }
        diag("MOVE op=%s phase=%s status=%s end=%s selected=%s replans=%d detail=%s",
            operation, phase, result.status, result.end, result.selectedGoal,
            result.replans, result.detail);
    }

    private static void openLog() {
        try {
            File dir = Debug.somedir("minerbot-v3-logs").toFile();
            dir.mkdirs();
            log = new PrintWriter(new FileWriter(new File(dir,
                "minerbot-v3-" + System.currentTimeMillis() + ".log"), true), true);
        } catch(Exception failure) {log = null;}
    }

    private static void closeLog() {
        if(log != null) {log.close(); log = null;}
    }

    private static List<String> zoneRoles() {
        List<String> roles = new ArrayList<>();
        roles.add(MinerBotV3ZoneStore.ROLE_STORAGE);
        roles.add(MinerBotV3ZoneStore.ROLE_WATER);
        roles.add(MinerBotV3ZoneStore.ROLE_FOOD);
        return roles;
    }

    private static String roleName(String role) {
        if(MinerBotV3ZoneStore.ROLE_STORAGE.equals(role)) return "stone/bar storage area";
        if(MinerBotV3ZoneStore.ROLE_WATER.equals(role)) return "water area";
        return "food area";
    }

    private enum LegOutcome {SUCCESS, TOO_HARD, FAILED}
    private enum MineOutcome {STOPPED, TOO_HARD, SUPPLY_NEEDED, ARM_FAILED}
    private enum Service {WATER, FOOD, STORAGE}

    private static final class Anchor {
        final Coord tile;
        final int trailIndex;

        Anchor(Coord tile, int trailIndex) {
            this.tile = new Coord(tile);
            this.trailIndex = trailIndex;
        }
    }

    /** Internal normal completion used when a dogleg reaches the exact column cap. */
    private static final class SafetyCapReached extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private static final class Run {
        final GameUI gui;
        final Bot bot;
        final MinerBotV3Logic.Direction originalDirection;
        final int barsTarget;
        final int segmentCap;
        final long segmentId;
        final List<Coord> trail = new ArrayList<>();
        final List<Anchor> anchors = new ArrayList<>();
        int placements;

        Run(GameUI gui, Bot bot, MinerBotV3Logic.Direction originalDirection,
            int barsTarget, int segmentCap, long segmentId, Coord origin) {
            this.gui = gui;
            this.bot = bot;
            this.originalDirection = originalDirection;
            this.barsTarget = barsTarget;
            this.segmentCap = segmentCap;
            this.segmentId = segmentId;
            trail.add(new Coord(origin));
            anchors.add(new Anchor(origin, 0));
        }

        void execute() throws InterruptedException {
            if(!walkToTile(trail.get(0), "initial support anchor"))
                fail("could not reach the initial support anchor " + trail.get(0));
            Coord anchor = new Coord(trail.get(0));
            try {
                while(placements < segmentCap) {
                    bot.checkCancelled();
                    status = String.format(Locale.ROOT,
                        "Status: running straight — columns %d%s", placements,
                        segmentCap == Integer.MAX_VALUE ? "" : "/" + segmentCap);
                    ensureSupplyCircuit(false);
                    LegOutcome result = mineAndPlaceLeg(anchor, originalDirection);
                    if(result == LegOutcome.SUCCESS) {
                        anchor = MinerBotV3Logic.endpoint(anchor, originalDirection);
                        continue;
                    }
                    if(result == LegOutcome.FAILED)
                        fail("straight leg from " + anchor + " failed without a too-hard response");
                    Coord bypass = recoverTooHard(anchor);
                    if(bypass == null)
                        fail("all eight bounded too-hard dogleg candidates failed");
                    anchor = bypass;
                }
            } catch(SafetyCapReached reached) {}
            status = "Status: complete — safety cap reached after " + placements + " columns";
            gui.msg("Miner Bot V3 finished: safety cap reached after " + placements + " columns.",
                GameUI.MsgType.GOOD);
        }

        private LegOutcome mineAndPlaceLeg(Coord anchor, MinerBotV3Logic.Direction heading)
                throws InterruptedException {
            if(placements >= segmentCap) throw new SafetyCapReached();
            status = "Status: mining " + heading.name().toLowerCase(Locale.ROOT) + " from " + anchor;
            LegOutcome line = completeLine(anchor, heading);
            if(line != LegOutcome.SUCCESS) return line;

            Coord endpoint = MinerBotV3Logic.endpoint(anchor, heading);
            Coord column = MinerBotV3Logic.columnTile(anchor, heading);
            LegOutcome pocket = minePocket(column);
            if(pocket != LegOutcome.SUCCESS) return pocket;

            ensureSupplyCircuit(true);
            if(MiningMaterials.stoneCount(gui) < MinerBotV3Logic.COLUMN_STONES)
                return failLeg("only " + MiningMaterials.stoneCount(gui) + "/" + MinerBotV3Logic.COLUMN_STONES + " building stones available");
            if(MiningMaterials.hardBarCount(gui) < barsTarget)
                return failLeg("only " + MiningMaterials.hardBarCount(gui) + "/" + barsTarget + " Bronze/Wrought bars available");

            status = "Status: placing column at " + column;
            MiningBot.waitForCommandQueueIdle(gui, bot, 15000L);
            MiningBot.waitForMovementSettled(gui, bot, 3000L);
            if(!MiningBot.placeSupport(gui, bot, MiningBot.tileCenter(column)))
                return failLeg("column placement failed at " + column);
            if(!walkToTile(endpoint, "return to tunnel centerline"))
                return failLeg("could not return to centerline " + endpoint + " after placement");

            if(trail.isEmpty() || !trail.get(trail.size() - 1).equals(endpoint))
                trail.add(new Coord(endpoint));
            anchors.add(new Anchor(endpoint, trail.size() - 1));
            placements++;
            diag("COLUMN placed=%s anchor=%s heading=%s count=%d", column, endpoint, heading, placements);
            return LegOutcome.SUCCESS;
        }

        private LegOutcome completeLine(Coord anchor, MinerBotV3Logic.Direction heading)
                throws InterruptedException {
            int completed = 0;
            MinerBotV3Logic.RedrawProgress progress = new MinerBotV3Logic.RedrawProgress(completed);
            while(completed < MinerBotV3Logic.LEG_TILES) {
                bot.checkCancelled();
                ensureSupplyCircuit(false);
                completed = advanceOpenPrefix(anchor, heading, completed);
                if(completed >= MinerBotV3Logic.LEG_TILES) return LegOutcome.SUCCESS;

                MinerBotV3Logic.Line line = MinerBotV3Logic.remainingLine(anchor, heading, completed);
                MineOutcome outcome = submitMineArea(line.start, line.end,
                    () -> openPrefix(anchor, heading) >= MinerBotV3Logic.LEG_TILES,
                    true, "line " + line.start + ".." + line.end);
                if(outcome == MineOutcome.TOO_HARD) return LegOutcome.TOO_HARD;
                if(outcome == MineOutcome.ARM_FAILED) return failLeg("Mine action did not arm for " + line.start + ".." + line.end);
                if(outcome == MineOutcome.SUPPLY_NEEDED) {
                    ensureSupplyCircuit(false);
                    continue;
                }

                completed = advanceOpenPrefix(anchor, heading, completed);
                if(progress.failedAfter(completed)) {
                    diag("LINE no-progress anchor=%s heading=%s completed=%d redraws=%d",
                        anchor, heading, completed, progress.noProgressCount());
                    return LegOutcome.FAILED;
                }
            }
            return LegOutcome.SUCCESS;
        }

        private LegOutcome minePocket(Coord tile) throws InterruptedException {
            if(MapHelper.isMinedFloorTile(gui, tile)) return LegOutcome.SUCCESS;
            for(int attempt = 1; attempt <= MinerBotV3Logic.MAX_NO_PROGRESS_REDRAWS; attempt++) {
                MineOutcome outcome = submitMineArea(tile, tile,
                    () -> MapHelper.isMinedFloorTile(gui, tile), true,
                    "column pocket " + tile + " attempt " + attempt);
                if(outcome == MineOutcome.TOO_HARD) return LegOutcome.TOO_HARD;
                if(outcome == MineOutcome.SUPPLY_NEEDED) {ensureSupplyCircuit(false); continue;}
                if(MapHelper.isMinedFloorTile(gui, tile)) return LegOutcome.SUCCESS;
            }
            return failLeg("column pocket did not open at " + tile);
        }

        private MineOutcome submitMineArea(Coord start, Coord end, BooleanSupplier complete,
                                           boolean monitorSupplies, String phase)
                throws InterruptedException {
            MiningBot.dropCursorItem(gui);
            gui.menu.wdgmsg("act", "mine", gui.ui.modflags());
            if(!waitFor(MINE_START_TIMEOUT_MS, gui.map::hasActiveSelector)) {
                diag("MINE phase=%s armed=false", phase);
                return MineOutcome.ARM_FAILED;
            }

            AtomicBoolean tooHard = new AtomicBoolean();
            rx.Subscription errors = Reactor.EMSG.subscribe(message -> {
                if(MinerBotV3Logic.tooHardMessage(message)) tooHard.set(true);
            });
            rx.Subscription infos = Reactor.IMSG.subscribe(message -> {
                if(MinerBotV3Logic.tooHardMessage(message)) tooHard.set(true);
            });
            boolean supply = false;
            try {
                gui.map.commitAreaSelection(start, end, gui.ui.modflags());
                diag("MINE phase=%s selection=%s..%s", phase, start, end);
                long started = System.currentTimeMillis();
                long lastActivity = started;
                boolean sawActivity = false;
                boolean wasProgress = gui.prog != null;
                if(wasProgress) sawActivity = true;
                while(!complete.getAsBoolean()) {
                    bot.checkCancelled();
                    long now = System.currentTimeMillis();
                    if(tooHard.get()) {
                        diag("MINE phase=%s result=too-hard", phase);
                        return MineOutcome.TOO_HARD;
                    }
                    boolean progress = gui.prog != null;
                    if(progress != wasProgress) {
                        sawActivity = true;
                        lastActivity = now;
                        wasProgress = progress;
                    }
                    if(progress) sawActivity = true;
                    if(monitorSupplies && !progress && immediateSupplyNeeded()) {
                        supply = true;
                        break;
                    }
                    if(now - started >= MINE_TOTAL_TIMEOUT_MS) break;
                    if(!progress && !sawActivity && now - started >= MINE_START_TIMEOUT_MS) break;
                    if(!progress && sawActivity && now - lastActivity >= MINE_QUIET_MS) break;
                    Thread.sleep(50L);
                }
            } finally {
                errors.unsubscribe();
                infos.unsubscribe();
                BotUtil.rclick(gui);
            }
            diag("MINE phase=%s result=%s", phase, supply ? "supply-needed" : "stopped");
            return supply ? MineOutcome.SUPPLY_NEEDED : MineOutcome.STOPPED;
        }

        private int openPrefix(Coord anchor, MinerBotV3Logic.Direction heading) {
            Coord step = heading.step();
            int open = 0;
            for(int i = 1; i <= MinerBotV3Logic.LEG_TILES; i++) {
                if(!MapHelper.isMinedFloorTile(gui, anchor.add(step.mul(i)))) break;
                open = i;
            }
            return open;
        }

        private int advanceOpenPrefix(Coord anchor, MinerBotV3Logic.Direction heading,
                                      int completed) throws InterruptedException {
            int open = openPrefix(anchor, heading);
            if(open <= completed) return completed;
            Coord step = heading.step();
            for(int candidate = open; candidate > completed; candidate--) {
                Coord tile = anchor.add(step.mul(candidate));
                if(!walkToTile(tile, "advance mined line")) continue;
                for(int i = completed + 1; i <= candidate; i++) appendTrail(anchor.add(step.mul(i)));
                diag("LINE advance anchor=%s heading=%s before=%d after=%d", anchor, heading, completed, candidate);
                return candidate;
            }
            return completed;
        }

        private Coord recoverTooHard(Coord failedAnchor) throws InterruptedException {
            diag("DETOUR begin failed-anchor=%s anchors=%d trail=%d", failedAnchor, anchors.size(), trail.size());
            List<Anchor> baseline = new ArrayList<>(anchors);
            for(MinerBotV3Logic.DetourCandidate candidate : MinerBotV3Logic.detourCandidates()) {
                int baseIndex = baseline.size() - 1 - candidate.retreatLegs;
                if(baseIndex < 0) {
                    diag("DETOUR skip=%s reason=no-prior-anchor", candidate);
                    continue;
                }
                Anchor base = baseline.get(baseIndex);
                if(!returnAndResetTo(base.tile, baseline, baseIndex))
                    fail("could not return to dogleg base " + base.tile);
                diag("DETOUR try=%s base=%s", candidate, base.tile);
                MinerBotV3Logic.Direction side = candidate.heading(originalDirection);
                Coord at = new Coord(base.tile);
                boolean candidateHard = false;
                for(int leg = 0; leg < candidate.sideLegs; leg++) {
                    LegOutcome lateral = mineAndPlaceLeg(at, side);
                    if(lateral == LegOutcome.FAILED)
                        fail("dogleg " + candidate + " failed while mining sideways from " + at);
                    if(lateral == LegOutcome.TOO_HARD) {candidateHard = true; break;}
                    at = MinerBotV3Logic.endpoint(at, side);
                }
                if(candidateHard) continue;
                LegOutcome forward = mineAndPlaceLeg(at, originalDirection);
                if(forward == LegOutcome.SUCCESS) {
                    Coord resumed = MinerBotV3Logic.endpoint(at, originalDirection);
                    diag("DETOUR success=%s resumed=%s", candidate, resumed);
                    return resumed;
                }
                if(forward == LegOutcome.FAILED)
                    fail("dogleg " + candidate + " failed while testing the resumed forward lane");
            }
            return null;
        }

        private boolean returnAndResetTo(Coord base, List<Anchor> baseline, int baseIndex)
                throws InterruptedException {
            int trailIndex = lastTrailIndex(base);
            if(trailIndex < 0 || !walkTrailTo(trailIndex)) return false;
            while(trail.size() > trailIndex + 1) trail.remove(trail.size() - 1);
            anchors.clear();
            for(int i = 0; i <= baseIndex; i++) anchors.add(baseline.get(i));
            return true;
        }

        private void ensureSupplyCircuit(boolean requireStones) throws InterruptedException {
            if(requireStones && MiningMaterials.stoneCount(gui) < MinerBotV3Logic.COLUMN_STONES)
                collectStonesAlongTrail();

            boolean waterEmpty = !MiningBot.hasAnyDrink(gui);
            boolean energyLow = energyLow();
            boolean stoneLow = MiningMaterials.stoneCount(gui) < MinerBotV3Logic.COLUMN_STONES;
            boolean barsLow = MinerBotV3Logic.needsBars(MiningMaterials.hardBarCount(gui), barsTarget);
            boolean triggered = waterEmpty || energyLow || barsLow || (requireStones && stoneLow);
            if(!triggered) return;

            MinerBotV3Navigator.Context frontier = MinerBotV3Navigator.context(gui);
            if(frontier == null || frontier.segment != segmentId)
                fail("saved-map position became unavailable before resupply");
            MinerBotV3ZoneStore.SavedArea water = zone(MinerBotV3ZoneStore.ROLE_WATER);
            MinerBotV3ZoneStore.SavedArea food = zone(MinerBotV3ZoneStore.ROLE_FOOD);
            MinerBotV3ZoneStore.SavedArea storage = zone(MinerBotV3ZoneStore.ROLE_STORAGE);

            if(waterEmpty && water == null) fail("no water remains and no water area is selected");
            if(energyLow && food == null) fail("energy fell below 2,500% and no food area is selected");
            if((stoneLow || barsLow) && storage == null)
                fail("stone/bar reserves are short and no storage area is selected");

            List<Service> pending = new ArrayList<>();
            if(water != null && !allDrinkVesselsFull()) pending.add(Service.WATER);
            if(energyLow) pending.add(Service.FOOD);
            if(stoneLow || barsLow) pending.add(Service.STORAGE);
            diag("SUPPLY trigger water-empty=%b energy-low=%b stone=%d/%d bars=%d/%d pending=%s frontier=%s",
                waterEmpty, energyLow, MiningMaterials.stoneCount(gui), MinerBotV3Logic.COLUMN_STONES,
                MiningMaterials.hardBarCount(gui), barsTarget, pending, frontier.savedTile);

            while(!pending.isEmpty()) {
                Service next = closest(pending, water, food, storage);
                MinerBotV3ZoneStore.SavedArea target = serviceArea(next, water, food, storage);
                status = "Status: resupplying — " + next.name().toLowerCase(Locale.ROOT);
                if(!MinerBotV3Navigator.moveToArea(gui, bot, target, "resupply " + next))
                    fail("could not route to the selected " + next.name().toLowerCase(Locale.ROOT) + " area");
                MinerBotV3Navigator.Context at = MinerBotV3Navigator.context(gui);
                Area live = at == null ? null : target.liveArea(at.sessionTile);
                if(live == null) fail("could not convert the " + next + " area into the live cave map");
                boolean ok;
                switch(next) {
                case WATER:
                    ok = MiningMaterials.refillWaterFromZone(gui, bot, live) && allDrinkVesselsFull();
                    break;
                case FOOD:
                    ok = MiningMaterials.eatFromZone(gui, bot, live, MinerBotV3Logic.EAT_UNTIL_PERCENT)
                        && MinerBotV3Logic.energyTargetReached(energy());
                    break;
                default:
                    ok = true;
                    if(MiningMaterials.stoneCount(gui) < MinerBotV3Logic.COLUMN_STONES)
                        ok = MiningMaterials.fetchFromZone(gui, bot, live,
                            MiningMaterials::isBuildingStone, MinerBotV3Logic.COLUMN_STONES);
                    if(MiningMaterials.hardBarCount(gui) < barsTarget)
                        ok = MiningMaterials.fetchFromZone(gui, bot, live,
                            MiningMaterials::isHardBar, barsTarget) && ok;
                    if(gui.maininv != null)
                        ok = StackAllItems.stackMatchingNow(gui.maininv, MiningMaterials::isHardBar) && ok;
                    ok = ok && MiningMaterials.stoneCount(gui) >= MinerBotV3Logic.COLUMN_STONES
                        && MiningMaterials.hardBarCount(gui) >= barsTarget;
                    break;
                }
                diag("SUPPLY service=%s ok=%b water-full=%b energy=%.4f stone=%d bars=%d",
                    next, ok, allDrinkVesselsFull(), energy(), MiningMaterials.stoneCount(gui),
                    MiningMaterials.hardBarCount(gui));
                if(!ok) fail("the selected " + next.name().toLowerCase(Locale.ROOT) + " area could not satisfy the V3 target");
                pending.remove(next);
            }

            status = "Status: returning to mining frontier";
            if(!MinerBotV3Navigator.moveToTile(gui, bot, frontier.segment, frontier.savedTile,
                "return to mining frontier"))
                fail("could not return to the recorded mining frontier " + frontier.savedTile);
        }

        private Service closest(List<Service> pending,
                                MinerBotV3ZoneStore.SavedArea water,
                                MinerBotV3ZoneStore.SavedArea food,
                                MinerBotV3ZoneStore.SavedArea storage) {
            Service best = null;
            double distance = Double.POSITIVE_INFINITY;
            for(Service service : pending) {
                double candidate = MinerBotV3Navigator.routeDistance(gui,
                    serviceArea(service, water, food, storage));
                if(candidate < distance - 0.0001
                    || (Math.abs(candidate - distance) <= 0.0001 && priority(service) < priority(best))) {
                    best = service;
                    distance = candidate;
                }
            }
            return best == null ? pending.get(0) : best;
        }

        private int priority(Service service) {
            if(service == null) return Integer.MAX_VALUE;
            if(service == Service.WATER) return 0;
            if(service == Service.FOOD) return 1;
            return 2;
        }

        private MinerBotV3ZoneStore.SavedArea serviceArea(Service service,
                MinerBotV3ZoneStore.SavedArea water,
                MinerBotV3ZoneStore.SavedArea food,
                MinerBotV3ZoneStore.SavedArea storage) {
            if(service == Service.WATER) return water;
            if(service == Service.FOOD) return food;
            return storage;
        }

        private MinerBotV3ZoneStore.SavedArea zone(String role) {
            return MinerBotV3ZoneStore.get().get(gui.ui.sess, role);
        }

        private void collectStonesAlongTrail() throws InterruptedException {
            if(trail.isEmpty()) return;
            int frontier = trail.size() - 1;
            int at = frontier;
            collectVisibleStones();
            while(MiningMaterials.stoneCount(gui) < MinerBotV3Logic.COLUMN_STONES && at > 0) {
                at = Math.max(0, at - TRAIL_LEG_TILES);
                if(!walkTrailTo(at)) break;
                collectVisibleStones();
            }
            if(!walkTrailTo(frontier))
                fail("could not return to the work frontier after collecting loose stone");
            diag("STONE route-search result=%d/%d", MiningMaterials.stoneCount(gui),
                MinerBotV3Logic.COLUMN_STONES);
        }

        private void collectVisibleStones() throws InterruptedException {
            Set<Long> tried = new HashSet<>();
            while(MiningMaterials.stoneCount(gui) < MinerBotV3Logic.COLUMN_STONES) {
                bot.checkCancelled();
                Gob player = gui.map.player();
                if(player == null || player.rc == null) return;
                Gob stone;
                synchronized(gui.ui.sess.glob.oc) {
                    stone = gui.ui.sess.glob.oc.stream()
                        .filter(g -> g != null && !g.disposed() && g.is(GobTag.PICKUP))
                        .filter(MiningMaterials::looksLikeStone)
                        .filter(g -> g.rc != null && g.rc.dist(player.rc) <= LOOSE_STONE_SCAN_RADIUS)
                        .filter(g -> !tried.contains(g.id))
                        .min(Comparator.comparingDouble(g -> g.rc.dist(player.rc))).orElse(null);
                }
                if(stone == null) return;
                tried.add(stone.id);
                BotMovement.Result move = MiningBot.moveToPoint(gui, bot, stone.rc,
                    "V3 loose stone #" + stone.id);
                logMovement("moveTo", "loose stone #" + stone.id, move);
                if(move == null || !move.arrived() || stone.disposed()) continue;
                boolean picked = Actions.pickupTarget(new GobTarget(stone), bot, 15000L);
                diag("STONE pickup id=%d picked=%b count=%d", stone.id, picked,
                    MiningMaterials.stoneCount(gui));
            }
        }

        private boolean walkTrailTo(int targetIndex) throws InterruptedException {
            if(targetIndex < 0 || targetIndex >= trail.size()) return false;
            Gob player = gui.map.player();
            if(player == null || player.rc == null) return false;
            int current = nearestTrailIndex(player.rc);
            for(int next : MinerBotV3Logic.trailStops(current, targetIndex, TRAIL_LEG_TILES)) {
                if(!walkToTile(trail.get(next), "known mining trail")) return false;
            }
            return true;
        }

        private int nearestTrailIndex(Coord2d world) {
            int best = 0;
            double distance = Double.POSITIVE_INFINITY;
            for(int i = 0; i < trail.size(); i++) {
                double d = MiningBot.tileCenter(trail.get(i)).dist(world);
                if(d < distance) {distance = d; best = i;}
            }
            return best;
        }

        private int lastTrailIndex(Coord tile) {
            for(int i = trail.size() - 1; i >= 0; i--)
                if(trail.get(i).equals(tile)) return i;
            return -1;
        }

        private void appendTrail(Coord tile) {
            if(trail.isEmpty() || !trail.get(trail.size() - 1).equals(tile))
                trail.add(new Coord(tile));
        }

        private boolean walkToTile(Coord tile, String phase) throws InterruptedException {
            PathfinderLog.setTarget("miner-v3 " + phase);
            try {
                BotMovement.Result result = BotMovement.moveTo(gui, bot,
                    MiningBot.tileCenter(tile), BotMovement.Mode.CAVE);
                logMovement("moveTo", phase + " " + tile, result);
                return result != null && result.arrived();
            } finally {
                PathfinderLog.clearTarget();
            }
        }

        private boolean immediateSupplyNeeded() {
            return !MiningBot.hasAnyDrink(gui) || energyLow();
        }

        private boolean energyLow() {
            return MinerBotV3Logic.needsEnergy(energy());
        }

        private double energy() {
            IMeter meter = gui.getIMeter("nrj");
            return meter == null ? -1.0 : meter.meter(0);
        }

        private boolean allDrinkVesselsFull() {
            List<InvHelper.ContainedItem> vessels = Stream.of(
                InvHelper.POUCHES_CONTAINED(gui).get().stream().filter(InvHelper::isDrinkContainer),
                InvHelper.INVENTORY_CONTAINED(gui).get().stream().filter(InvHelper::isDrinkContainer),
                InvHelper.BELT_CONTAINED(gui).get().stream().filter(InvHelper::isDrinkContainer),
                InvHelper.HANDS_CONTAINED(gui).get().stream()
                    .filter(ci -> InvHelper.isDrinkContainer(ci) || InvHelper.isBucket(ci))
            ).flatMap(stream -> stream).collect(Collectors.toList());
            Set<GItem> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            boolean any = false;
            for(InvHelper.ContainedItem vessel : vessels) {
                if(!seen.add(vessel.item.item)) continue;
                any = true;
                try {
                    if(InvHelper.isNotFull(vessel) || !InvHelper.HAS_WATER.test(vessel.item)) return false;
                } catch(Loading loading) {return false;}
            }
            return any;
        }

        private boolean waitFor(long timeoutMs, BooleanSupplier condition) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while(!condition.getAsBoolean()) {
                bot.checkCancelled();
                if(System.currentTimeMillis() >= deadline) return false;
                Thread.sleep(50L);
            }
            return true;
        }

        private LegOutcome failLeg(String reason) {
            diag("LEG failed reason=%s", reason);
            return LegOutcome.FAILED;
        }

        private void fail(String reason) throws InterruptedException {
            diag("FAIL %s", reason);
            status = "Status: stopped — " + reason;
            gui.error("Miner Bot V3 stopped: " + reason);
            bot.cancel(reason);
            bot.checkCancelled();
            throw new InterruptedException(reason);
        }
    }
}
