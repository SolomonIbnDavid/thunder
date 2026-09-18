package thunder.mining;

import auto.MinerBotV3;
import auto.MinerBotV3Logic;
import haven.Area;
import haven.Button;
import haven.Coord;
import haven.GameUI;
import haven.Label;
import haven.MiniMap;
import haven.TextEntry;
import haven.UI;
import haven.Widget;
import haven.WindowX;

/** Session-scoped setup window for the first Miner Bot V3 mode. */
public final class MinerBotV3SetupWnd extends WindowX {
    private static MinerBotV3SetupWnd instance;
    private static final int WIDTH = 330;

    static {
        ZonePicker.registerRoleColor(MinerBotV3ZoneStore.ROLE_STORAGE, new Integer[]{255, 160, 0});
        ZonePicker.registerRoleColor(MinerBotV3ZoneStore.ROLE_WATER, new Integer[]{0, 160, 255});
        ZonePicker.registerRoleColor(MinerBotV3ZoneStore.ROLE_FOOD, new Integer[]{0, 220, 100});
    }

    private final TextEntry direction;
    private final TextEntry bars;
    private final TextEntry cap;
    private final Label runtimeStatus;
    private String lastRuntimeStatus;

    private MinerBotV3SetupWnd() {
        super(Coord.z, "Miner Bot V3");
        justclose = true;
        int y = 0;
        add(new Label("Mode: Straight exploratory miner"), 0, y);
        y += UI.scale(22);
        add(new Label("Fixed leg: 11 tiles | Column: 30 stones"), 0, y);
        y += UI.scale(18);
        add(new Label("Eat below 2,500% → 8,000% | refill empty water"), 0, y);
        y += UI.scale(26);

        add(new Label("Direction (n/s/e/w):"), 0, y);
        direction = add(new TextEntry(UI.scale(55), "n"), UI.scale(205), y);
        y += direction.sz.y + UI.scale(6);

        add(new Label("Minimum Bronze/Wrought bars:"), 0, y);
        bars = add(new TextEntry(UI.scale(55), "10"), UI.scale(255), y);
        y += bars.sz.y + UI.scale(6);

        add(new Label("Safety cap (columns, 0 = unlimited):"), 0, y);
        cap = add(new TextEntry(UI.scale(55), "0"), UI.scale(255), y);
        y += cap.sz.y + UI.scale(12);

        y = zoneRow(y, "Stone/bar storage", MinerBotV3ZoneStore.ROLE_STORAGE);
        y = zoneRow(y, "Water", MinerBotV3ZoneStore.ROLE_WATER);
        y = zoneRow(y, "Energy food", MinerBotV3ZoneStore.ROLE_FOOD);
        y += UI.scale(8);

        add(new Button(UI.scale(100), "Start") {
            public void click() {startBot();}
        }, 0, y);
        add(new Button(UI.scale(100), "Stop") {
            public void click() {MinerBotV3.stop();}
        }, UI.scale(110), y);
        y += UI.scale(34);

        lastRuntimeStatus = MinerBotV3.status();
        runtimeStatus = add(new Label(lastRuntimeStatus), 0, y);
        pack();
        if(ca().sz().x < UI.scale(WIDTH)) resize(new Coord(UI.scale(WIDTH), ca().sz().y));
    }

    @Override
    protected void added() {
        super.added();
        if(ui != null && ui.sess != null) MinerBotV3ZoneStore.get().bind(ui.sess);
        showZones();
    }

    @Override
    public void tick(double dt) {
        super.tick(dt);
        String current = MinerBotV3.status();
        if(!current.equals(lastRuntimeStatus)) {
            lastRuntimeStatus = current;
            runtimeStatus.settext(current);
        }
    }

    private int zoneRow(int y, String name, String role) {
        add(new Label(name + ":"), 0, y);
        y += UI.scale(16);
        Label status = add(new Label(zoneStatus(role)), 0, y);
        add(new Button(UI.scale(60), "Set") {
            public void click() {pick(role, status);}
        }, UI.scale(245), y - UI.scale(2));
        return y + UI.scale(23);
    }

