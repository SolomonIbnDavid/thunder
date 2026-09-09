package auto;

import haven.*;

import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class GobHelper {
    static List<ITarget> getNearest(GameUI gui, String name, int limit, double distance) {
	return getGobs(gui, limit, PositionHelper.byDistanceToPlayer, gobIs(name), gob -> PositionHelper.distanceToPlayer(gob) <= distance);
    }
    
    static List<ITarget> getNearest(GameUI gui, int limit, double distance, GobTag... tags) {
	return getGobs(gui, limit, PositionHelper.byDistanceToPlayer, gobIsAny(tags), gob -> PositionHelper.distanceToPlayer(gob) <= distance);
    }
    
    @SafeVarargs
    static List<ITarget> getNearest(GameUI gui, int limit, double distance, Predicate<Gob>... filters) {
	Predicate<Gob> distFilter = gob -> PositionHelper.distanceToPlayer(gob) <= distance;
	if(filters == null || filters.length == 0) {
	    filters = new Predicate[]{distFilter};
	} else {
	    filters = Utils.extend(filters, filters.length + 1);
	    filters[filters.length - 1] = distFilter;
	}
	return getGobs(gui, limit, PositionHelper.byDistanceToPlayer, filters);
    }
    
    @SafeVarargs
    static List<ITarget> getNearestToPoint(GameUI gui, int limit, Coord2d pos, double distance, Predicate<Gob>... filters) {
	Predicate<Gob> distFilter = g -> PositionHelper.distanceToCoord(pos, g) <= distance;
	if(filters == null || filters.length == 0) {
	    filters = new Predicate[]{distFilter};
	} else {
	    filters = Utils.extend(filters, filters.length + 1);
	    filters[filters.length - 1] = distFilter;
	}
	return getGobs(gui, limit, Comparator.comparingDouble(g -> PositionHelper.distanceToCoord(pos, g)), filters);
    }
    
    private static List<ITarget> getNearest(GameUI gui, int limit, Function<Gob, Double> meter, double distance, GobTag... tags) {
	return getGobs(gui, limit, Comparator.comparingDouble(meter::apply), gobIsAny(tags), gob -> meter.apply(gob) <= distance);
    }
    
    @SafeVarargs
    static List<ITarget> getGobs(GameUI gui, int limit, Comparator<Gob> sort, Predicate<Gob>... filters) {
	Stream<Gob> stream = gui.ui.sess.glob.oc.stream();
	for (Predicate<Gob> filter : filters) {
	    stream = stream.filter(filter);
	}
	return stream
	    .sorted(sort)
	    .limit(limit)
	    .map(GobTarget::new)
	    .collect(Collectors.toList());
    }
    
    static Bot.BotAction waitGobNoPose(Gob gob, long timeout, String... poses) {
	return (t, b) -> {
	    if(gob == null) {return;}
	    final long started = System.currentTimeMillis();
	    while (System.currentTimeMillis() - started < timeout && !gob.disposed() && gob.hasPose(poses)) {
		BotUtil.pause(100);
	    }
	};
    }
    
    static Bot.BotAction waitGobPose(Gob gob, long timeout, String... poses) {
	return (t, b) -> {
	    if(gob == null) {return;}
	    final long started = System.currentTimeMillis();
	    while (System.currentTimeMillis() - started < timeout && !gob.disposed() && !gob.hasPose(poses)) {
		BotUtil.pause(100);
	    }
	};
    }
    
    public static boolean isNotFriendlySteed(Gob gob) {
	return gob.occupants.stream().noneMatch(g -> g.anyOf(GobTag.ME, GobTag.PARTY));
    }

    /** Mirrors the attack-cursor guard in MapView.Click: bot aggro bypasses that path, so it needs its own check. */
    public static boolean isNotBlockedTamedHorse(Gob gob) {
	return !(CFG.BLOCK_ATTACK_TAMED_HORSE.get() && gob.is(GobTag.HORSE) && gob.is(GobTag.DOMESTIC));
    }
    
    private static Predicate<Gob> gobIs(String what) {
	return g -> {
	    if(g == null) {return false;}
	    String id = g.resid();
	    if(id == null) {return false;}
	    return id.contains(what);
	};
    }
    
    public static Predicate<Gob> gobIs(GobTag what) {
	return g -> {
	    if(g == null) {return false;}
	    return g.is(what);
	};
    }
    
    public static Predicate<Gob> gobIsNot(GobTag what) {
	return g -> {
	    if(g == null) {return false;}
	    return !g.is(what);
	};
    }
    
    public static Predicate<Gob> gobIsAny(GobTag... what) {
	return g -> {
	    if(g == null) {return false;}
	    return g.anyOf(what);
	};
    }
    
    static Predicate<Gob> resIdStartsWith(String text) {
	return gob -> {
	    try {
		return gob.resid().startsWith(text);
	    } catch (Exception ignored) {}
	    return false;
	};
    }
}
