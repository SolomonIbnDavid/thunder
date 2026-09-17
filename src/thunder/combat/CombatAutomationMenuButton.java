package thunder.combat;

import haven.GSprite;
import haven.MenuGrid;
import haven.Message;
import haven.Resource;
import me.ender.CustomPagButton;
import me.ender.CustomPaginaAction;

import java.util.function.Supplier;

/** Reuses an existing combat icon while the local action remains code-backed. */
public final class CombatAutomationMenuButton extends CustomPagButton {
    private static final Resource ICON = Resource.local().loadwait("paginae/add/auto/aggro_one");
    private GSprite icon;

    public CombatAutomationMenuButton(MenuGrid.Pagina pagina, CustomPaginaAction action,
                                      Supplier<Boolean> toggleState) {
        super(pagina, action, toggleState);
    }

    @Override
    public GSprite spr() {
        if(icon == null)
            icon = GSprite.create(this, ICON, Message.nil);
        return(icon);
    }

    @Override
    public void tick(double dt) {
        if(icon != null)
            icon.tick(dt);
    }
}
