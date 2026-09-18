package auto;

import haven.Area;
import haven.Coord;
import haven.Coord2d;
import haven.GameUI;
import haven.Gob;
import haven.MapFile;
import haven.MiniMap;
import haven.pathfinding.BotMovement;
import haven.pathfinding.CaveRoutePlanner;
import haven.pathfinding.MapFileCaveSource;
import haven.pathfinding.MapTileCoordinates;
import thunder.mining.MinerBotV3ZoneStore;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Miner-owned saved-map strategy. BotMovement still owns every local leg. */
final class MinerBotV3Navigator {
    private static final int ROUTE_MAX_TILES = 250_000;
    private static final int LOCAL_LEG_TILES = 4;
    private static final int MAX_REPLANS = 4;

    private MinerBotV3Navigator() {}

    static final class Context {
        final MapFile file;
        final long segment;
        final Coord sessionTile;
        final Coord savedTile;

        Context(MapFile file, long segment, Coord sessionTile, Coord savedTile) {
            this.file = file;
            this.segment = segment;
            this.sessionTile = sessionTile;
            this.savedTile = savedTile;
        }
    }

    static Context context(GameUI gui) {
        if(gui == null || gui.map == null || gui.mapfile == null) return null;
        MiniMap.Location location = gui.mapfile.playerLocation();
        Gob player = gui.map.player();
        if(location == null || location.seg == null || location.tc == null
            || player == null || player.rc == null) return null;
        return new Context(gui.mapfile.file, location.seg.id, new Coord(location.tc),
            MapTileCoordinates.playerTile(player.rc, location.tc));
    }

    static Coord currentSavedTile(GameUI gui) {
        Context context = context(gui);
        return context == null ? null : context.savedTile;
    }

    static boolean sameSegment(GameUI gui, MinerBotV3ZoneStore.SavedArea area) {
        Context context = context(gui);
        return context != null && area != null && context.segment == area.segmentId;
    }

    static double routeDistance(GameUI gui, MinerBotV3ZoneStore.SavedArea area) {
        Context context = context(gui);
        if(context == null || area == null || context.segment != area.segmentId)
            return Double.POSITIVE_INFINITY;
        CaveRoutePlanner.Plan plan = CaveRoutePlanner.planTo(
            new MapFileCaveSource(context.file, context.segment), context.savedTile,
            goals(area.area), ROUTE_MAX_TILES);
        return plan.usable() ? plan.distanceTiles : Double.POSITIVE_INFINITY;
    }

    static double routeDistanceToTile(GameUI gui, long segment, Coord tile) {
        Context context = context(gui);
        if(context == null || tile == null || context.segment != segment)
            return Double.POSITIVE_INFINITY;
        List<Coord> goals = new ArrayList<>();
        goals.add(new Coord(tile));
        CaveRoutePlanner.Plan plan = CaveRoutePlanner.planTo(
            new MapFileCaveSource(context.file, context.segment), context.savedTile,
            goals, ROUTE_MAX_TILES);
        return plan.usable() ? plan.distanceTiles : Double.POSITIVE_INFINITY;
    }

    static boolean moveToArea(GameUI gui, Bot bot, MinerBotV3ZoneStore.SavedArea area,
                              String label) throws InterruptedException {
        return area != null && move(gui, bot, area.segmentId, goals(area.area), label);
    }

    static boolean moveToTile(GameUI gui, Bot bot, long segment, Coord tile,
                              String label) throws InterruptedException {
        List<Coord> goals = new ArrayList<>();
        goals.add(new Coord(tile));
        return move(gui, bot, segment, goals, label);
    }

