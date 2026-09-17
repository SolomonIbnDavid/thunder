package thunder.combat;

import haven.Buff;
import haven.Bufflist;
import haven.Debug;
import haven.Fightsess;
import haven.Fightview;
import haven.GameUI;
import haven.Gob;
import haven.GobTag;
import haven.Loading;
import haven.MenuGrid;
import haven.OwnerContext;
import haven.Resource;
import haven.UI;
import haven.Utils;
import haven.Widget;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;

/** UI-thread controller for the conservative small-animal combat profile. */
public final class CombatAutomation extends Widget {
    private static final String QUICK_BARRAGE = "paginae/atk/barrage";
    private static final String FULL_CIRCLE = "paginae/atk/fullcircle";
    private static final double ACK_TIMEOUT = 3.0;
    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private static CombatAutomation instance;
    private static String lastStatus = "Disabled";

    private final GameUI gui;
    private final PrintWriter log;
    private long targetId = -1;
    private double pendingSince;
    private double pendingLastUse;
    private String pendingAction;
    private String lastLoggedState;

    private CombatAutomation(GameUI gui) {
        this.gui = gui;
        this.log = openLog();
        setStatus("Enabled; waiting for combat");
        log("enabled profile=small-animal enemy-red-target=" +
            CombatAutomationRules.ENEMY_RED_TARGET + " own-opening-limit=" +
            CombatAutomationRules.OWN_OPENING_LIMIT);
    }

    public static boolean paginaAction(OwnerContext ctx, MenuGrid.Interaction iact) {
        UI ui = ctx.context(UI.class);
        if(ui == null || ui.gui == null)
            return(false);
        if(iact != null && (iact.modflags & UI.MOD_SHIFT) != 0) {
            CombatAutomationWnd.toggle(ui.gui);
            return(false);
        }
        toggle(ui.gui);
        return(true);
    }

    public static void toggle(GameUI gui) {
        if(gui == null)
            return;
        if(instance != null) {
            lastStatus = "Disabled";
            instance.log("disabled by user");
            instance.reqdestroy();
            gui.msg("Combat Automation disabled.", GameUI.MsgType.INFO);
        } else {
            instance = gui.add(new CombatAutomation(gui));
            gui.msg("Combat Automation enabled (small animals; bears excluded).", GameUI.MsgType.GOOD);
        }
    }

    public static boolean enabled() {
        return(instance != null);
    }

    public static String status() {
        return(lastStatus);
    }

    @Override
    public void tick(double dt) {
        super.tick(dt);
        if(instance != this)
            return;

        Fightview fv = gui.fv;
        Fightsess fsess = gui.fsess;
        double now = Utils.rtime();
        if(pendingAction != null) {
            if(fv == null || fsess == null || fv.current == null) {
                log("combat ended while awaiting acknowledgement action=" + pendingAction);
                pendingAction = null;
                targetId = -1;
                updateState("Enabled; waiting for combat");
                return;
            } else if(fv.lastuse > pendingLastUse) {
                log("ack action=" + pendingAction);
                pendingAction = null;
            } else if(now - pendingSince >= ACK_TIMEOUT) {
                disableWithError("No server acknowledgement for " + displayName(pendingAction) + ".");
                return;
            } else {
                return;
            }
        }

        if(fv == null || fsess == null || fv.current == null) {
            targetId = -1;
            updateState("Enabled; waiting for combat");
            return;
        }

        Fightview.Relation relation = fv.current;
        Gob target = gui.ui.sess.glob.oc.getgob(relation.gobid);
        if(target == null || relation.invalid) {
            updateState("Waiting for current target data");
            return;
        }
        if(targetId != target.id) {
            targetId = target.id;
            log("target id=" + target.id + " resource=" + target.resid());
        }

        TargetSupport support = targetSupport(target);
        if(support != TargetSupport.SUPPORTED) {
            updateState(support == TargetSupport.BEAR ?
                "Paused: bears are not supported yet" : "Paused: target is not a supported wild animal");
            return;
        }

        Integer ownGreen = opening(fv.buffs, Buff.OPEN_GREEN);
        Integer ownYellow = opening(fv.buffs, Buff.OPEN_YELLOW);
        Integer ownRed = opening(fv.buffs, Buff.OPEN_RED);
        Integer ownBlue = opening(fv.buffs, Buff.OPEN_BLUE);
        Integer enemyRed = opening(relation.buffs, Buff.OPEN_RED);
        if(ownGreen == null || ownYellow == null || ownRed == null || ownBlue == null || enemyRed == null) {
            updateState("Waiting for opening data");
            return;
        }

        CombatAutomationRules.Snapshot snapshot = new CombatAutomationRules.Snapshot(
            true, true, now >= fv.atkct,
            ownGreen, ownYellow, ownRed, ownBlue, enemyRed);
        CombatAutomationRules.Decision decision = CombatAutomationRules.decide(snapshot);
        if(decision == CombatAutomationRules.Decision.WAIT) {
            updateState(String.format("Cooldown; enemy red %d%%, own max %d%%",
                enemyRed, max(ownGreen, ownYellow, ownRed, ownBlue)));
            return;
        }

        List<String> candidates;
        switch(decision) {
        case QUICK_BARRAGE:
            candidates = Collections.singletonList(QUICK_BARRAGE);
            break;
        case FULL_CIRCLE:
            candidates = Collections.singletonList(FULL_CIRCLE);
            break;
        case RESTORE_GREEN:
            candidates = CombatAutomationRules.restorationCandidates(CombatAutomationRules.Opening.GREEN);
            break;
        case RESTORE_YELLOW:
            candidates = CombatAutomationRules.restorationCandidates(CombatAutomationRules.Opening.YELLOW);
            break;
        case RESTORE_RED:
            candidates = CombatAutomationRules.restorationCandidates(CombatAutomationRules.Opening.RED);
            break;
        case RESTORE_BLUE:
            candidates = CombatAutomationRules.restorationCandidates(CombatAutomationRules.Opening.BLUE);
            break;
        default:
            updateState("Paused: unsupported target");
            return;
        }

        ActionChoice choice = chooseAction(fsess, candidates, now);
        if(choice.loading) {
            updateState("Waiting for combat deck data");
            return;
        }
        if(!choice.found) {
            disableWithError("Required move is not in the active combat deck: " +
                candidateNames(candidates) + ".");
            return;
        }
        if(choice.slot < 0) {
            updateState("Move cooldown: " + candidateNames(candidates));
            return;
        }

        String state = String.format("%s; enemy red %d%%, own G/Y/R/B %d/%d/%d/%d%%",
            displayName(choice.resource), enemyRed, ownGreen, ownYellow, ownRed, ownBlue);
        updateState(state);
        pendingSince = now;
        pendingLastUse = fv.lastuse;
        pendingAction = choice.resource;
        log("send slot=" + choice.slot + " action=" + choice.resource +
            " enemy-red=" + enemyRed + " own=" + ownGreen + "/" + ownYellow +
            "/" + ownRed + "/" + ownBlue);
        if(!fsess.triggerAction(choice.slot, target.rc)) {
            pendingAction = null;
            disableWithError("Combat action slot became unavailable.");
        }
    }

