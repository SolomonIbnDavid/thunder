package auto;

import haven.Coord;
import haven.Defer;
import haven.GItem;
import haven.GameUI;
import haven.Inventory;
import haven.ItemInfo;
import haven.Loading;
import haven.WItem;
import haven.Widget;
import haven.WindowX;
import me.ender.WindowDetector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Merge stacks in inventory windows, Hurricane-style: pick the two smallest
 * quality-compatible piles of each name, take the smaller onto the next with
 * shift+ctrl itemact, drop leftovers, and repeat until no merge can progress.
 */
public class StackAllItems implements Defer.Callable<Void> {
    private static final int MAX_PASSES = 512;
    private static final Object lock = new Object();
    private static StackAllItems current;
    private Defer.Future<Void> task;

    private final List<Inventory> inventories;
    private final boolean rebuildStacks;
    private boolean completed = true;

    private StackAllItems(List<Inventory> inventories, boolean rebuildStacks) {
	this.inventories = inventories;
	this.rebuildStacks = rebuildStacks;
    }

    public static void stack(Inventory inv) {
	stack(inv, true);
    }

    public static void stack(Inventory inv, boolean organize) {
	if(inv == null || inv.ui == null || inv.ui.gui == null)
	    return;
	if(InventorySorter.invalidCursor(inv.ui))
	    return;
	start(new StackAllItems(Collections.singletonList(inv), organize), inv.ui.gui,
	      organize ? () -> InventorySorter.sort(inv) : null);
    }

    public static void stackOpened(GameUI gui) {
	if(InventorySorter.invalidCursor(gui.ui))
	    return;
	List<Inventory> targets = new ArrayList<>();
	for(haven.ExtInventory w : gui.ui.root.children(haven.ExtInventory.class)) {
	    if(w == null || w.inv == null)
		continue;
	    WindowX window = w.getparent(WindowX.class);
	    if(window == null || WindowDetector.isWindowType(window, InventorySorter.EXCLUDE))
		continue;
	    targets.add(w.inv);
	}
	if(!targets.isEmpty())
	    start(new StackAllItems(targets, true), gui, () -> InventorySorter.sortAll(gui));
    }

    @Override
    public Void call() throws InterruptedException {
	try {
	    for(Inventory inv : inventories) {
		if(inv.disposed())
		    continue;
		boolean ok = rebuildStacks ? rebuild(inv) : stackInv(inv, Collections.emptySet());
		if(!ok) {
		    completed = false;
		    break;
		}
	    }
	} finally {
	    synchronized(lock) {
		if(current == this)
		    current = null;
	    }
	}
	return null;
    }

    private boolean rebuild(Inventory inv) throws InterruptedException {
	Set<Integer> originalStacks = stackIdsNeedingRebuild(inv);
	for(int round = 0; round < 80 && !originalStacks.isEmpty(); round++) {
	    int pendingBefore = originalStacks.size();
	    boolean unpacked = UnstackAllItems.unstackInv(inv, originalStacks);
	    pruneUnpacked(inv, originalStacks);
	    if(!stackInv(inv, originalStacks))
		return false;
	    pruneUnpacked(inv, originalStacks);
	    if(!unpacked && originalStacks.size() == pendingBefore)
		break;
	}
	return stackInv(inv, Collections.emptySet());
    }

    private static Set<Integer> allStackIds(Inventory inv) {
	Set<Integer> ids = new HashSet<>();
	for(Widget wdg = inv.lchild; wdg != null; wdg = wdg.prev) {
	    if(wdg.visible && wdg instanceof WItem) {
		WItem w = (WItem) wdg;
		if(ItemStacking.isStackName(itemName(w)))
		    ids.add(w.item.wdgid());
	    }
	}
	return ids;
    }

    private static Set<Integer> stackIdsNeedingRebuild(Inventory inv) {
	Map<String, List<WItem>> groups = new LinkedHashMap<>();
	for(Widget wdg = inv.lchild; wdg != null; wdg = wdg.prev) {
	    if(!wdg.visible || !(wdg instanceof WItem))
		continue;
	    WItem w = (WItem) wdg;
	    String name = itemName(w);
	    if(!ItemStacking.isStackName(name))
		continue;
	    String key = ItemStacking.stackKey(name);
	    if(key != null)
		groups.computeIfAbsent(key, k -> new ArrayList<>()).add(w);
	}

	Set<Integer> ids = new HashSet<>();
	for(List<WItem> stacks : groups.values()) {
	    if(stacks.size() < 2)
		continue;
	    double[] mins = new double[stacks.size()];
	    double[] maxs = new double[stacks.size()];
	    for(int i = 0; i < stacks.size(); i++) {
		double[] range = qualityRange(stacks.get(i));
		mins[i] = range[0];
		maxs[i] = range[1];
	    }
	    boolean[] rebuild = ItemStacking.rangesNeedingRebuild(mins, maxs);
	    for(int i = 0; i < rebuild.length; i++) {
		if(rebuild[i])
		    ids.add(stacks.get(i).item.wdgid());
	    }
	}
	return ids;
    }

