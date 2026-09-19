package auto;

import haven.CFG;
import haven.Coord;
import haven.Coord2d;
import haven.Coord3f;
import haven.GOut;
import haven.GameUI;
import haven.Gob;
import haven.MCache;
import haven.MapView;
import haven.dev.DebugDraw;
import haven.pathfinding.MovementScene;
import haven.pathfinding.PathfinderLog;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Read-only map visualization for V3's selected support and computed straight leg. */
public final class MinerBotV3Overlay implements DebugDraw {
    private static final Color SUPPORT = new Color(255, 210, 60, 245);
    private static final Color ANCHOR = new Color(40, 230, 255, 245);
    private static final Color LEG = new Color(80, 255, 170, 235);
    private static final Color FAN = new Color(90, 180, 255, 235);
    private static final Color COLUMN = new Color(235, 80, 255, 245);
    private static final Color COLLISION = new Color(255, 70, 70, 230);
    private static final Color PLAYER_LINK = new Color(245, 245, 245, 190);
    private static final Color WARNING = new Color(255, 105, 70, 245);
    private static final Color TEXT = new Color(235, 255, 255, 245);

    private static volatile Snapshot current;

    static {
        DebugDraw.Registry.register(new MinerBotV3Overlay());
    }

    private MinerBotV3Overlay() {}

    /** Forces static registration when the setup window first opens. */
    public static void init() {}

    public static PreviewResult preview(GameUI gui, MinerBotV3Logic.Direction heading) {
        return preview(gui, heading, false);
    }

    public static PreviewResult preview(GameUI gui, MinerBotV3Logic.Direction heading,
                                        boolean fanning) {
        MinerBotV3.StartPlan plan = MinerBotV3.resolveStartPlan(gui, heading);
        if(plan == null) {
            current = null;
            return new PreviewResult("Preview: no visible support", "No visible mine support was found.", false);
        }
        String route = probeAnchor(gui, plan);
        showStart(gui, plan, plan.heading, "setup preview", route, fanning);
        Snapshot snapshot = current;
        return new PreviewResult(snapshot == null ? "Preview unavailable" : snapshot.summary,
            snapshot == null ? "Preview could not be built." : snapshot.warning,
            snapshot != null && !snapshot.routeBlocked);
    }

    static void showStart(GameUI gui, MinerBotV3.StartPlan plan,
                          MinerBotV3Logic.Direction heading, String phase, String route,
                          boolean fanning) {
        if(gui == null || gui.map == null || plan == null || heading == null) return;
        Snapshot prior = current;
        if(route == null && prior != null && prior.heading == heading && prior.anchor.equals(plan.anchor))
            route = prior.route.toUpperCase(Locale.ROOT);
        Gob support = plan.anchorSupport == null ? plan.nearestSupport : plan.anchorSupport;
        Coord expectedSupportTile = plan.anchor.add(heading.right().step());
        Coord2d expectedSupport = MiningBot.tileCenter(expectedSupportTile);
        Coord2d supportWorld = support == null || support.rc == null ? null : Coord2d.of(support.rc.x, support.rc.y);
        double supportOffset = supportWorld == null ? Double.NaN : supportWorld.dist(expectedSupport);
        MovementScene.GobGeom geom = support == null ? null : MovementScene.gobGeom(gui.map.player(), support);
        List<Coord2d[]> collision = geom == null ? Collections.emptyList() : copyPolygons(geom.collision);
        String supportName = supportName(support, geom);
        Coord2d playerWorld = gui.map.player() == null || gui.map.player().rc == null
            ? null : Coord2d.of(gui.map.player().rc.x, gui.map.player().rc.y);
        int cross = MinerBotV3Logic.crossTrackTiles(plan.anchor, heading, plan.playerTile);
        int along = MinerBotV3Logic.alongTrackTiles(plan.anchor, heading, plan.playerTile);
        boolean blocked = "BLOCKED".equals(route);
        String warning = warningFor(cross, supportOffset, blocked, support != null);
        String routeText = route == null ? "not probed" : route.toLowerCase(Locale.ROOT);
        String summary = String.format(Locale.ROOT, "Preview: %s anchor %s (%s) — %s",
            heading.name(), plan.anchor, plan.anchorSource(), routeText);
        current = new Snapshot(heading, plan.anchor, supportWorld, supportName,
            support == null ? -1L : support.id, collision, playerWorld, cross, along,
            supportOffset, plan.chainedSupports, phase, routeText, blocked, false,
            warning, summary, plan.anchorSource(), fanning);
    }

