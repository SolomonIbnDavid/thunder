package thunder.woodcut;

import auto.Bot;
import haven.*;
import thunder.mining.ZonePicker;

/** Setup window for cutting loose or cart-loaded logs into board/block stockpiles. */
public class WoodCutSetupWnd extends WindowX {
    private static WoodCutSetupWnd instance;
    private static final Integer[] RGB_LOGS = {90, 190, 255};
    private static final Integer[] RGB_OUTPUT = {255, 165, 50};
    private WoodCutRules.Product product = WoodCutRules.Product.BOARDS;
    private final Label areas;
    private final Label runStatus;

    static {
        ZonePicker.registerRoleColor(WoodCutZoneStore.ROLE_LOGS, RGB_LOGS);
        ZonePicker.registerRoleColor(WoodCutZoneStore.ROLE_OUTPUT, RGB_OUTPUT);
    }

    private WoodCutSetupWnd() {
        super(Coord.z, "Log Cutter");
        justclose = false;
        int y = 0;
        add(new Label("Cut logs into:"), 0, y);
        RadioGroup group = new RadioGroup(this) {
            public void changed(int btn, String label) {
                product = (btn == 0) ? WoodCutRules.Product.BOARDS : WoodCutRules.Product.BLOCKS;
            }
        };
        group.add("Boards", Coord.of(UI.scale(100), y));
        group.add("Blocks", Coord.of(UI.scale(180), y));
        group.check(0);
        y += UI.scale(28);
        y = zoneRow(y, "Logs / carts area", WoodCutZoneStore.ROLE_LOGS);
        y = zoneRow(y, "Stockpile area", WoodCutZoneStore.ROLE_OUTPUT);
        areas = add(new Label(areaText()), 0, y);
        y += UI.scale(22);
        add(new Label("Stops below 4,000% energy or with no water."), 0, y);
        y += UI.scale(20);
        runStatus = add(new Label(WoodCutBot.status()), 0, y);
        y += UI.scale(24);
        add(new Button(UI.scale(90), "Start") {public void click() {startBot();}}, 0, y);
        add(new Button(UI.scale(90), "Stop") {public void click() {WoodCutBot.stop();}}, UI.scale(98), y);
        add(new Button(UI.scale(110), "Debug snapshot") {public void click() {WoodCutBot.snapshot(ui.gui, "manual");}}, UI.scale(196), y);
        pack();
    }

    public void tick(double dt) {
        super.tick(dt);
        runStatus.settext(WoodCutBot.status());
    }

    protected void added() {
        super.added();
        MapView map = ui.gui.map;
        for(String role : new String[]{WoodCutZoneStore.ROLE_LOGS, WoodCutZoneStore.ROLE_OUTPUT}) {
            Area a = WoodCutZoneStore.get().get(role);
            if(a != null) ZonePicker.showZone(map, role, a);
        }
    }

    private int zoneRow(int y, String title, String role) {
        Label state = add(new Label(zoneText(role)), UI.scale(150), y);
        add(new Label(title + ":"), 0, y);
        add(new Button(UI.scale(55), "Set") {
            public void click() {
                ui.gui.msg("Drag the " + title.toLowerCase() + " on the map.", GameUI.MsgType.INFO);
                ZonePicker.start(ui.gui.map, role, a -> {
                    WoodCutZoneStore.get().put(role, a);
                    ZonePicker.showZone(ui.gui.map, role, a);
                    state.settext(zoneText(role));
                    areas.settext(areaText());
                });
            }
        }, UI.scale(245), y - UI.scale(2));
        return y + UI.scale(25);
    }

    private String zoneText(String role) {
        Area a = WoodCutZoneStore.get().get(role);
        return a == null ? "not set" : a.sz().x + "x" + a.sz().y + " tiles";
    }

    private String areaText() {
        return WoodCutZoneStore.get().get(WoodCutZoneStore.ROLE_LOGS) != null &&
            WoodCutZoneStore.get().get(WoodCutZoneStore.ROLE_OUTPUT) != null ?
            "Status: both areas set" : "Status: select both areas";
    }

    private void startBot() {
        if(Bot.hasCurrent() && !WoodCutBot.isRunning()) {
            ui.gui.error("Log Cutter: another bot is already running.");
            return;
        }
        WoodCutBot.start(ui.gui, product,
            WoodCutZoneStore.get().get(WoodCutZoneStore.ROLE_LOGS),
            WoodCutZoneStore.get().get(WoodCutZoneStore.ROLE_OUTPUT));
    }

    public static void toggle(Widget parent) {
        if(instance == null) instance = parent.add(new WoodCutSetupWnd());
        else {instance.reqdestroy(); instance = null;}
    }

    public void destroy() {
        ZonePicker.cancel();
        ZonePicker.hideZone(WoodCutZoneStore.ROLE_LOGS);
        ZonePicker.hideZone(WoodCutZoneStore.ROLE_OUTPUT);
        WoodCutZoneStore.get().clearAll();
        super.destroy();
        instance = null;
    }
}