    private static void pruneUnpacked(Inventory inv, Set<Integer> ids) {
	Set<Integer> remaining = allStackIds(inv);
	for(Iterator<Integer> it = ids.iterator(); it.hasNext();) {
	    if(!remaining.contains(it.next()))
		it.remove();
	}
    }

    private boolean stackInv(Inventory inv, Set<Integer> excludedIds) throws InterruptedException {
	GameUI gui = inv.ui.gui;
	if(gui == null)
	    return false;
	if(gui.vhand != null) {
	    gui.error("Can't stack items with an occupied cursor!");
	    return false;
	}
	Set<String> stuck = new HashSet<>();
	Set<String> rejectedGroups = new HashSet<>();
	Set<Integer> fullStacks = new HashSet<>();
	for(int pass = 0; pass < MAX_PASSES; pass++) {
	    if(inv.disposed() || Thread.currentThread().isInterrupted())
		return true;
	    int result = mergeOnePass(gui, inv, stuck, rejectedGroups, fullStacks, excludedIds);
	    if(result < 0)
		return false;
	    if(result == 0)
		return true;
	}
	gui.error("Stack items stopped at its safety limit.");
	return false;
    }

    private static int mergeOnePass(GameUI gui, Inventory inv, Set<String> stuck,
				    Set<String> rejectedGroups, Set<Integer> fullStacks,
				    Set<Integer> excludedIds)
				    throws InterruptedException {
	Map<String, List<WItem>> groups = new LinkedHashMap<>();
	for(Widget wdg = inv.lchild; wdg != null; wdg = wdg.prev) {
	    if(!wdg.visible || !(wdg instanceof WItem))
		continue;
	    WItem w = (WItem) wdg;
	    if(excludedIds.contains(w.item.wdgid()) || fullStacks.contains(w.item.wdgid()))
		continue;
	    String name = itemName(w);
	    String key = ItemStacking.stackKey(name);
	    if(key == null || !ItemStacking.mayStack(name, w.item.resname(), w.lsz.x, w.lsz.y))
		continue;
	    groups.computeIfAbsent(key, k -> new ArrayList<>()).add(w);
	}
	boolean changed = false;
	for(Map.Entry<String, List<WItem>> entry : groups.entrySet()) {
	    String key = entry.getKey();
	    List<WItem> similar = entry.getValue();
	    if(similar.size() < 2 || rejectedGroups.contains(key))
		continue;
	    boolean knownStackable = false;
	    for(WItem w : similar)
		knownStackable |= ItemStacking.isStackName(itemName(w));
	    while(true) {
		int[] amounts = new int[similar.size()];
		double[] mins = new double[similar.size()];
		double[] maxs = new double[similar.size()];
		boolean[][] blocked = new boolean[similar.size()][similar.size()];
		for(int i = 0; i < similar.size(); i++) {
		    WItem w = similar.get(i);
		    amounts[i] = amount(w);
		    double[] range = qualityRange(w);
		    mins[i] = range[0];
		    maxs[i] = range[1];
		    for(int j = i + 1; j < similar.size(); j++)
			blocked[i][j] = fullStacks.contains(w.item.wdgid()) ||
			    fullStacks.contains(similar.get(j).item.wdgid()) ||
			    stuck.contains(pairKey(w, similar.get(j)));
		}
		int[] pick = ItemStacking.closestQualityPair(mins, maxs, amounts, blocked);
		if(pick == null)
		    break;
		WItem source = similar.get(pick[0]);
		WItem destination = similar.get(pick[1]);
		if(source.disposed() || destination.disposed())
		    break;
		String pair = pairKey(source, destination);
		int result = merge(gui, inv, key, source, destination);
		if(result < 0)
		    return -1;
		if(result > 0) {
		    changed = true;
		    break;
		}
		stuck.add(pair);
		if(!knownStackable) {
		    rejectedGroups.add(key);
		    break;
		}
		fullStacks.add(destination.item.wdgid());
		if(ItemStacking.failedSourceIsAlsoFull(amounts[pick[0]], amounts[pick[1]])) {
		    fullStacks.add(source.item.wdgid());
		    if(ItemStacking.isStackName(itemName(source)) &&
		       ItemStacking.isStackName(itemName(destination))) {
			int learnedCapacity = amounts[pick[1]];
			for(WItem stack : similar) {
			    if(ItemStacking.isStackName(itemName(stack)) &&
			       amount(stack) >= learnedCapacity)
				fullStacks.add(stack.item.wdgid());
			}
		    }
		}
	    }
	}
	return changed ? 1 : 0;
    }