    static void showLeg(GameUI gui, Coord anchor, MinerBotV3Logic.Direction heading, String phase) {
        if(gui == null || gui.map == null || anchor == null || heading == null) return;
        Snapshot old = current;
        Coord2d playerWorld = gui.map.player() == null || gui.map.player().rc == null
            ? null : Coord2d.of(gui.map.player().rc.x, gui.map.player().rc.y);
        Coord playerTile = playerWorld == null ? anchor : playerWorld.floor(MCache.tilesz);
        int cross = MinerBotV3Logic.crossTrackTiles(anchor, heading, playerTile);
        int along = MinerBotV3Logic.alongTrackTiles(anchor, heading, playerTile);
        String warning = warningFor(cross, old == null ? Double.NaN : old.supportOffset,
            false, old != null && old.supportWorld != null);
        current = new Snapshot(heading, anchor,
            old == null ? null : old.supportWorld,
            old == null ? "" : old.supportName,
            old == null ? -1L : old.supportId,
            old == null ? Collections.emptyList() : old.supportCollision,
            playerWorld, cross, along,
            old == null ? Double.NaN : old.supportOffset,
            old == null ? 0 : old.chainedSupports,
            phase, "running", false, false, warning,
            String.format(Locale.ROOT, "V3: %s from %s", heading.name(), anchor),
            old == null ? "session checkpoint" : old.anchorSource,
            old != null && old.fanning);
    }

    static void markFailure(String reason) {
        Snapshot old = current;
        if(old == null) return;
        String message = reason == null || reason.trim().isEmpty() ? "V3 stopped" : reason.trim();
        current = old.withFailure(message);
    }

    public static void clear() {
        current = null;
    }

    static String warningFor(int crossTrack, double supportOffset, boolean routeBlocked) {
        return warningFor(crossTrack, supportOffset, routeBlocked, true);
    }

    static String warningFor(int crossTrack, double supportOffset, boolean routeBlocked,
                             boolean hasLegacySupport) {
        List<String> warnings = new ArrayList<>();
        if(crossTrack != 0) {
            warnings.add(String.format(Locale.ROOT, "player is %d tile%s %s of the computed lane",
                Math.abs(crossTrack), Math.abs(crossTrack) == 1 ? "" : "s",
                crossTrack > 0 ? "right" : "left"));
        }
        if(!Double.isNaN(supportOffset) && supportOffset > 1.5) {
            warnings.add(String.format(Locale.ROOT, "legacy support is %.1f units off its assumed tile point", supportOffset));
        }
        if(routeBlocked) warnings.add("computed anchor is not reachable from the current position");
        if(warnings.isEmpty()) {
            return hasLegacySupport
                ? "Verify that the yellow legacy support belongs on the right of this heading."
                : "Session anchor is locked; the cyan tile will be used on Start.";
        }
        return join(warnings);
    }

    private static String probeAnchor(GameUI gui, MinerBotV3.StartPlan plan) {
        if(gui == null || gui.map == null || gui.map.player() == null || plan == null) return "UNKNOWN";
        Coord2d target = MiningBot.tileCenter(plan.anchor);
        Coord2d player = gui.map.player().rc;
        if(player != null && player.dist(target) <= 2.75) return "AT ANCHOR";
        if(player != null && player.dist(target) > MCache.tilesz.x * 20.0) {
            double distance = MinerBotV3Navigator.routeDistanceToTile(gui,
                plan.segmentId, plan.savedAnchor);
            return Double.isFinite(distance)
                ? String.format(Locale.ROOT, "SAVED ROUTE %.0ft", distance) : "BLOCKED";
        }
        PathfinderLog.beginProbe();
        try {
            MovementScene.Plan routePlan = MovementScene.planAnyCaveAvoiding(gui,
                Collections.singletonList(target), false, Collections.emptyList(), 0.0);
            return routePlan != null && routePlan.status != MovementScene.Plan.Status.FAILED ? "REACHABLE" : "BLOCKED";
        } catch(RuntimeException failure) {
            return "UNKNOWN";
        } finally {
            PathfinderLog.endProbe();
        }
    }

