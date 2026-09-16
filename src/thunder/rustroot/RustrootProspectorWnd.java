package thunder.rustroot;

import auto.Bot;
import haven.Button;
import haven.Coord;
import haven.Coord2d;
import haven.FlowerMenu;
import haven.GameUI;
import haven.Gob;
import haven.Label;
import haven.Loading;
import haven.MCache;
import haven.UI;
import haven.WItem;
import haven.Widget;
import haven.WindowX;
import haven.rx.Reactor;
import haven.pathfinding.BotMovement;
import me.ender.ClientUtils;
import me.ender.ItemHelpers;
import rx.Subscription;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Guided Rustroot Extract triangulation assistant. */
public class RustrootProspectorWnd extends WindowX {
    private static RustrootProspectorWnd instance;

    private final List<RustrootGeometry.Reading> readings = new ArrayList<>();
    private final Label status;
    private final Label estimate;
    private final Label last;
    private RustrootGeometry.Point suggestion;
    private Subscription flowerWait;
    private FlowerMenu pendingFlower;
    private long flowerDeadline;
    private List<WItem> scanCandidates = new ArrayList<>();
    private int scanCandidateIndex;
    private boolean tryNextCandidate;
    private WItem cachedExtract;
    private Widget cachedExtractParent;
    private Coord cachedExtractPosition;
    final List<TileResult> tileResults = new ArrayList<>();

    static final class TileResult {
        final Coord2d at;
        final String name;
        final boolean ore;

        TileResult(Coord2d at, String name, boolean ore) {
            this.at = at;
            this.name = name;
            this.ore = ore;
        }
    }

    private RustrootProspectorWnd() {
        super(Coord.z, "Rustroot Prospector");
        RustrootTileOverlay.init();
        justclose = true;
        int y = 0;
        status = add(new Label("No readings yet."), 0, y);
        y += UI.scale(22);
        estimate = add(new Label("Next: scan once to establish a bearing."), 0, y);
        y += UI.scale(22);
        last = add(new Label("Result: --"), 0, y);
        y += UI.scale(28);

        add(new Button(UI.scale(90), "Scan here", this::scanHere), 0, y);
        add(new Button(UI.scale(90), "Go to next", this::goToNext), UI.scale(100), y);
        add(new Button(UI.scale(75), "Clear", this::clearReadings), UI.scale(200), y);
        y += UI.scale(32);
        add(new Label("Each move and each dose remain under your control."), 0, y);
        pack();
        resize(new Coord(Math.max(sz.x, UI.scale(300)), sz.y));
    }

    public static void toggle(Widget parent) {
        if(instance == null) {
            instance = parent.add(new RustrootProspectorWnd());
        } else {
            instance.reqdestroy();
        }
    }

    static RustrootProspectorWnd current() {
        return instance;
    }

    public static void onCone(Coord2d origin, double a1, double a2, double quality, double range) {
        RustrootProspectorWnd wnd = instance;
        if(wnd == null || origin == null) {return;}
        wnd.readings.add(new RustrootGeometry.Reading(
            new RustrootGeometry.Point(origin.x, origin.y), a1, a2, quality, range));
        wnd.last.settext(String.format("Result: directional hit (Q %.1f)", quality));
        wnd.recalculate();
    }

    public static void onDirectResult(Coord2d origin, String resource) {
        RustrootProspectorWnd wnd = instance;
        if(wnd == null || origin == null || resource == null) {return;}
        String name = ClientUtils.prettyResName(resource);
        boolean ore = RustrootGeometry.isOre(resource) || RustrootGeometry.isOre(name);
        wnd.last.settext("Directly below: " + name);
        synchronized(wnd.tileResults) {
            wnd.tileResults.add(new TileResult(tileCenter(origin), name, ore));
        }
        if(ore) {
            wnd.ui.gui.mapfile.addMarker(origin.floor(MCache.tilesz), name + " (below)");
            wnd.estimate.settext("Ore confirmed; permanent map marker added.");
        } else {
            wnd.estimate.settext("Temporary tile marker added for this search.");
        }
    }

    private static Coord2d tileCenter(Coord2d world) {
        Coord tile = world.floor(MCache.tilesz);
        return MCache.tilesz.mul(tile.x, tile.y).add(MCache.tilesz.x / 2.0, MCache.tilesz.y / 2.0);
    }

    /** Remembers the inventory slot from any successful manual or assisted Prospect action. */
    public static void rememberExtract(WItem item) {
        RustrootProspectorWnd wnd = instance;
        if(wnd == null || item == null) {return;}
        wnd.cachedExtract = item;
        wnd.cachedExtractParent = item.parent;
        wnd.cachedExtractPosition = item.c;
    }

    private void recalculate() {
        suggestion = RustrootGeometry.suggest(readings);
        List<RustrootGeometry.Point> candidates = RustrootGeometry.candidates(readings);
        status.settext(String.format("Readings: %d   possible tiles: %d", readings.size(), candidates.size()));
        if(suggestion == null) {
            estimate.settext("Cones do not overlap; likely separate deposits. Clear or reposition.");
        } else {
            estimate.settext(String.format("Next tile: %d, %d", tile(suggestion.x), tile(suggestion.y)));
        }
    }

    private static int tile(double world) {
        return (int)Math.floor(world / RustrootGeometry.TILE);
    }

    private void clearReadings() {
        readings.clear();
        synchronized(tileResults) {tileResults.clear();}
        suggestion = null;
        status.settext("No readings yet.");
        estimate.settext("Next: scan once to establish a bearing.");
        last.settext("Result: --");
    }