    private static int merge(GameUI gui, Inventory inv, String key, WItem source,
			     WItem destination) throws InterruptedException {
	String before = groupState(inv, key);
	Coord dropSlot = source.c.sub(1, 1).div(Inventory.sqsz);
	source.take();
	if(!waitUntil(() -> gui.vhand != null, 40, 25)) {
	    gui.error("Stack items: could not pick up an item.");
	    return -1;
	}
	destination.itemact(3);
	waitUntil(() -> gui.vhand == null || !before.equals(groupState(inv, key)), 40, 25);
	if(gui.vhand != null) {
	    inv.wdgmsg("drop", dropSlot);
	    if(!waitUntil(() -> gui.vhand == null, 40, 25)) {
		gui.error("Stack items: could not return the leftover item.");
		return -1;
	    }
	}
	boolean progressed = waitUntil(() -> !before.equals(groupState(inv, key)), 20, 25);
	return progressed ? 1 : 0;
    }

    private static String pairKey(WItem a, WItem b) {
	int ia = a.item.wdgid();
	int ib = b.item.wdgid();
	if(ia > ib) {
	    int t = ia;
	    ia = ib;
	    ib = t;
	}
	return ia + ":" + ib;
    }

    private static String groupState(Inventory inv, String wantedKey) {
	List<String> values = new ArrayList<>();
	for(Widget wdg = inv.lchild; wdg != null; wdg = wdg.prev) {
	    if(!(wdg instanceof WItem))
		continue;
	    WItem w = (WItem) wdg;
	    String name = itemName(w);
	    String key = ItemStacking.stackKey(name);
	    if(!wantedKey.equals(key))
		continue;
	    values.add(amount(w) + (ItemStacking.isStackName(name) ? "s" : "i"));
	}
	Collections.sort(values);
	return values.toString();
    }

    private static double[] qualityRange(WItem w) {
	double min = Double.POSITIVE_INFINITY;
	double max = Double.NEGATIVE_INFINITY;
	if(w.item.contents != null) {
	    for(WItem child : w.item.contents.children(WItem.class)) {
		double q = quality(child);
		if(Double.isFinite(q)) {
		    min = Math.min(min, q);
		    max = Math.max(max, q);
		}
	    }
	}
	if(min == Double.POSITIVE_INFINITY) {
	    double q = quality(w);
	    if(Double.isFinite(q))
		return new double[] {q, q};
	    return new double[] {Double.NaN, Double.NaN};
	}
	return new double[] {min, max};
    }

    private static double quality(WItem w) {
	try {
	    double q = w.quality();
	    return q > 0 ? q : Double.NaN;
	} catch(Loading ignored) {
	    return Double.NaN;
	}
    }

    static String itemName(WItem w) {
	try {
	    return w.item.name.get("");
	} catch(Loading ignored) {
	    return "";
	}
    }

    static int amount(WItem w) {
	try {
	    for(ItemInfo info : w.item.info()) {
		if(info instanceof GItem.Amount)
		    return Math.max(1, ((GItem.Amount) info).itemnum());
	    }
	} catch(Loading ignored) {}
	Float q = w.item.quantity.get(1f);
	if(q != null && q > 1)
	    return q.intValue();
	if(w.item.contents != null) {
	    int n = 0;
	    for(WItem ignored : w.item.contents.children(WItem.class))
		n++;
	    if(n > 0)
		return n;
	}
	return 1;
    }

    private static boolean waitUntil(BooleanSupplier cond, int tries, int sleepMs) throws InterruptedException {
	for(int i = 0; i < tries; i++) {
	    if(cond.getAsBoolean())
		return true;
	    Thread.sleep(sleepMs);
	}
	return cond.getAsBoolean();
    }

    private void run(java.util.function.Consumer<String> callback) {
	task = Defer.later(this);
	task.callback(() -> callback.accept(task.cancelled() ? "cancelled" : "complete"));
    }

    public static void cancel() {
	synchronized(lock) {
	    if(current != null && current.task != null) {
		current.task.cancel();
		current = null;
	    }
	}
    }

    private static void start(StackAllItems job, GameUI gui, Runnable afterComplete) {
	UnstackAllItems.cancel();
	cancel();
	synchronized(lock) {current = job;}
	job.run((result) -> {
	    if("complete".equals(result) && job.completed) {
		if(afterComplete != null)
		    afterComplete.run();
	    } else if(!"complete".equals(result)) {
		gui.ui.message(String.format("Stack is %s.", result), GameUI.MsgType.INFO);
	    }
	});
    }
}