    private static String supportName(Gob support, MovementScene.GobGeom geom) {
        String name = geom == null ? "" : geom.name;
        String resid = geom == null ? "" : geom.resid;
        if(name != null && !name.isEmpty() && !"?".equals(name)) return name;
        if(resid != null && !resid.isEmpty()) return resid;
        return support == null ? "support" : "support #" + support.id;
    }

    private static List<Coord2d[]> copyPolygons(List<Coord2d[]> polygons) {
        if(polygons == null || polygons.isEmpty()) return Collections.emptyList();
        List<Coord2d[]> out = new ArrayList<>();
        for(Coord2d[] polygon : polygons) {
            if(polygon == null) continue;
            Coord2d[] copy = new Coord2d[polygon.length];
            for(int i = 0; i < polygon.length; i++) {
                Coord2d p = polygon[i];
                copy[i] = p == null ? null : Coord2d.of(p.x, p.y);
            }
            out.add(copy);
        }
        return Collections.unmodifiableList(out);
    }

    private static String join(List<String> values) {
        StringBuilder text = new StringBuilder();
        for(String value : values) {
            if(text.length() > 0) text.append("; ");
            text.append(value);
        }
        return text.toString();
    }

    @Override
    public CFG<Boolean> toggle() {
        return CFG.MINER_BOT_V3_PREVIEW_OVERLAY;
    }

    @Override
    public void paint(GOut g, MapView mv) {
        Snapshot snapshot = current;
        if(g == null || mv == null || snapshot == null) return;

        drawPolygons(g, mv, snapshot.supportCollision, COLLISION, 2);
        if(snapshot.playerWorld != null) {
            Coord player = screen(mv, snapshot.playerWorld);
            Coord anchor = screen(mv, MiningBot.tileCenter(snapshot.anchor));
            if(player != null && anchor != null) {
                g.chcolor(snapshot.crossTrack == 0 ? PLAYER_LINK : WARNING);
                g.line(player, anchor, 1.5);
                marker(g, player, PLAYER_LINK, 4, "player");
            }
        }

        Coord endpoint = MinerBotV3Logic.endpoint(snapshot.anchor, snapshot.heading);
        Coord column = MinerBotV3Logic.columnTile(snapshot.anchor, snapshot.heading);
        outlineTile(g, mv, snapshot.anchor, snapshot.failure || snapshot.routeBlocked ? WARNING : ANCHOR, 3);
        outlineTile(g, mv, endpoint, LEG, 2);
        outlineTile(g, mv, column, COLUMN, 3);

        Coord previous = screen(mv, MiningBot.tileCenter(snapshot.anchor));
        g.chcolor(LEG);
        for(int i = 1; i <= MinerBotV3Logic.LEG_TILES; i++) {
            Coord tile = snapshot.anchor.add(snapshot.heading.step().mul(i));
            Coord at = screen(mv, MiningBot.tileCenter(tile));
            if(previous != null && at != null) g.line(previous, at, 2.0);
            if(at != null) {
                g.fellipse(at, Coord.of(i == MinerBotV3Logic.LEG_TILES ? 5 : 3,
                    i == MinerBotV3Logic.LEG_TILES ? 5 : 3));
                if(i == 1 || i == MinerBotV3Logic.LEG_TILES)
                    g.atext(Integer.toString(i), at.add(6, -5), 0, 0);
            }
            previous = at;
        }

        if(snapshot.fanning) {
            Coord fanCenter = MinerBotV3Logic.fanAnchor(snapshot.anchor, snapshot.heading);
            drawFanArm(g, mv, fanCenter, snapshot.heading.left(),
                MinerBotV3Logic.FAN_LEFT_TILES, "fan left");
            drawFanArm(g, mv, fanCenter, snapshot.heading.right(),
                MinerBotV3Logic.FAN_RIGHT_TILES, "fan right");
        }

        Coord anchorScreen = screen(mv, MiningBot.tileCenter(snapshot.anchor));
        if(anchorScreen != null) marker(g, anchorScreen,
            snapshot.failure || snapshot.routeBlocked ? WARNING : ANCHOR, 7, "anchor");
        Coord columnScreen = screen(mv, MiningBot.tileCenter(column));
        if(columnScreen != null) marker(g, columnScreen, COLUMN, 7, "next column");
        Coord supportScreen = screen(mv, snapshot.supportWorld);
        if(supportScreen != null) marker(g, supportScreen, SUPPORT, 7,
            "legacy support" + (snapshot.supportId < 0 ? "" : " #" + snapshot.supportId));

        paintHud(g, mv, snapshot);
        g.chcolor();
    }