    private void goToNext() {
        if(suggestion == null) {
            ui.gui.error("No suggested scan tile yet.");
            return;
        }
        Coord2d target = new Coord2d(suggestion.x, suggestion.y);
        GameUI gui = ui.gui;
        if(Bot.hasCurrent()) {
            gui.error("Another automation task is already running.");
            return;
        }
        Bot.execute((unused, bot) -> {
            BotMovement.Result result = BotMovement.moveTo(gui, bot, target, BotMovement.Mode.LAND, 120000L);
            if(result.status != BotMovement.Status.ARRIVED)
                gui.error("Could not reach the suggested scan tile (" + result.status + ").");
        }).start(gui.ui, true);
    }

    private void scanHere() {
        GameUI gui = ui.gui;
        Gob player = gui.map == null ? null : gui.map.player();
        if(player == null) {
            gui.error("Player position is not available.");
            return;
        }
        scanCandidates = new ArrayList<>();
        ItemHelpers.findAll(ui, this::couldContainExtract).forEach(scanCandidates::add);
        scanCandidates.sort(Comparator.comparingInt(this::candidateRank));
        WItem remembered = resolveRememberedExtract();
        if(remembered != null) {
            scanCandidates.remove(remembered);
            scanCandidates.add(0, remembered);
        } else {
            // If the live tooltip does not identify the liquid, do not spend seconds
            // probing every jar. One manual Prospect teaches us the correct slot.
            scanCandidates.removeIf(item -> candidateRank(item) > 1);
        }
        scanCandidateIndex = 0;
        if(scanCandidates.isEmpty()) {
            gui.error("Prospect manually with the extract jar once; Scan here will remember that slot afterward.");
            last.settext("Result: waiting for one manual Prospect to identify the jar.");
            return;
        }
        tryCandidate();
    }

    private WItem resolveRememberedExtract() {
        if(cachedExtract != null && !cachedExtract.disposed()) {return cachedExtract;}
        if(cachedExtractParent == null || cachedExtractParent.disposed() || cachedExtractPosition == null) {return null;}
        for(WItem item : cachedExtractParent.children(WItem.class)) {
            if(!item.disposed() && cachedExtractPosition.equals(item.c)) {
                cachedExtract = item;
                return item;
            }
        }
        return null;
    }

    private boolean couldContainExtract(WItem item) {
        if(item == null || item.disposed()) {return false;}
        try {
            String res = item.item.resname().toLowerCase();
            boolean likely = res.contains("rustroot") || item.item.is("Rustroot Extract")
                || item.item.is("Rustroot");
            boolean nonemptyJar = res.contains("jar") && !item.item.contains.get().empty();
            return likely || nonemptyJar;
        } catch(Loading l) {
            return false;
        }
    }

    private int candidateRank(WItem item) {
        if(item == cachedExtract) {return 0;}
        try {
            String content = item.item.contains.get().name;
            if(content != null) {
                String lower = content.toLowerCase();
                if(lower.contains("rustroot") || lower.contains("extract")) {return 1;}
            }
            String name = item.item.name.get("").toLowerCase();
            if(name.contains("rustroot") || name.contains("extract")) {return 1;}
        } catch(Loading ignored) {}
        return 2;
    }

    private void tryCandidate() {
        while(scanCandidateIndex < scanCandidates.size() && scanCandidates.get(scanCandidateIndex).disposed()) {
            scanCandidateIndex++;
        }
        if(scanCandidateIndex >= scanCandidates.size()) {
            flowerDeadline = 0;
            ui.gui.error("None of the accessible jars offers Prospect.");
            last.settext("Result: no prospectable extract jar found.");
            return;
        }
        WItem extract = scanCandidates.get(scanCandidateIndex);
        if(flowerWait != null && !flowerWait.isUnsubscribed()) {flowerWait.unsubscribe();}
        flowerDeadline = System.currentTimeMillis() + 750;
        flowerWait = Reactor.FLOWER.first().subscribe(menu -> pendingFlower = menu);
        // WItem.rclick() does not seed FlowerMenu's item target; mirror the real
        // mouse-right-click path so ProspectingWnd receives the extract quality.
        FlowerMenu.lastItem(extract);
        extract.item.wdgmsg("iact", Coord.z, 0);
        last.settext(String.format("Result: checking jar %d of %d...", scanCandidateIndex + 1, scanCandidates.size()));
    }

    @Override
    public void tick(double dt) {
        super.tick(dt);
        if(tryNextCandidate) {
            tryNextCandidate = false;
            tryCandidate();
            return;
        }
        if(pendingFlower != null && pendingFlower.opts != null) {
            FlowerMenu menu = pendingFlower;
            pendingFlower = null;
            flowerDeadline = 0;
            FlowerMenu.Petal prospect = null;
            for(FlowerMenu.Petal p : menu.opts) {
                if("Prospect".equals(p.name)) {prospect = p; break;}
            }
            if(prospect != null) {
                cachedExtract = scanCandidates.get(scanCandidateIndex);
                cachedExtractParent = cachedExtract.parent;
                cachedExtractPosition = cachedExtract.c;
                menu.choose(prospect);
                last.settext("Result: scanning...");
            } else {
                menu.choose(null);
                scanCandidateIndex++;
                tryNextCandidate = true;
            }
        } else if(flowerDeadline != 0 && System.currentTimeMillis() > flowerDeadline) {
            flowerDeadline = 0;
            pendingFlower = null;
            scanCandidateIndex++;
            tryNextCandidate = true;
        }
    }

    @Override
    public void destroy() {
        if(flowerWait != null && !flowerWait.isUnsubscribed()) {flowerWait.unsubscribe();}
        pendingFlower = null;
        scanCandidates.clear();
        synchronized(tileResults) {tileResults.clear();}
        super.destroy();
        instance = null;
    }
}
