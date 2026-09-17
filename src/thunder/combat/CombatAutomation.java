package thunder.combat;

import haven.Buff;
import haven.Bufflist;
import haven.Debug;
import haven.Fightsess;
import haven.Fightview;
import haven.GameUI;
import haven.Gob;
import haven.GobDamageInfo;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** UI-thread controller for user-authored combat plans. */
public final class CombatAutomation extends Widget {
    private static final double ACK_TIMEOUT = 3.0;
    private static final double ACK_RETRY_DELAY = 0.75;
    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private static CombatAutomation instance;
    private static String lastStatus = "Disabled";

    private final GameUI gui;
    private final CombatPlanStore plans = CombatPlanStore.get();
    private final CombatEngagementDamage damage = new CombatEngagementDamage();
    private final PrintWriter log;
    private long targetId = -1;
    private double pendingSince;
    private double pendingLastUse;
    private String pendingAction;
    private String pendingName;
    private boolean pendingDistanceCapable;
    private int consecutiveAckTimeouts;
    private double retryNotBefore;
    private String lastLoggedState;
    private String lastDeck;
    private String lastVisibleError;

    private CombatAutomation(GameUI gui) {
        this.gui = gui;
        this.log = openLog();
        setStatus("Enabled; waiting for combat");
        log("enabled character=" + characterId() + " queue-lead-ms=" +
            Math.round(CombatAutomationRules.ACTION_QUEUE_LEAD * 1000) +
            " melee-safety-distance=" + CombatAutomationRules.MAX_ACTION_DISTANCE);
        if(plans.loadError() != null)
            log("planner load warning=" + plans.loadError());
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
            gui.msg("Combat Automation enabled.", GameUI.MsgType.GOOD);
        }
    }

    public static boolean enabled() {
        return(instance != null);
    }

    public static String status() {
        return(lastStatus);
    }

    public static String currentTargetResource(GameUI gui) {
        if(gui == null || gui.fv == null || gui.fv.current == null || gui.ui == null)
            return(null);
        Gob target = gui.ui.sess.glob.oc.getgob(gui.fv.current.gobid);
        if(target == null)
            return(null);
        try {
            return(target.resid());
        } catch(Loading loading) {
            return(null);
        }
    }

    /** Localized live-deck data used by the planner's move picker. */
    public static final class MoveInfo {
        public final String resource;
        public final String name;
        public final String description;
        public final boolean equipped;

        MoveInfo(String resource, String name, String description, boolean equipped) {
            this.resource = resource;
            this.name = name;
            this.description = description;
            this.equipped = equipped;
        }
    }

    public static Map<String, MoveInfo> currentDeck(GameUI gui) {
        if(gui == null || gui.fsess == null)
            return(Collections.emptyMap());
        Map<String, MoveInfo> result = new LinkedHashMap<>();
        for(Fightsess.Action action : gui.fsess.actions) {
            if(action == null)
                continue;
            try {
                Resource resource = action.res.get();
                Resource.Pagina pagina = resource.layer(Resource.pagina);
                result.put(resource.name, new MoveInfo(resource.name, actionName(resource),
                    pagina == null ? "" : pagina.text, true));
            } catch(Loading loading) {
                // The editor will refresh on the next UI tick.
            }
        }
        return(result);
    }

    @Override
    public void tick(double dt) {
        super.tick(dt);
        if(instance != this)
            return;

        Fightview fv = gui.fv;
        Fightsess fsess = gui.fsess;
        updateEngagementDamage(fv);
        double now = Utils.rtime();
        if(handlePending(fv, fsess, now))
            return;

        if(fv == null || fsess == null || fv.current == null) {
            targetId = -1;
            consecutiveAckTimeouts = 0;
            retryNotBefore = 0;
            lastVisibleError = null;
            updateState("Enabled; waiting for combat");
            return;
        }

        Fightview.Relation relation = fv.current;
        Gob target = gui.ui.sess.glob.oc.getgob(relation.gobid);
        if(target == null || relation.invalid) {
            updateState("Waiting for current target data");
            return;
        }
        String targetResource;
        try {
            targetResource = target.resid();
        } catch(Loading loading) {
            updateState("Waiting for target resource data");
            return;
        }
        if(targetResource == null)
            targetResource = "unknown/" + target.id;
        if(targetId != target.id) {
            targetId = target.id;
            consecutiveAckTimeouts = 0;
            retryNotBefore = 0;
            lastVisibleError = null;
            log("target id=" + target.id + " resource=" + targetResource);
            plans.observeTarget(characterId(), targetResource, CombatTargetCatalog.labelFor(targetResource));
        }
        if(now < retryNotBefore) {
            updateState("Waiting to retry " + (pendingName == null ? "action" : pendingName));
            return;
        }

        CombatPlanEvaluator.State observed = observeState(fv, relation);
        if(observed == null) {
            updateState("Waiting for opening data");
            return;
        }
        GobDamageInfo.DamageSnapshot dealt = damage.snapshot(relation.gobid);
        observed.armorDamage = dealt.armor;
        observed.shpDamage = dealt.shp;
        observed.hhpDamage = dealt.hhp;

        DeckSnapshot deck = inspectDeck(fsess);
        if(deck.loading) {
            updateState("Waiting for combat deck data");
            return;
        }
        if(!deck.description.equals(lastDeck)) {
            lastDeck = deck.description;
            log("deck " + (lastDeck.isEmpty() ? "empty" : lastDeck));
        }

        CombatPlan.CharacterPlans characterPlans = plans.character(characterId());
        CombatPlan.Resolution resolved = CombatPlan.resolve(characterPlans, targetResource);
        if(resolved.profile == null || resolved.strategy == null) {
            pause("No active combat strategy is available.");
            return;
        }
        CombatPlanEvaluator.Result result = CombatPlanEvaluator.evaluate(
            resolved.strategy, observed, deck.moves, now, fv.atkct);
        if(result.outcome == CombatPlanEvaluator.Outcome.NO_MATCH) {
            lastVisibleError = null;
            updateState("Waiting; no rule matches in " + resolved.strategy.name);
            return;
        }
        if(result.outcome == CombatPlanEvaluator.Outcome.MISSING_MOVE) {
            pause(result.detail + "; equip it to resume.");
            return;
        }
        if(result.outcome == CombatPlanEvaluator.Outcome.WAIT) {
            lastVisibleError = null;
            updateState("Waiting for " + (result.move == null ? "action" : result.move.name) + " cooldown");
            return;
        }

        CombatPlanEvaluator.DeckMove move = result.move;
        boolean distanceCapable = CombatMoveCatalog.distanceCapable(move.resource);
        double distance = targetDistance(target);
        if(!Double.isFinite(distance)) {
            updateState("Waiting for player and target position data");
            return;
        }
        if(!CombatAutomationRules.canAttemptAction(distance, distanceCapable)) {
            updateState(String.format("Approaching target; distance %.1f (need %.1f or less for %s)",
                distance, CombatAutomationRules.MAX_ACTION_DISTANCE, move.name));
            return;
        }

        lastVisibleError = null;
        String stateText = formatState(observed);
        updateState(move.name + "; " + stateText);
        pendingSince = now;
        pendingLastUse = fv.lastuse;
        pendingAction = move.resource;
        pendingName = move.name;
        pendingDistanceCapable = distanceCapable;
        long queueLead = Math.round(Math.max(0,
            Math.max(fv.atkct, move.cooldownEnd) - now) * 1000);
        log("send target-resource=" + targetResource +
            " target-profile=" + resolved.profile.displayName +
            " fallback=" + resolved.fallback +
            " strategy=" + resolved.strategy.name +
            " rule=" + result.rule.name +
            " observed={" + stateText + "}" +
            " slot=" + move.slot + " action=" + move.resource + " name=" + move.name +
            String.format(" distance=%.1f distance-capable=%s queue-lead-ms=%d",
                distance, distanceCapable, queueLead));
        if(!fsess.triggerAction(move.slot, target.rc)) {
            clearPending();
            disableWithError("Combat action slot became unavailable.");
        }
    }

    private boolean handlePending(Fightview fv, Fightsess fsess, double now) {
        if(pendingAction == null)
            return(false);
        if(fv == null || fsess == null || fv.current == null) {
            log("combat ended while awaiting acknowledgement action=" + pendingAction);
            clearPending();
            targetId = -1;
            consecutiveAckTimeouts = 0;
            retryNotBefore = 0;
            updateState("Enabled; waiting for combat");
            return(true);
        }
        if(fv.lastuse > pendingLastUse) {
            log("ack action=" + pendingAction);
            clearPending();
            consecutiveAckTimeouts = 0;
            retryNotBefore = 0;
            return(false);
        }
        if(now - pendingSince < ACK_TIMEOUT)
            return(true);

        String timedOutAction = pendingAction;
        String timedOutName = pendingName;
        boolean distanceCapable = pendingDistanceCapable;
        double distance = currentTargetDistance(fv);
        int timeout = ++consecutiveAckTimeouts;
        clearPending();
        if(CombatAutomationRules.shouldRetryMissingAcknowledgement(timeout, distance, distanceCapable)) {
            retryNotBefore = now + ACK_RETRY_DELAY;
            log(String.format("ack timeout action=%s name=%s attempt=%d distance=%.1f retry",
                timedOutAction, timedOutName, timeout, distance));
            updateState(String.format("Waiting to retry %s; target distance %.1f", timedOutName, distance));
            return(true);
        }
        disableWithError("No server acknowledgement for " + timedOutName +
            " after " + timeout + " valid-range attempts.");
        return(true);
    }

    private void updateEngagementDamage(Fightview fv) {
        if(fv == null) {
            damage.clear();
            return;
        }
        List<Long> active = new ArrayList<>();
        for(Fightview.Relation relation : fv.lsrel) {
            if(relation != null && !relation.invalid)
                active.add(relation.gobid);
        }
        damage.update(active, GobDamageInfo::snapshot);
    }

    private CombatPlanEvaluator.State observeState(Fightview fv, Fightview.Relation relation) {
        Integer ownGreen = opening(fv.buffs, Buff.OPEN_GREEN);
        Integer ownYellow = opening(fv.buffs, Buff.OPEN_YELLOW);
        Integer ownRed = opening(fv.buffs, Buff.OPEN_RED);
        Integer ownBlue = opening(fv.buffs, Buff.OPEN_BLUE);
        Integer opponentGreen = opening(relation.buffs, Buff.OPEN_GREEN);
        Integer opponentYellow = opening(relation.buffs, Buff.OPEN_YELLOW);
        Integer opponentRed = opening(relation.buffs, Buff.OPEN_RED);
        Integer opponentBlue = opening(relation.buffs, Buff.OPEN_BLUE);
        if(ownGreen == null || ownYellow == null || ownRed == null || ownBlue == null ||
           opponentGreen == null || opponentYellow == null || opponentRed == null || opponentBlue == null)
            return(null);
        CombatPlanEvaluator.State state = new CombatPlanEvaluator.State();
        state.yourIp = relation.ip;
        state.opponentIp = relation.oip;
        state.yourGreen = ownGreen;
        state.yourYellow = ownYellow;
        state.yourRed = ownRed;
        state.yourBlue = ownBlue;
        state.opponentGreen = opponentGreen;
        state.opponentYellow = opponentYellow;
        state.opponentRed = opponentRed;
        state.opponentBlue = opponentBlue;
        return(state);
    }

    private static DeckSnapshot inspectDeck(Fightsess fsess) {
        DeckSnapshot result = new DeckSnapshot();
        StringBuilder description = new StringBuilder();
        for(int slot = 0; slot < fsess.actions.length; slot++) {
            Fightsess.Action action = fsess.actions[slot];
            if(action == null)
                continue;
            try {
                Resource resource = action.res.get();
                Resource.Pagina pagina = resource.layer(Resource.pagina);
                EnumSet<CombatAutomationRules.Opening> clears = CombatMoveMetadata.reducedOpenings(
                    pagina == null ? null : pagina.text);
                String name = actionName(resource);
                result.moves.add(new CombatPlanEvaluator.DeckMove(
                    resource.name, name, slot, action.ct, clears));
                if(description.length() > 0)
                    description.append("; ");
                description.append(slot).append(':').append(resource.name);
                if(!clears.isEmpty())
                    description.append(" clears=").append(clears);
            } catch(Loading loading) {
                result.loading = true;
            }
        }
        result.description = description.toString();
        return(result);
    }

    private double currentTargetDistance(Fightview fv) {
        if(fv == null || fv.current == null)
            return(Double.NaN);
        return(targetDistance(gui.ui.sess.glob.oc.getgob(fv.current.gobid)));
    }

    private double targetDistance(Gob target) {
        Gob player = gui.map == null ? null : gui.map.player();
        if(player == null || player.rc == null || target == null || target.rc == null)
            return(Double.NaN);
        return(player.rc.dist(target.rc));
    }

    private void clearPending() {
        pendingAction = null;
        pendingName = null;
        pendingDistanceCapable = false;
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

    private static String actionName(Resource resource) {
        Resource.Tooltip tooltip = resource.layer(Resource.tooltip);
        return(tooltip == null ? CombatMoveCatalog.label(resource.name) : tooltip.t);
    }

    private static String formatState(CombatPlanEvaluator.State state) {
        return("ip=" + state.yourIp + "/" + state.opponentIp +
            " own-gyrb=" + state.yourGreen + "/" + state.yourYellow + "/" +
            state.yourRed + "/" + state.yourBlue +
            " opponent-gyrb=" + state.opponentGreen + "/" + state.opponentYellow + "/" +
            state.opponentRed + "/" + state.opponentBlue +
            " damage-arm/shp/hhp/total=" + state.armorDamage + "/" + state.shpDamage + "/" +
            state.hhpDamage + "/" + state.totalDamage());
    }

    private String characterId() {
        return(gui.chrid);
    }

    private void pause(String reason) {
        updateState("Paused: " + reason);
        if(!reason.equals(lastVisibleError)) {
            lastVisibleError = reason;
            gui.error("Combat Automation paused: " + reason);
            log("paused reason=" + reason);
        }
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
        damage.clear();
        if(log != null) {
            log("controller destroyed");
            log.close();
        }
        if(instance == this)
            instance = null;
        super.destroy();
    }

    private static final class DeckSnapshot {
        final List<CombatPlanEvaluator.DeckMove> moves = new ArrayList<>();
        boolean loading;
        String description = "";
    }
}
