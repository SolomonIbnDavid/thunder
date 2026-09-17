package auto;

/** Pure helpers for Hurricane-style inventory auto-stack / unstack. */
public final class ItemStacking {
    public static final String STACK_SUFFIX = ", stack of";

    private ItemStacking() {}

    /**
     * Grouping key for auto-stack. Returns null when the item should be skipped
     * (unloaded name, rings, or quantity liquids/powders whose names contain a
     * decimal, matching Hurricane).
     */
    public static String stackKey(String name) {
	if(name == null || name.isEmpty() || "???".equals(name))
	    return null;
	if(name.contains("Ring"))
	    return null;
	if(name.indexOf('.') >= 0)
	    return null;
	if(name.endsWith(STACK_SUFFIX))
	    name = name.substring(0, name.length() - STACK_SUFFIX.length());
	name = name.trim();
	return name.isEmpty() ? null : name;
    }

    public static boolean isStackName(String name) {
	return name != null && name.contains("stack of");
    }

    /**
     * The server does not expose a general "stackable" flag for loose items.
     * Restrict probing to the one-slot items stacks can contain and exclude
     * the liquid containers that are commonly stored in duplicate.
     */
    public static boolean mayStack(String name, String resname, int slotsWide, int slotsHigh) {
	if(stackKey(name) == null || slotsWide != 1 || slotsHigh != 1)
	    return false;
	if(resname == null)
	    return true;
	return !(resname.endsWith("/waterskin") ||
		 resname.endsWith("/waterflask") ||
		 resname.contains("/glassjug") ||
		 resname.contains("/kuksa") ||
		 resname.contains("/bucket"));
    }

    /**
     * Pick the pair whose combined quality range is smallest. The returned
     * indices are ordered source, destination; the smaller pile is held so a
     * full pile is not selected as the source when a partial pile is present.
     * Unknown quality is represented by NaN and falls back to pile size.
     */
    public static int[] closestQualityPair(double[] mins, double[] maxs, int[] amounts) {
	return closestQualityPair(mins, maxs, amounts, null);
    }

    static int[] closestQualityPair(double[] mins, double[] maxs, int[] amounts, boolean[][] blocked) {
	if(mins == null || maxs == null || amounts == null || mins.length < 2 ||
	   mins.length != maxs.length || mins.length != amounts.length)
	    return null;
	int bestA = -1, bestB = -1;
	double bestSpan = Double.POSITIVE_INFINITY;
	int bestAmount = Integer.MAX_VALUE;
	for(int i = 0; i < mins.length; i++) {
	    for(int j = i + 1; j < mins.length; j++) {
		if(blocked != null && blocked[i][j])
		    continue;
		double span = qualitySpan(mins[i], maxs[i], mins[j], maxs[j]);
		int amount = amounts[i] + amounts[j];
		if(bestA < 0 || Double.compare(span, bestSpan) < 0 ||
		   (Double.compare(span, bestSpan) == 0 && amount < bestAmount)) {
		    bestA = i;
		    bestB = j;
		    bestSpan = span;
		    bestAmount = amount;
		}
	    }
	}
	if(bestA < 0)
	    return null;
	if(amounts[bestB] < amounts[bestA]) {
	    int t = bestA;
	    bestA = bestB;
	    bestB = t;
	}
	return new int[] {bestA, bestB};
    }

    private static double qualitySpan(double minA, double maxA, double minB, double maxB) {
	if(!Double.isFinite(minA) || !Double.isFinite(maxA) ||
	   !Double.isFinite(minB) || !Double.isFinite(maxB))
	    return Double.POSITIVE_INFINITY;
	return Math.max(maxA, maxB) - Math.min(minA, minB);
    }

    /**
     * Indices of the two smallest amounts in {@code amounts}. Null if fewer
     * than two entries. Ties keep earlier indices, matching a stable
     * smallest-then-next-smallest pick.
     */
    public static int[] twoSmallest(int[] amounts) {
	if(amounts == null || amounts.length < 2)
	    return null;
	int a = 0, b = 1;
	if(amounts[b] < amounts[a]) {
	    a = 1;
	    b = 0;
	}
	for(int i = 2; i < amounts.length; i++) {
	    if(amounts[i] < amounts[a]) {
		b = a;
		a = i;
	    } else if(amounts[i] < amounts[b]) {
		b = i;
	    }
	}
	return new int[] {a, b};
    }

    /**
     * True when a take+itemact actually stacked something: the taken pile
     * vanished, or the target's count went up. False means a full or
     * incompatible stack — retrying that pair just pick-up/drop-loops.
     */
    public static boolean stacked(boolean sourceGone, int destBefore, int destAfter) {
	return sourceGone || destAfter > destBefore;
    }
}