    private static void paintHud(GOut g, MapView mv, Snapshot snapshot) {
        int width = Math.min(620, Math.max(420, mv.sz.x - 24));
        Coord base = Coord.of(Math.max(12, (mv.sz.x - width) / 2), 12);
        int height = 84;
        g.chcolor(new Color(0, 0, 0, 185));
        g.frect(base.add(-6, -5), Coord.of(width, height));
        g.chcolor(snapshot.failure || snapshot.routeBlocked ? WARNING : TEXT);
        g.atext(snapshot.failure ? "Miner V3 STOP: " + snapshot.phase : snapshot.summary,
            base, 0, 0);
        g.chcolor(TEXT);
        if(snapshot.supportWorld == null) {
            g.atext("anchor source: " + snapshot.anchorSource,
                base.add(0, 16), 0, 0);
        } else {
            g.atext(String.format(Locale.ROOT,
                "support: %s%s | chained=%d | assumed-point offset=%s",
                snapshot.supportName, snapshot.supportId < 0 ? "" : " #" + snapshot.supportId,
                snapshot.chainedSupports,
                Double.isNaN(snapshot.supportOffset) ? "?" : String.format(Locale.ROOT, "%.1fu", snapshot.supportOffset)),
                base.add(0, 16), 0, 0);
        }
        g.atext(String.format(Locale.ROOT, "player lane: cross=%d along=%d",
            snapshot.crossTrack, snapshot.alongTrack), base.add(0, 32), 0, 0);
        g.chcolor(snapshot.warning.startsWith("Verify") ? SUPPORT
            : snapshot.warning.startsWith("Session anchor") ? ANCHOR : WARNING);
        g.atext(snapshot.warning, base.add(0, 48), 0, 0);
        g.chcolor(TEXT);
        g.atext("yellow=support  red=collision  cyan=anchor  green=leg  blue=fan  magenta=column",
            base.add(0, 64), 0, 0);
    }

    private static void drawFanArm(GOut g, MapView mv, Coord center,
                                   MinerBotV3Logic.Direction direction, int tiles,
                                   String label) {
        Coord previous = screen(mv, MiningBot.tileCenter(center));
        g.chcolor(FAN);
        for(int i = 1; i <= tiles; i++) {
            Coord tile = center.add(direction.step().mul(i));
            Coord at = screen(mv, MiningBot.tileCenter(tile));
            if(previous != null && at != null) g.line(previous, at, 2.0);
            if(at != null) {
                g.fellipse(at, Coord.of(i == tiles ? 5 : 3, i == tiles ? 5 : 3));
                if(i == tiles) g.atext(label, at.add(6, -5), 0, 0);
            }
            previous = at;
        }
    }

    private static void outlineTile(GOut g, MapView mv, Coord tile, Color color, int width) {
        Coord2d origin = MCache.tilesz.mul(tile.x, tile.y);
        Coord[] corners = new Coord[]{
            screen(mv, origin),
            screen(mv, origin.add(MCache.tilesz.x, 0)),
            screen(mv, origin.add(MCache.tilesz.x, MCache.tilesz.y)),
            screen(mv, origin.add(0, MCache.tilesz.y))
        };
        g.chcolor(color);
        for(int i = 0; i < corners.length; i++) {
            Coord a = corners[i];
            Coord b = corners[(i + 1) % corners.length];
            if(a != null && b != null) g.line(a, b, width);
        }
    }

    private static void drawPolygons(GOut g, MapView mv, List<Coord2d[]> polygons,
                                     Color color, int width) {
        g.chcolor(color);
        for(Coord2d[] polygon : polygons) {
            if(polygon == null || polygon.length < 2) continue;
            for(int i = 0; i < polygon.length; i++) {
                Coord a = screen(mv, polygon[i]);
                Coord b = screen(mv, polygon[(i + 1) % polygon.length]);
                if(a != null && b != null) g.line(a, b, width);
            }
        }
    }

