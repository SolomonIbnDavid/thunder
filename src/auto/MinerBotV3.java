package auto;

import haven.Area;
import haven.Coord;
import haven.Coord2d;
import haven.Debug;
import haven.FlowerMenu;
import haven.GameUI;
import haven.GItem;
import haven.Gob;
import haven.GobTag;
import haven.IMeter;
import haven.Loading;
import haven.MCache;
import haven.WItem;
import haven.Widget;
import haven.pathfinding.BotMovement;
import haven.pathfinding.PathfinderLog;
import haven.rx.Reactor;
import thunder.mining.MinerBotV3AnchorStore;
import thunder.mining.MinerBotV3ZoneStore;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
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
    private static final int BOULDER_MAX_CHIPS = 500;
    private static final int BOULDER_MAX_ATTEMPTS = 3;
    private static final int BOULDER_MAX_ROCK_DROPS = 600;
    private static final long BOULDER_STEP_TIMEOUT_MS = 6000L;
    private static final long BOULDER_CHIP_TIMEOUT_MS = 120000L;
    private static final double BOULDER_DIRECT_CLICK_RADIUS = MCache.tilesz.x * 5.0;
    private static final double BOULDER_APPROACH_RADIUS = MCache.tilesz.x * 1.25;

    private static volatile boolean running;
    private static volatile String status = "Status: idle";
    private static Bot active;
    private static PrintWriter log;

    private MinerBotV3() {}

    public static boolean isRunning() {return running;}
    public static String status() {return status;}

    public static synchronized void start(GameUI gui, MinerBotV3Logic.Direction direction,
                                          int barsTarget, int segmentCap, boolean fanning) {
        if(running) {gui.error("Miner Bot V3 is already running."); return;}
        if(gui == null || gui.map == null || gui.menu == null || gui.map.player() == null) return;
        if(Bot.hasCurrent()) {gui.error("Miner Bot V3: another automation task is already running."); return;}
        if(direction == null) {gui.error("Miner Bot V3: choose a direction."); return;}
        if(barsTarget < 1) {gui.error("Miner Bot V3: bar refill target must be at least 1."); return;}
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

        MinerBotV3AnchorStore anchorStore = MinerBotV3AnchorStore.get();
        anchorStore.bind(gui.ui.sess);
        MinerBotV3AnchorStore.SavedAnchor checkpoint = anchorStore.get(gui.ui.sess);
        if(checkpoint != null && checkpoint.segmentId != context.segment) {
            gui.error("Miner Bot V3: the locked anchor is on another map segment; return to that cave level or clear the anchor.");
            return;
        }

        StartPlan plan = resolveStartPlan(gui, direction);
        if(plan == null) {
            gui.error("Miner Bot V3: no mine support is visible; start within sight of an existing support.");
            return;
        }
        if(!plan.locked) {
            anchorStore.put(gui.ui.sess, new MinerBotV3AnchorStore.SavedAnchor(
                plan.segmentId, plan.savedAnchor, plan.heading, false));
        }

        int cap = segmentCap <= 0 ? Integer.MAX_VALUE : segmentCap;
        final Coord start = new Coord(plan.anchor);
        final Coord savedStart = new Coord(plan.savedAnchor);
        final MinerBotV3Logic.Direction runDirection = plan.heading;
        final int targetBars = barsTarget;
        running = true;
        status = "Status: starting";
        MinerBotV3Overlay.showStart(gui, plan, runDirection, "starting", null, fanning);
        openLog();
        diag("START direction=%s origin=%s saved-origin=%s anchor-source=%s bars=%d cap=%s fanning=%b segment=%x",
            runDirection, start, savedStart, plan.anchorSource(), targetBars,
            cap == Integer.MAX_VALUE ? "unlimited" : Integer.toString(cap), fanning, context.segment);
        if(plan.anchorSupport != null && plan.nearestSupport != null) {
            diag("START-GEOMETRY player-tile=%s nearest-support=#%d@%s anchor-support=#%d@%s chained=%d",
                plan.playerTile, plan.nearestSupport.id, plan.nearestSupport.rc,
                plan.anchorSupport.id, plan.anchorSupport.rc, plan.chainedSupports);
        } else {
            diag("START-GEOMETRY player-tile=%s locked-anchor=%s heading=%s source=%s",
                plan.playerTile, savedStart, runDirection, plan.anchorSource());
        }
        Bot task = Bot.execute((ignored, bot) -> {
            try {
                MiningBot.prewarmSupportResource(gui, bot);
                new Run(gui, bot, runDirection, targetBars, cap, fanning,
                    context.segment, savedStart).execute();
            } catch(InterruptedException interrupted) {
                String reason = bot.stopMessage();
                String source = bot.cancellationSource();
                MinerBotV3Overlay.markFailure(reason == null ? "Task interrupted" : reason);
                diag("CANCEL reason=%s source=%s", reason == null ? "Task interrupted" : reason,
                    source == null ? "unknown" : source);
                throw interrupted;
            } catch(Throwable failure) {
                status = "Status: stopped — task error: " + failure.getClass().getSimpleName()
                    + (failure.getMessage() == null ? "" : ": " + failure.getMessage());
                diagThrowable("ERROR", failure);
                if(failure instanceof RuntimeException) throw (RuntimeException) failure;
                if(failure instanceof Error) throw (Error) failure;
                throw new RuntimeException(failure);
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

    static StartPlan resolveStartPlan(GameUI gui, MinerBotV3Logic.Direction direction) {
        if(gui == null || gui.map == null || direction == null) return null;
        Gob player = gui.map.player();
        if(player == null) return null;
        MinerBotV3Navigator.Context context = MinerBotV3Navigator.context(gui);
        if(context == null) return null;
        MinerBotV3AnchorStore.SavedAnchor saved = MinerBotV3AnchorStore.get().get(gui.ui.sess);
        if(saved != null) {
            if(saved.segmentId != context.segment) return null;
            Coord live = saved.liveTile(context.sessionTile);
            return new StartPlan(null, null, live, player.rc.floor(MCache.tilesz), 0,
                saved.tile, saved.segmentId, saved.heading, true, saved.manuallyPicked);
        }
        Gob nearest = MiningBot.findNearestSupport(gui, player);
        if(nearest == null || nearest.rc == null) return null;
        Gob anchorSupport = nearest;
        Coord origin = nearest.rc.floor(MCache.tilesz).sub(direction.right().step());
        int chained = 0;
        while(true) {
            Coord expected = MinerBotV3Logic.columnTile(origin, direction);
            Gob next = MiningBot.findSupportNear(gui, expected);
            if(next == null) break;
            anchorSupport = next;
            origin = MinerBotV3Logic.endpoint(origin, direction);
            chained++;
        }
        return new StartPlan(nearest, anchorSupport, origin,
            player.rc.floor(MCache.tilesz), chained, origin.add(context.sessionTile),
            context.segment, direction, false, false);
    }

    static final class StartPlan {
        final Gob nearestSupport;
        final Gob anchorSupport;
        final Coord anchor;
        final Coord playerTile;
        final int chainedSupports;
        final Coord savedAnchor;
        final long segmentId;
        final MinerBotV3Logic.Direction heading;
        final boolean locked;
        final boolean manuallyPicked;

        StartPlan(Gob nearestSupport, Gob anchorSupport, Coord anchor,
                  Coord playerTile, int chainedSupports, Coord savedAnchor,
                  long segmentId, MinerBotV3Logic.Direction heading,
                  boolean locked, boolean manuallyPicked) {
            this.nearestSupport = nearestSupport;
            this.anchorSupport = anchorSupport;
            this.anchor = new Coord(anchor);
            this.playerTile = new Coord(playerTile);
            this.chainedSupports = chainedSupports;
            this.savedAnchor = new Coord(savedAnchor);
            this.segmentId = segmentId;
            this.heading = heading;
            this.locked = locked;
            this.manuallyPicked = manuallyPicked;
        }

        String anchorSource() {
            if(!locked) return "automatic support";
            return manuallyPicked ? "manual session checkpoint" : "saved session checkpoint";
        }
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

    static synchronized void diagThrowable(String phase, Throwable failure) {
        StringWriter buffer = new StringWriter();
        failure.printStackTrace(new PrintWriter(buffer));
        String text = String.format(Locale.ROOT, "[miner-v3] %s type=%s message=%s%n%s",
            phase, failure.getClass().getName(), failure.getMessage(), buffer);
        Debug.log.print(text);
        Debug.log.flush();
        if(log != null) {log.print(text); log.flush();}
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
    private enum BoulderOutcome {NONE, CLEARED, FAILED}
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
        final boolean fanning;
        final long segmentId;
        final Coord savedOrigin;
        final List<Coord> trail = new ArrayList<>();
        final List<Anchor> anchors = new ArrayList<>();
        Coord sessionTile;
        int placements;
        boolean barBatchInitialized;

        Run(GameUI gui, Bot bot, MinerBotV3Logic.Direction originalDirection,
            int barsTarget, int segmentCap, boolean fanning, long segmentId, Coord savedOrigin) {
            this.gui = gui;
            this.bot = bot;
            this.originalDirection = originalDirection;
            this.barsTarget = barsTarget;
            this.segmentCap = segmentCap;
            this.fanning = fanning;
            this.segmentId = segmentId;
            this.savedOrigin = new Coord(savedOrigin);
        }

        void execute() throws InterruptedException {
            status = "Status: returning to locked mining anchor";
            if(!returnToSavedAnchor())
                fail("could not reach the locked mining anchor " + savedOrigin);
            MinerBotV3Navigator.Context arrived = MinerBotV3Navigator.context(gui);
            if(arrived == null || arrived.segment != segmentId)
                fail("saved-map position became unavailable at the locked mining anchor");
            sessionTile = new Coord(arrived.sessionTile);
            Coord anchor = savedOrigin.sub(sessionTile);
            trail.add(new Coord(anchor));
            anchors.add(new Anchor(anchor, 0));
            diag("CHECKPOINT arrived saved=%s live=%s heading=%s", savedOrigin, anchor,
                originalDirection);
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

        private boolean returnToSavedAnchor() throws InterruptedException {
            MinerBotV3Navigator.Context context = MinerBotV3Navigator.context(gui);
            Gob player = gui.map == null ? null : gui.map.player();
            if(context != null && context.segment == segmentId && player != null && player.rc != null) {
                Coord live = savedOrigin.sub(context.sessionTile);
                if(player.rc.dist(MiningBot.tileCenter(live)) <= MCache.tilesz.x * 20.0) {
                    if(walkToTile(live, "nearby session mining anchor")) return true;
                    diag("CHECKPOINT nearby-local-route-failed saved=%s; trying saved-map route",
                        savedOrigin);
                }
            }
            return MinerBotV3Navigator.moveToTile(gui, bot, segmentId, savedOrigin,
                "session mining anchor");
        }

        private LegOutcome mineAndPlaceLeg(Coord anchor, MinerBotV3Logic.Direction heading)
                throws InterruptedException {
            if(placements >= segmentCap) throw new SafetyCapReached();
            status = "Status: mining " + heading.name().toLowerCase(Locale.ROOT) + " from " + anchor;
            MinerBotV3Overlay.showLeg(gui, anchor, heading, status);
            LegOutcome line = completeLine(anchor, heading);
            if(line != LegOutcome.SUCCESS) return line;

            Coord endpoint = MinerBotV3Logic.endpoint(anchor, heading);
            Coord column = MinerBotV3Logic.columnTile(anchor, heading);
            if(!clearOperationBoulders(column, "column pocket"))
                return failLeg("could not clear boulder obstructing column pocket " + column);
            LegOutcome pocket = minePocket(column);
            if(pocket != LegOutcome.SUCCESS) return pocket;

            ensureSupplyCircuit(true);
            if(MiningMaterials.stoneCount(gui) < MinerBotV3Logic.COLUMN_STONES)
                return failLeg("only " + MiningMaterials.stoneCount(gui) + "/" + MinerBotV3Logic.COLUMN_STONES + " building stones available");
            if(MiningMaterials.hardBarCount(gui) < 1)
                return failLeg("no Bronze/Wrought bar is available for the column");

            status = "Status: placing column at " + column;
            if(!clearOperationBoulders(column, "column placement"))
                return failLeg("could not clear boulder obstructing column placement " + column);
            MiningBot.waitForCommandQueueIdle(gui, bot, 15000L);
            MiningBot.waitForMovementSettled(gui, bot, 3000L);
            if(!MiningBot.placeSupport(gui, bot, MiningBot.tileCenter(column)))
                return failLeg("column placement failed at " + column);
            if(heading == originalDirection) saveCheckpoint(endpoint);
            if(!walkToTile(endpoint, "return to tunnel centerline"))
                return failLeg("could not return to centerline " + endpoint + " after placement");

            if(fanning && heading == originalDirection && !mineFan(anchor, endpoint))
                return LegOutcome.FAILED;

            if(trail.isEmpty() || !trail.get(trail.size() - 1).equals(endpoint))
                trail.add(new Coord(endpoint));
            anchors.add(new Anchor(endpoint, trail.size() - 1));
            placements++;
            diag("COLUMN placed=%s anchor=%s heading=%s count=%d", column, endpoint, heading, placements);
            return LegOutcome.SUCCESS;
        }

        private boolean mineFan(Coord mainAnchor, Coord endpoint) throws InterruptedException {
            Coord center = MinerBotV3Logic.fanAnchor(mainAnchor, originalDirection);
            MinerBotV3Logic.Direction[] arms = {
                originalDirection.left(), originalDirection.right()
            };
            String[] names = {"left", "right"};
            for(int i = 0; i < arms.length; i++) {
                if(!walkToTile(center, "fan center " + names[i]))
                    return failFan("could not reach fan center " + center);
                status = "Status: fanning " + names[i] + " from " + center;
                MinerBotV3Overlay.showLeg(gui, mainAnchor, originalDirection, status);
                int trailSize = trail.size();
                LegOutcome result;
                try {
                    result = completeLine(center, arms[i]);
                } finally {
                    while(trail.size() > trailSize) trail.remove(trail.size() - 1);
                }
                diag("FAN arm=%s center=%s heading=%s result=%s",
                    names[i], center, arms[i], result);
                if(result == LegOutcome.FAILED)
                    return failFan(names[i] + " fan arm failed from " + center);
                // A hard wall merely bounds this supported fan arm; the other
                // side and the main tunnel can still proceed safely.
            }
            if(!walkToTile(endpoint, "return from fan to tunnel centerline"))
                return failFan("could not return from fan to centerline " + endpoint);
            return true;
        }

        private boolean failFan(String reason) throws InterruptedException {
            diag("FAN failed reason=%s", reason);
            fail(reason);
            return false;
        }

        private void saveCheckpoint(Coord liveAnchor) {
            MinerBotV3Navigator.Context context = MinerBotV3Navigator.context(gui);
            Coord offset = context != null && context.segment == segmentId
                ? context.sessionTile : sessionTile;
            if(offset == null) {
                diag("CHECKPOINT update-skipped live=%s reason=session-offset-unavailable", liveAnchor);
                return;
            }
            Coord saved = liveAnchor.add(offset);
            MinerBotV3AnchorStore.get().put(gui.ui.sess,
                new MinerBotV3AnchorStore.SavedAnchor(segmentId, saved,
                    originalDirection, false));
            diag("CHECKPOINT updated saved=%s live=%s heading=%s", saved, liveAnchor,
                originalDirection);
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

                BoulderOutcome boulder = clearFrontierBoulder(anchor, heading, completed);
                if(boulder == BoulderOutcome.FAILED)
                    return failLeg("could not clear the boulder at the active mining frontier");
                if(boulder == BoulderOutcome.CLEARED) continue;

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

        /** Area mining cannot target a boulder gob that occupies the first
         * unopened tile. Clear only that active-line blocker, then let the
         * normal remainder calculation redraw toward the original endpoint. */
        private BoulderOutcome clearFrontierBoulder(Coord anchor,
                MinerBotV3Logic.Direction heading, int completed) throws InterruptedException {
            Gob boulder = frontierBoulder(anchor, heading, completed);
            if(boulder == null) return BoulderOutcome.NONE;
            status = "Status: clearing boulder on active mining line";
            diag("BOULDER detected id=%d resid=%s tile=%s anchor=%s heading=%s completed=%d",
                boulder.id, safeResid(boulder), boulder.rc.floor(MCache.tilesz),
                anchor, heading, completed);
            boolean cleared = chipBoulder(boulder);
            diag("BOULDER result id=%d cleared=%b", boulder.id, cleared);
            return cleared ? BoulderOutcome.CLEARED : BoulderOutcome.FAILED;
        }

        private Gob frontierBoulder(Coord anchor, MinerBotV3Logic.Direction heading,
                                    int completed) {
            Coord frontier = anchor.add(heading.step().mul(completed + 1));
            Coord2d frontierWorld = MiningBot.tileCenter(frontier);
            synchronized(gui.ui.sess.glob.oc) {
                return gui.ui.sess.glob.oc.stream()
                    .filter(g -> g != null && !g.disposed() && g.rc != null)
                    .filter(g -> MinerBotV3Logic.isBoulderResource(safeResid(g)))
                    .filter(g -> MinerBotV3Logic.boulderBlocksFrontier(anchor, heading,
                        completed, g.rc.floor(MCache.tilesz)))
                    .min(Comparator.<Gob>comparingDouble(g -> g.rc.dist(frontierWorld))
                        .thenComparingLong(g -> g.id))
                    .orElse(null);
            }
        }

        /** Clears every loaded bumling whose footprint can overlap the tile
         * required by the current operation. Re-scan after each chip because a
         * cave-in can contain more than one boulder at the same work site. */
        private boolean clearOperationBoulders(Coord target, String phase)
                throws InterruptedException {
            int cleared = 0;
            while(true) {
                Gob boulder = operationBoulder(target);
                if(boulder == null) return true;
                status = "Status: clearing boulder obstructing " + phase;
                diag("BOULDER obstruction phase=%s id=%d resid=%s tile=%s target=%s",
                    phase, boulder.id, safeResid(boulder),
                    boulder.rc.floor(MCache.tilesz), target);
                if(!chipBoulder(boulder)) {
                    diag("BOULDER obstruction phase=%s id=%d result=failed", phase, boulder.id);
                    return false;
                }
                cleared++;
                diag("BOULDER obstruction phase=%s id=%d result=cleared count=%d",
                    phase, boulder.id, cleared);
                if(cleared >= BOULDER_MAX_ATTEMPTS) {
                    diag("BOULDER obstruction phase=%s result=safety-limit limit=%d",
                        phase, BOULDER_MAX_ATTEMPTS);
                    return operationBoulder(target) == null;
                }
            }
        }

        private Gob operationBoulder(Coord target) {
            Coord2d targetWorld = MiningBot.tileCenter(target);
            synchronized(gui.ui.sess.glob.oc) {
                return gui.ui.sess.glob.oc.stream()
                    .filter(g -> g != null && !g.disposed() && g.rc != null)
                    .filter(g -> MinerBotV3Logic.isBoulderResource(safeResid(g)))
                    .filter(g -> MinerBotV3Logic.boulderBlocksTile(target,
                        g.rc.floor(MCache.tilesz)))
                    .min(Comparator.<Gob>comparingDouble(g -> g.rc.dist(targetWorld))
                        .thenComparingLong(g -> g.id))
                    .orElse(null);
            }
        }

        /** Proven Clear-Cut/Cellar-Digger interaction sequence, scoped to one
         * V3 frontier boulder. Rock output is dropped as it appears so a nearly
         * full mining inventory cannot stall the action; V3's existing route
         * collector retrieves the 30-stone column reserve afterward. */
        private boolean chipBoulder(Gob boulder) throws InterruptedException {
            long id = boulder.id;
            int chips = 0;
            int failedActions = 0;
            while(chips < BOULDER_MAX_CHIPS) {
                bot.checkCancelled();
                ensureSupplyCircuit(false);
                boulder = currentBoulder(id);
                if(boulder == null) return true;
                if(!Equip.ensureTwoHanded(gui, bot, Equip.PICKAXE)) {
                    diag("BOULDER id=%d result=pickaxe-unavailable", id);
                    return false;
                }
                boulder = currentBoulder(id);
                if(boulder == null) return true;
                if(!approachBoulder(boulder)) {
                    if(currentBoulder(id) == null) return true;
                    diag("BOULDER id=%d result=approach-failed", id);
                    return false;
                }
                if(!dropRockStacks()) {
                    diag("BOULDER id=%d result=pre-chip-rock-drop-failed", id);
                    return false;
                }
                boulder = currentBoulder(id);
                if(boulder == null) return true;
                FlowerMenu menu = openBoulderMenu(boulder);
                if(menu == null) {
                    if(currentBoulder(id) == null) return true;
                    if(++failedActions >= BOULDER_MAX_ATTEMPTS) {
                        diag("BOULDER id=%d result=menu-timeout attempts=%d", id, failedActions);
                        return false;
                    }
                    continue;
                }
                int option = menuOption(menu, "Chip stone");
                if(option < 0) {
                    diag("BOULDER id=%d result=chip-option-missing options=%s",
                        id, java.util.Arrays.toString(menu.options));
                    menu.wdgmsg("cl", -1, 0);
                    return currentBoulder(id) == null;
                }
                diag("BOULDER chip id=%d number=%d", id, chips + 1);
                menu.wdgmsg("cl", option, 0);
                ChipResult result = waitForChip(id);
                diag("BOULDER chip-result id=%d started=%b produced=%b gone=%b safe=%b",
                    id, result.started, result.produced, result.gone, result.safe);
                if(!result.safe) return false;
                if((!result.started || !result.produced) && !result.gone) {
                    if(++failedActions >= BOULDER_MAX_ATTEMPTS) return false;
                } else {
                    failedActions = 0;
                    chips++;
                }
                if(result.gone) return true;
            }
            diag("BOULDER id=%d result=chip-safety-limit limit=%d", id, BOULDER_MAX_CHIPS);
            return currentBoulder(id) == null;
        }

        private boolean approachBoulder(Gob boulder) throws InterruptedException {
            long id = boulder.id;
            for(int attempt = 1; attempt <= BOULDER_MAX_ATTEMPTS; attempt++) {
                Gob target = currentBoulder(id);
                if(target == null) return true;
                Gob player = gui.map.player();
                if(player != null && player.rc != null &&
                   player.rc.dist(target.rc) <= BOULDER_DIRECT_CLICK_RADIUS) {
                    MiningBot.waitForMovementSettled(gui, bot, 3000L);
                    return true;
                }
                BotMovement.Result normal = MiningBot.approachGob(gui, bot, target,
                    "V3 frontier boulder #" + id);
                logMovement("approach", "frontier boulder #" + id, normal);
                if(normal != null && normal.readyToInteract()) return true;
                if(normal != null && normal.status == BotMovement.Status.TARGET_GONE) return true;

                List<Coord2d> ring = new ArrayList<>();
                for(int i = 0; i < 24; i++) {
                    double angle = Math.PI * 2.0 * i / 24.0;
                    ring.add(target.rc.add(Math.cos(angle) * BOULDER_APPROACH_RADIUS,
                        Math.sin(angle) * BOULDER_APPROACH_RADIUS));
                }
                if(player != null && player.rc != null)
                    ring.sort(Comparator.comparingDouble(player.rc::dist));
                BotMovement.Result fallback = MiningBot.moveToAnyPoint(gui, bot, ring,
                    "V3 frontier boulder ring #" + id);
                logMovement("moveToAny", "frontier boulder ring #" + id, fallback);
                player = gui.map.player();
                target = currentBoulder(id);
                if(target == null) return true;
                if(fallback != null && fallback.arrived() && player != null && player.rc != null &&
                   player.rc.dist(target.rc) <= MCache.tilesz.x * 1.75) return true;
            }
            return false;
        }

        private Gob currentBoulder(long id) {
            Gob gob = gui.ui.sess.glob.oc.getgob(id);
            if(gob == null || gob.disposed()) return null;
            try {
                return MinerBotV3Logic.isBoulderResource(gob.resid()) ? gob : null;
            } catch(Loading loading) {
                return gob;
            }
        }

        private FlowerMenu openBoulderMenu(Gob gob) throws InterruptedException {
            for(int attempt = 1; attempt <= BOULDER_MAX_ATTEMPTS; attempt++) {
                bot.checkCancelled();
                Set<Widget> before = descendantWidgets(gui.ui.root);
                FlowerMenu.lastGob(gob);
                new GobTarget(gob).rclick();
                final FlowerMenu[] found = new FlowerMenu[1];
                if(waitFor(BOULDER_STEP_TIMEOUT_MS, () -> {
                    found[0] = newDescendant(gui.ui.root, FlowerMenu.class, before);
                    return found[0] != null;
                })) return found[0];
                if(currentBoulder(gob.id) == null) return null;
            }
            return null;
        }

        private int menuOption(FlowerMenu menu, String name) {
            if(menu != null && menu.options != null)
                for(int i = 0; i < menu.options.length; i++)
                    if(name.equals(menu.options[i])) return i;
            return -1;
        }

        private static final class ChipResult {
            final boolean started;
            final boolean produced;
            final boolean gone;
            final boolean safe;

            ChipResult(boolean started, boolean produced, boolean gone, boolean safe) {
                this.started = started;
                this.produced = produced;
                this.gone = gone;
                this.safe = safe;
            }
        }

        private ChipResult waitForChip(long boulderId) throws InterruptedException {
            long now = System.currentTimeMillis();
            long startDeadline = now + 3000L;
            long actionDeadline = now + BOULDER_CHIP_TIMEOUT_MS;
            long stoppedAt = -1L;
            boolean started = false;
            boolean produced = false;
            boolean gone = false;
            while(System.currentTimeMillis() < actionDeadline) {
                bot.checkCancelled();
                boolean activeProgress = gui.prog != null;
                if(activeProgress) started = true;
                int dropped = dropRockStacksCount();
                if(dropped < 0) return new ChipResult(started, produced, gone, false);
                if(dropped > 0) {
                    produced = true;
                    started = true;
                }
                GameUI.DraggedItem held = gui.hand();
                if(held != null) {
                    try {
                        held.item.info();
                    } catch(Loading loading) {
                        Thread.sleep(25L);
                        continue;
                    }
                    if(!MiningMaterials.isRockMaterial(held.item))
                        return new ChipResult(started, produced, gone, false);
                    MiningBot.dropCursorItem(gui);
                    if(!waitFor(BOULDER_STEP_TIMEOUT_MS, () -> gui.hand() == null))
                        return new ChipResult(started, produced, gone, false);
                    produced = true;
                    started = true;
                }
                gone = currentBoulder(boulderId) == null;
                if(gone) started = true;
                now = System.currentTimeMillis();
                if(!started) {
                    if(now >= startDeadline) break;
                } else if(activeProgress) {
                    stoppedAt = -1L;
                } else {
                    if(stoppedAt < 0L) stoppedAt = now;
                    long settle = produced ? 150L : (gone ? 750L : 2000L);
                    if(now - stoppedAt >= settle) break;
                }
                Thread.sleep(25L);
            }
            return new ChipResult(started, produced, gone, true);
        }

        private boolean dropRockStacks() throws InterruptedException {
            return dropRockStacksCount() >= 0;
        }

        /** Negative means a stack failed to leave the inventory. */
        private int dropRockStacksCount() throws InterruptedException {
            int dropped = 0;
            while(dropped < BOULDER_MAX_ROCK_DROPS) {
                bot.checkCancelled();
                WItem rock = firstRockStack();
                if(rock == null) return dropped;
                GItem item = rock.item;
                diag("BOULDER rock-drop item=%s", safeItemResid(item));
                item.wdgmsg("drop", rock.sz.div(2));
                if(!waitFor(BOULDER_STEP_TIMEOUT_MS, () -> !mainInventoryContains(item)))
                    return -1;
                dropped++;
            }
            return -1;
        }

        private WItem firstRockStack() {
            if(gui.maininv == null) return null;
            for(WItem item : gui.maininv.children(WItem.class))
                if(MiningMaterials.isRockMaterial(item)) return item;
            return null;
        }

        private boolean mainInventoryContains(GItem wanted) {
            if(gui.maininv == null || wanted == null) return false;
            for(WItem item : gui.maininv.children(WItem.class))
                if(item.item == wanted) return true;
            return false;
        }

        private Set<Widget> descendantWidgets(Widget root) {
            Set<Widget> out = new HashSet<>();
            if(root == null) return out;
            for(Widget child = root.lchild; child != null; child = child.prev) {
                out.add(child);
                out.addAll(descendantWidgets(child));
            }
            return out;
        }

        private <T extends Widget> T newDescendant(Widget root, Class<T> type,
                                                    Set<Widget> before) {
            if(root == null) return null;
            for(Widget child = root.lchild; child != null; child = child.prev) {
                if(type.isInstance(child) && !before.contains(child)) return type.cast(child);
                T nested = newDescendant(child, type, before);
                if(nested != null) return nested;
            }
            return null;
        }

        private String safeResid(Gob gob) {
            try {
                return gob == null ? null : gob.resid();
            } catch(RuntimeException ignored) {
                return null;
            }
        }

        private String safeItemResid(GItem item) {
            try {
                return item == null ? null : item.resname();
            } catch(RuntimeException ignored) {
                return null;
            }
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
                Coord start = MinerBotV3Logic.detourStart(base.tile, originalDirection);
                if(!walkToTile(start, "dogleg support clearance"))
                    fail("could not reach dogleg start " + start + " behind support " + base.tile);
                diag("DETOUR try=%s base=%s start=%s clearance=1", candidate, base.tile, start);
                MinerBotV3Logic.Direction side = candidate.heading(originalDirection);
                Coord at = start;
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
            if(!barBatchInitialized && MinerBotV3Logic.barBatchRestored(
                    MiningMaterials.hardBarCount(gui), barsTarget))
                barBatchInitialized = true;
            boolean waterEmpty = !MiningBot.hasAnyDrink(gui);
            boolean energyLow = energyLow();
            boolean stoneLow = MiningMaterials.stoneCount(gui) < MinerBotV3Logic.COLUMN_STONES;
            boolean barsNeeded = needsBarSupply();
            boolean triggered = waterEmpty || energyLow || barsNeeded || (requireStones && stoneLow);
            if(!triggered) return;

            if(MinerBotV3Logic.shouldCollectRouteStone(MiningMaterials.stoneCount(gui), triggered))
                collectStonesAlongTrail();

            waterEmpty = !MiningBot.hasAnyDrink(gui);
            energyLow = energyLow();
            stoneLow = MiningMaterials.stoneCount(gui) < MinerBotV3Logic.COLUMN_STONES;
            barsNeeded = needsBarSupply();
            triggered = waterEmpty || energyLow || barsNeeded || (requireStones && stoneLow);
            if(!triggered) return;

            MinerBotV3Navigator.Context frontier = MinerBotV3Navigator.context(gui);
            if(frontier == null || frontier.segment != segmentId)
                fail("saved-map position became unavailable before resupply");
            MinerBotV3ZoneStore.SavedArea water = zone(MinerBotV3ZoneStore.ROLE_WATER);
            MinerBotV3ZoneStore.SavedArea food = zone(MinerBotV3ZoneStore.ROLE_FOOD);
            MinerBotV3ZoneStore.SavedArea storage = zone(MinerBotV3ZoneStore.ROLE_STORAGE);

            if(waterEmpty && water == null) fail("no water remains and no water area is selected");
            if(energyLow && food == null) fail("energy fell below 2,500% and no food area is selected");
            if((stoneLow || barsNeeded) && storage == null)
                fail("stone/bar reserves are short and no storage area is selected");

            List<Service> pending = new ArrayList<>();
            if(water != null && !allDrinkVesselsFull()) pending.add(Service.WATER);
            if(energyLow) pending.add(Service.FOOD);
            if(stoneLow || barsNeeded) pending.add(Service.STORAGE);
            diag("SUPPLY trigger water-empty=%b energy-low=%b stone=%d/%d bars=%d initialized=%b refill-at-zero target=%d pending=%s frontier=%s",
                waterEmpty, energyLow, MiningMaterials.stoneCount(gui), MinerBotV3Logic.COLUMN_STONES,
                MiningMaterials.hardBarCount(gui), barBatchInitialized, barsTarget, pending, frontier.savedTile);

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
                case WATER: {
                    boolean helperResult = MiningMaterials.refillWaterFromZone(gui, bot, live);
                    boolean allFull = allDrinkVesselsFull();
                    ok = MinerBotV3Logic.waterRefillSucceeded(helperResult, allFull);
                    diag("SUPPLY water helper-result=%b observed-all-full=%b", helperResult, allFull);
                    break;
                }
                case FOOD:
                    ok = MiningMaterials.eatFromZone(gui, bot, live, MinerBotV3Logic.EAT_UNTIL_PERCENT)
                        && MinerBotV3Logic.energyTargetReached(energy());
                    break;
                default: {
                    boolean refillBars = needsBarSupply();
                    ok = true;
                    if(MiningMaterials.stoneCount(gui) < MinerBotV3Logic.COLUMN_STONES)
                        ok = MiningMaterials.fetchFromZone(gui, bot, live,
                            MiningMaterials::isBuildingStone, MinerBotV3Logic.COLUMN_STONES,
                            gob -> sourcePriority(gob, MinerBotV3Logic.Supply.STONE));
                    if(refillBars)
                        ok = MiningMaterials.fetchFromZone(gui, bot, live,
                            MiningMaterials::isHardBar, barsTarget,
                            gob -> sourcePriority(gob, MinerBotV3Logic.Supply.BARS)) && ok;
                    if(gui.maininv != null)
                        ok = StackAllItems.stackMatchingNow(gui.maininv, MiningMaterials::isHardBar) && ok;
                    ok = ok && MiningMaterials.stoneCount(gui) >= MinerBotV3Logic.COLUMN_STONES
                        && (!refillBars || MinerBotV3Logic.barBatchRestored(
                            MiningMaterials.hardBarCount(gui), barsTarget));
                    if(ok && refillBars) barBatchInitialized = true;
                    break;
                }
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

        private boolean needsBarSupply() {
            return MinerBotV3Logic.needsBarSupply(
                MiningMaterials.hardBarCount(gui), barsTarget, barBatchInitialized);
        }

        private int sourcePriority(Gob gob, MinerBotV3Logic.Supply supply) {
            String resid;
            try {
                resid = gob == null ? null : gob.resid();
            } catch(RuntimeException ignored) {
                resid = null;
            }
            return MinerBotV3Logic.supplySourcePriority(supply, resid,
                gob != null && gob.is(GobTag.HAS_WATER),
                gob != null && gob.is(GobTag.EMPTY),
                gob != null && gob.is(GobTag.FULL));
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
            MinerBotV3Overlay.markFailure(reason);
            gui.error("Miner Bot V3 stopped: " + reason);
            bot.cancel(reason);
            bot.checkCancelled();
            throw new InterruptedException(reason);
        }
    }
}