    private static boolean move(GameUI gui, Bot bot, long segment, List<Coord> goals,
                                String label) throws InterruptedException {
        Set<Coord> blocked = new HashSet<>();
        for(int attempt = 0; attempt <= MAX_REPLANS; attempt++) {
            bot.checkCancelled();
            Context context = context(gui);
            if(context == null) {
                MinerBotV3.diag("ROUTE phase=%s result=context-unavailable", label);
                return false;
            }
            if(context.segment != segment) {
                MinerBotV3.diag("ROUTE phase=%s result=wrong-segment current=%x wanted=%x",
                    label, context.segment, segment);
                return false;
            }
            MapFileCaveSource saved = new MapFileCaveSource(context.file, context.segment);
            CaveRoutePlanner.Source source = tile -> blocked.contains(tile)
                ? CaveRoutePlanner.Cell.BLOCKED : saved.cell(tile);
            CaveRoutePlanner.Plan plan = CaveRoutePlanner.planTo(
                source, context.savedTile, goals, ROUTE_MAX_TILES);
            MinerBotV3.diag("ROUTE phase=%s attempt=%d status=%s start=%s dest=%s tiles=%d distance=%.1f blocked=%d",
                label, attempt + 1, plan.status, context.savedTile, plan.destination,
                plan.route.size(), plan.distanceTiles, blocked.size());
            if(!plan.usable()) return false;
            if(plan.route.size() <= 1) return true;

            boolean replan = false;
            int previousIndex = 0;
            for(int index = LOCAL_LEG_TILES; index < plan.route.size(); index += LOCAL_LEG_TILES) {
                int goalIndex = Math.min(index, plan.route.size() - 1);
                Coord savedGoal = plan.route.get(goalIndex);
                Context live = context(gui);
                if(live == null || live.segment != segment) return false;
                Coord2d worldGoal = MapTileCoordinates.worldPosition(savedGoal, live.sessionTile);
                BotMovement.Result result = BotMovement.moveTo(gui, bot, worldGoal,
                    BotMovement.Mode.CAVE, 45000L);
                MinerBotV3.logMovement("saved-route", label + " tile " + savedGoal, result);
                if(result == null || !result.arrived()) {
                    if(advanceToEarlierWaypoint(gui, bot, segment, plan.route,
                        previousIndex, goalIndex, label)) {
                        MinerBotV3.diag("ROUTE phase=%s local-fallback-progress failed=%s",
                            label, savedGoal);
                        replan = true;
                        break;
                    }
                    markBarrier(blocked, savedGoal);
                    MinerBotV3.diag("ROUTE phase=%s block-exact=%s blocked=%d",
                        label, savedGoal, blocked.size());
                    replan = true;
                    break;
                }
                if(goalIndex == plan.route.size() - 1) return true;
                previousIndex = goalIndex;
            }
            if(!replan) {
                Coord savedGoal = plan.route.get(plan.route.size() - 1);
                Context live = context(gui);
                if(live == null || live.segment != segment) return false;
                BotMovement.Result result = BotMovement.moveTo(gui, bot,
                    MapTileCoordinates.worldPosition(savedGoal, live.sessionTile),
                    BotMovement.Mode.CAVE, 45000L);
                MinerBotV3.logMovement("saved-route-final", label, result);
                if(result != null && result.arrived()) return true;
                if(advanceToEarlierWaypoint(gui, bot, segment, plan.route,
                    previousIndex, plan.route.size() - 1, label)) {
                    MinerBotV3.diag("ROUTE phase=%s local-fallback-progress failed=%s",
                        label, savedGoal);
                    continue;
                }
                Coord barrier = failureBarrier(plan.route, previousIndex,
                    plan.route.size() - 1, true);
                markBarrier(blocked, barrier);
                MinerBotV3.diag("ROUTE phase=%s failed-goal=%s block-exact=%s blocked=%d",
                    label, savedGoal, barrier, blocked.size());
            }
        }
        MinerBotV3.diag("ROUTE phase=%s result=replans-exhausted", label);
        return false;
    }

    /** A local failure proves only that exact waypoint unsafe. Expanding it to
     * 3x3 can seal an otherwise valid one- or two-tile mine tunnel. */
    static void markBarrier(Set<Coord> blocked, Coord center) {
        if(blocked != null && center != null) blocked.add(new Coord(center));
    }

    /** Never mark the requested destination itself blocked. For a failed final
     * hop, invalidate only its last approach tile so the saved planner may try
     * another side. A one-tile final hop is retried without inventing a block. */
    static Coord failureBarrier(List<Coord> route, int previousIndex,
                                int failedIndex, boolean destination) {
        if(route == null || failedIndex < 0 || failedIndex >= route.size()) return null;
        if(!destination) return new Coord(route.get(failedIndex));
        int approach = failedIndex - 1;
        return approach > previousIndex ? new Coord(route.get(approach)) : null;
    }

    /** Salvage bounded progress when a four-tile local goal is obstructed.
     * Replanning from a nearer observed tile is safer than inventing a wide
     * saved-map barrier around an arbitrary staging waypoint. */
    private static boolean advanceToEarlierWaypoint(GameUI gui, Bot bot, long segment,
                                                     List<Coord> route, int previousIndex,
                                                     int failedIndex, String label)
            throws InterruptedException {
        for(int index = failedIndex - 1; index > previousIndex; index--) {
            Context live = context(gui);
            if(live == null || live.segment != segment) return false;
            Coord savedGoal = route.get(index);
            BotMovement.Result result = BotMovement.moveTo(gui, bot,
                MapTileCoordinates.worldPosition(savedGoal, live.sessionTile),
                BotMovement.Mode.CAVE, 45000L);
            MinerBotV3.logMovement("saved-route-fallback", label + " tile " + savedGoal, result);
            if(result != null && result.arrived()) return true;
        }
        return false;
    }

    /** Tight candidate set around zone center so strategic routing reaches its containers. */
    static List<Coord> goals(Area area) {
        List<Coord> out = new ArrayList<>();
        if(area == null) return out;
        Coord center = area.ul.add(area.br).div(2);
        out.add(new Coord(center));
        for(int radius = 1; radius <= 2; radius++) {
            for(int y = -radius; y <= radius; y++) {
                for(int x = -radius; x <= radius; x++) {
                    if(Math.max(Math.abs(x), Math.abs(y)) == radius)
                        out.add(center.add(x, y));
                }
            }
        }
        return out;
    }

    static Coord2d liveWorld(Coord savedTile, Coord sessionTile) {
        return MapTileCoordinates.worldPosition(savedTile, sessionTile);
    }
}