    private static Integer opening(Bufflist list, String resource) {
        boolean loading = false;
        for(Buff buff : list.children(Buff.class)) {
            try {
                if(resource.equals(buff.resource().name)) {
                    int value = buff.ameter();
                    return(value < 0 ? null : value);
                }
            } catch(Loading l) {
                loading = true;
            }
        }
        return(loading ? null : 0);
    }

    private static ActionChoice chooseAction(Fightsess fsess, List<String> candidates, double now) {
        boolean loading = false;
        boolean found = false;
        for(String candidate : candidates) {
            for(int slot = 0; slot < fsess.actions.length; slot++) {
                Fightsess.Action action = fsess.actions[slot];
                if(action == null)
                    continue;
                try {
                    Resource resource = action.res.get();
                    if(candidate.equals(resource.name)) {
                        found = true;
                        if(now >= action.ct)
                            return(new ActionChoice(slot, candidate, true, false));
                    }
                } catch(Loading l) {
                    loading = true;
                }
            }
        }
        return(new ActionChoice(-1, null, found, !found && loading));
    }

    private static TargetSupport targetSupport(Gob target) {
        String resource = target.resid();
        if(resource != null && resource.startsWith("gfx/kritter/bear/"))
            return(TargetSupport.BEAR);
        if(!target.is(GobTag.ANIMAL) || target.anyOf(GobTag.DOMESTIC, GobTag.PLAYER,
            GobTag.CRITTER, GobTag.DEAD, GobTag.KO))
            return(TargetSupport.UNSUPPORTED);
        return(TargetSupport.SUPPORTED);
    }

    private void updateState(String state) {
        setStatus(state);
        if(!state.equals(lastLoggedState)) {
            lastLoggedState = state;
            log("state " + state);
        }
    }

    private void disableWithError(String reason) {
        setStatus("Stopped: " + reason);
        log("stopped reason=" + reason);
        gui.error("Combat Automation stopped: " + reason);
        reqdestroy();
    }

    private static void setStatus(String status) {
        lastStatus = status;
    }

    private static String candidateNames(List<String> candidates) {
        StringBuilder result = new StringBuilder();
        for(String candidate : candidates) {
            if(result.length() > 0)
                result.append(" / ");
            result.append(displayName(candidate));
        }
        return(result.toString());
    }

    private static String displayName(String resource) {
        if(QUICK_BARRAGE.equals(resource)) return("Quick Barrage");
        if(FULL_CIRCLE.equals(resource)) return("Full Circle");
        if("paginae/atk/zigzag".equals(resource)) return("Zig-Zag");
        if("paginae/atk/artevade".equals(resource)) return("Artful Evasion");
        if("paginae/atk/regain".equals(resource)) return("Regain Composure");
        if("paginae/atk/jump".equals(resource)) return("Jump");
        if("paginae/atk/qdodge".equals(resource)) return("Quick Dodge");
        if("paginae/atk/sidestep".equals(resource)) return("Sidestep");
        return(resource == null ? "combat move" : resource);
    }

    private static int max(int... values) {
        int result = Integer.MIN_VALUE;
        for(int value : values)
            result = Math.max(result, value);
        return(result);
    }

    private static PrintWriter openLog() {
        try {
            Path dir = Debug.somedir("combat-automation-logs");
            Files.createDirectories(dir);
            Path file = dir.resolve("combat-automation-" + FILE_TIME.format(LocalDateTime.now()) + ".log");
            BufferedWriter writer = Files.newBufferedWriter(file);
            return(new PrintWriter(writer, true));
        } catch(IOException e) {
            return(null);
        }
    }

    private void log(String message) {
        if(log != null)
            log.println(LOG_TIME.format(LocalDateTime.now()) + " " + message);
    }

    @Override
    public void destroy() {
        if(log != null) {
            log("controller destroyed");
            log.close();
        }
        if(instance == this)
            instance = null;
        super.destroy();
    }

    private enum TargetSupport {
        SUPPORTED,
        BEAR,
        UNSUPPORTED
    }

    private static final class ActionChoice {
        final int slot;
        final String resource;
        final boolean found;
        final boolean loading;

        ActionChoice(int slot, String resource, boolean found, boolean loading) {
            this.slot = slot;
            this.resource = resource;
            this.found = found;
            this.loading = loading;
        }
    }
}
