package thunder;

/**
 * Pure "what should I do" text for the fishing helper. Never clicks or walks.
 */
public final class FishingAdvice {
    public enum Mode { TROPHY, FOOD }

    public static final int FOOD_BITE_MIN = 50;

    public static final class Situation {
	public final Mode mode;
	public final String lock;
	public final FishingBiteList.Result list;
	public final FishingKit.Snapshot kit;
	public final boolean depleted;
	public final FishingTackle.Combo combo;

	public Situation(Mode mode, String lock, FishingBiteList.Result list,
			 FishingKit.Snapshot kit, boolean depleted, FishingTackle.Combo combo) {
	    this.mode = mode;
	    this.lock = lock;
	    this.list = list;
	    this.kit = kit;
	    this.depleted = depleted;
	    this.combo = combo;
	}
    }

    private FishingAdvice() {}

    public static FishingBiteList.Row pick(Mode mode, String lock, FishingBiteList.Result list) {
	if(list == null || !list.parsed) {return null;}
	if(mode == Mode.TROPHY) {return list.find(lock);}
	FishingBiteList.Row best = null;
	for(FishingBiteList.Row r : list.rows) {
	    if(r.landPct != 100) {continue;}
	    if(best == null || r.bitePct > best.bitePct) {best = r;}
	}
	if(best != null) {return best;}
	for(FishingBiteList.Row r : list.rows) {
	    if(best == null || r.bitePct > best.bitePct) {best = r;}
	}
	return best;
    }

    public static String advise(Situation s) {
	if(s.kit != null && s.kit.emptyKit()) {
	    String miss = s.kit.missing();
	    return "empty kit — " + (miss == null ? "line/hook/lure missing" : miss + " missing");
	}
	if(s.list == null || !s.list.parsed) {
	    return "bait list not parsed (fail-closed)";
	}
	if(s.mode == Mode.TROPHY) {
	    if(s.lock == null || s.lock.trim().isEmpty()) {return "lock a species";}
	    FishingBiteList.Row r = s.list.find(s.lock);
	    if(r == null) {return "not on this list / wrong water or Will×Surv";}
	    if(r.landPct != 100) {return swapTackle(s.combo);}
	    if(s.depleted) {return "depleted — wait or move";}
	    if(r.bitePct < FOOD_BITE_MIN) {return "walk the node";}
	    return String.format("ok — pick %s  bite %d%%  land %d%%", r.name, r.bitePct, r.landPct);
	}
	FishingBiteList.Row food = pick(Mode.FOOD, s.lock, s.list);
	if(food == null) {return "empty bite list";}
	if(food.landPct != 100) {return swapTackle(s.combo != null ? s.combo : FishingTackle.suggest(food.name));}
	if(s.depleted) {return "depleted — wait or move";}
	if(food.bitePct < FOOD_BITE_MIN) {return "walk the node";}
	return String.format("food pick: %s  bite %d%%  land %d%%", food.name, food.bitePct, food.landPct);
    }

    private static String swapTackle(FishingTackle.Combo combo) {
	if(combo == null) {return "swap tackle";}
	return "swap tackle — " + combo.hint();
    }
}
