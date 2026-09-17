package thunder.combat;

import haven.Button;
import haven.Coord;
import haven.GameUI;
import haven.Label;
import haven.UI;
import haven.Widget;
import haven.WindowX;

/** Shift-click settings/status shell for future combat profiles. */
public final class CombatAutomationWnd extends WindowX {
    private static CombatAutomationWnd instance;
    private final StatusLabel status;
    private final Button toggle;
    private final Label advanced;
    private String shownStatus;

    private CombatAutomationWnd() {
        super(Coord.z, "Combat Automation");
        justclose = false;
        int y = 0;
        add(new Label("Profile: all combat targets"), 0, y);
        y += UI.scale(20);
        add(new Label("Quick Barrage to 55% enemy red; then Full Circle."), 0, y);
        y += UI.scale(20);
        add(new Label(String.format("At %d%% own opening, use a matching restoration first.",
            CombatAutomationRules.OWN_OPENING_LIMIT)), 0, y);
        y += UI.scale(20);
        add(new Label(String.format("Queue each move %d ms before cooldown expiry.",
            Math.round(CombatAutomationRules.ACTION_QUEUE_LEAD * 1000))), 0, y);
        y += UI.scale(28);
        add(new Label("Status:"), 0, y);
        shownStatus = CombatAutomation.status();
        status = add(new StatusLabel(shownStatus, UI.scale(430)), UI.scale(55), y);
        y += status.sz.y + UI.scale(12);
        toggle = add(new Button(UI.scale(130), toggleText()) {
            public void click() {
                CombatAutomation.toggle(ui.gui);
                updateView();
            }
        }, 0, y);
        y += toggle.sz.y + UI.scale(12);
        advanced = add(new Label("Advanced settings will be added here later."), 0, y);
        pack();
    }

    @Override
    public void tick(double dt) {
        super.tick(dt);
        updateView();
    }

    private void updateView() {
        String current = CombatAutomation.status();
        if(!current.equals(shownStatus)) {
            shownStatus = current;
            status.settext(current);
            int y = status.c.y + status.sz.y + UI.scale(12);
            toggle.move(Coord.of(0, y));
            advanced.move(Coord.of(0, y + toggle.sz.y + UI.scale(12)));
            pack();
        }
        toggle.change(toggleText());
    }

    private String toggleText() {
        return(CombatAutomation.enabled() ? "Disable" : "Enable");
    }

    public static void toggle(Widget parent) {
        if(instance == null)
            instance = parent.add(new CombatAutomationWnd());
        else {
            instance.reqdestroy();
            instance = null;
        }
    }

    @Override
    public void destroy() {
        super.destroy();
        instance = null;
    }

    private static final class StatusLabel extends Label {
        private final int width;

        StatusLabel(String text, int width) {
            super(text, width);
            this.width = width;
        }

        @Override
        public void settext(String text) {
            if(text.equals(this.texts))
                return;
            this.text.dispose();
            this.text = f.renderwrap(texts = text, col, width);
            resize(this.text.sz());
        }
    }
}