    private void pick(String role, Label label) {
        GameUI gui = ui.gui;
        MiniMap.Location location = gui != null && gui.mapfile != null
            ? gui.mapfile.playerLocation() : null;
        if(location == null || location.seg == null || location.tc == null) {
            gui.error("Miner Bot V3: saved cave-map location is unavailable.");
            return;
        }
        gui.msg("Drag a rectangle for the Miner Bot V3 " + roleName(role) + ".", GameUI.MsgType.INFO);
        ZonePicker.start(gui.map, role, live -> {
            MiniMap.Location pickedAt = gui.mapfile == null ? null : gui.mapfile.playerLocation();
            if(pickedAt == null || pickedAt.seg == null || pickedAt.tc == null) {
                gui.error("Miner Bot V3: saved cave-map location disappeared while selecting the area.");
                return;
            }
            MinerBotV3ZoneStore.SavedArea saved = MinerBotV3ZoneStore.SavedArea.fromLive(
                pickedAt.seg.id, pickedAt.tc, live);
            MinerBotV3ZoneStore.get().put(gui.ui.sess, role, saved);
            ZonePicker.showZone(gui.map, role, live);
            label.settext(zoneStatus(role));
            gui.msg("Miner Bot V3 " + roleName(role) + " set for this login session.", GameUI.MsgType.GOOD);
        });
    }

    private String zoneStatus(String role) {
        if(ui == null || ui.sess == null) return "not set";
        MinerBotV3ZoneStore.SavedArea saved = MinerBotV3ZoneStore.get().get(ui.sess, role);
        if(saved == null) return "not set (optional until needed)";
        Coord size = saved.area.sz();
        return "set (" + size.x + "x" + size.y + ", segment " + Long.toHexString(saved.segmentId) + ")";
    }

    private void showZones() {
        if(ui == null || ui.gui == null || ui.gui.map == null || ui.gui.mapfile == null) return;
        MiniMap.Location location = ui.gui.mapfile.playerLocation();
        if(location == null || location.seg == null || location.tc == null) return;
        for(String role : roles()) {
            MinerBotV3ZoneStore.SavedArea saved = MinerBotV3ZoneStore.get().get(ui.sess, role);
            if(saved != null && saved.segmentId == location.seg.id) {
                Area live = saved.liveArea(location.tc);
                ZonePicker.showZone(ui.gui.map, role, live);
            }
        }
    }

    private void startBot() {
        MinerBotV3Logic.Direction dir;
        int barTarget;
        int safetyCap;
        try {
            dir = MinerBotV3Logic.Direction.parse(direction.text());
            barTarget = Integer.parseInt(bars.text().trim());
            safetyCap = Integer.parseInt(cap.text().trim());
        } catch(IllegalArgumentException failure) {
            ui.gui.error("Miner Bot V3: " + failure.getMessage());
            return;
        }
        MinerBotV3.start(ui.gui, dir, barTarget, safetyCap);
    }

    private static String roleName(String role) {
        if(MinerBotV3ZoneStore.ROLE_STORAGE.equals(role)) return "stone/bar storage area";
        if(MinerBotV3ZoneStore.ROLE_WATER.equals(role)) return "water area";
        return "food area";
    }

    private static String[] roles() {
        return new String[]{MinerBotV3ZoneStore.ROLE_STORAGE,
            MinerBotV3ZoneStore.ROLE_WATER, MinerBotV3ZoneStore.ROLE_FOOD};
    }

    public static void toggle(Widget parent) {
        if(instance == null) instance = parent.add(new MinerBotV3SetupWnd());
        else instance.reqdestroy();
    }

    @Override
    public void destroy() {
        ZonePicker.cancel();
        for(String role : roles()) ZonePicker.hideZone(role);
        super.destroy();
        instance = null;
    }
}