    private static void marker(GOut g, Coord at, Color color, int radius, String label) {
        if(at == null) return;
        g.chcolor(color);
        g.line(at.add(-radius, -radius), at.add(radius, radius), 2.0);
        g.line(at.add(-radius, radius), at.add(radius, -radius), 2.0);
        g.fellipse(at, Coord.of(3, 3));
        if(label != null) g.atext(label, at.add(radius + 3, -5), 0, 0);
    }

    private static Coord screen(MapView mv, Coord2d world) {
        if(mv == null || world == null) return null;
        Coord3f projected;
        try {
            projected = mv.screenxf(mv.glob.map.getzp(world));
        } catch(RuntimeException failure) {
            projected = mv.screenxf(world);
        }
        if(projected == null) return null;
        Coord result = Coord.of(Math.round(projected.x), Math.round(projected.y));
        return result.x >= -80 && result.y >= -80
            && result.x <= mv.sz.x + 80 && result.y <= mv.sz.y + 80 ? result : null;
    }

    public static final class PreviewResult {
        public final String summary;
        public final String warning;
        public final boolean reachable;

        PreviewResult(String summary, String warning, boolean reachable) {
            this.summary = summary;
            this.warning = warning;
            this.reachable = reachable;
        }
    }

    private static final class Snapshot {
        final MinerBotV3Logic.Direction heading;
        final Coord anchor;
        final Coord2d supportWorld;
        final String supportName;
        final long supportId;
        final List<Coord2d[]> supportCollision;
        final Coord2d playerWorld;
        final int crossTrack;
        final int alongTrack;
        final double supportOffset;
        final int chainedSupports;
        final String phase;
        final String route;
        final boolean routeBlocked;
        final boolean failure;
        final String warning;
        final String summary;
        final String anchorSource;
        final boolean fanning;

        Snapshot(MinerBotV3Logic.Direction heading, Coord anchor,
                 Coord2d supportWorld, String supportName, long supportId,
                 List<Coord2d[]> supportCollision, Coord2d playerWorld,
                 int crossTrack, int alongTrack, double supportOffset,
                 int chainedSupports, String phase, String route,
                 boolean routeBlocked, boolean failure, String warning, String summary) {
            this(heading, anchor, supportWorld, supportName, supportId,
                supportCollision, playerWorld, crossTrack, alongTrack, supportOffset,
                chainedSupports, phase, route, routeBlocked, failure, warning, summary,
                "automatic support", false);
        }

        Snapshot(MinerBotV3Logic.Direction heading, Coord anchor,
                 Coord2d supportWorld, String supportName, long supportId,
                 List<Coord2d[]> supportCollision, Coord2d playerWorld,
                 int crossTrack, int alongTrack, double supportOffset,
                 int chainedSupports, String phase, String route,
                 boolean routeBlocked, boolean failure, String warning, String summary,
                 String anchorSource, boolean fanning) {
            this.heading = heading;
            this.anchor = new Coord(anchor);
            this.supportWorld = supportWorld;
            this.supportName = supportName == null ? "" : supportName;
            this.supportId = supportId;
            this.supportCollision = supportCollision == null ? Collections.emptyList() : supportCollision;
            this.playerWorld = playerWorld;
            this.crossTrack = crossTrack;
            this.alongTrack = alongTrack;
            this.supportOffset = supportOffset;
            this.chainedSupports = chainedSupports;
            this.phase = phase == null ? "" : phase;
            this.route = route == null ? "" : route;
            this.routeBlocked = routeBlocked;
            this.failure = failure;
            this.warning = warning == null ? "" : warning;
            this.summary = summary == null ? "" : summary;
            this.anchorSource = anchorSource == null ? "" : anchorSource;
            this.fanning = fanning;
        }

        Snapshot withFailure(String reason) {
            return new Snapshot(heading, anchor, supportWorld, supportName, supportId,
                supportCollision, playerWorld, crossTrack, alongTrack, supportOffset,
                chainedSupports, reason, route, routeBlocked, true, warning, summary,
                anchorSource, fanning);
        }
    }
}
