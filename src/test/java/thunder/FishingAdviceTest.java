package thunder;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class FishingAdviceTest {
    private static FishingBiteList.Result list(FishingBiteList.Row... rows) {
	List<FishingBiteList.Row> rs = new ArrayList<>();
	for(FishingBiteList.Row r : rows) {rs.add(r);}
	return FishingBiteList.Result.ok(rs);
    }

    private static FishingBiteList.Row row(String name, int bite, int land, boolean green) {
	return new FishingBiteList.Row(name, bite, land, bite * land / 100, green);
    }

    private static FishingKit.Snapshot readyKit() {
	return FishingKit.of("Primitive Casting-Rod", 40, "Farmer's Fishline", 30, "Bone Hook", 20, "Woodfish", 10);
    }

    private static FishingKit.Snapshot emptyKit() {
	return FishingKit.of("Primitive Casting-Rod", 40, "Farmer's Fishline", 30, "Bone Hook", 20, null, 0);
    }

    @Test
    void emptyKitScreamsFirst() {
	FishingAdvice.Situation s = new FishingAdvice.Situation(
	    FishingAdvice.Mode.TROPHY, "Pike",
	    list(row("Pike", 86, 100, false)), emptyKit(), false, FishingTackle.suggest("Pike"));
	assertTrue(FishingAdvice.advise(s).startsWith("empty kit"));
    }

    @Test
    void trophyIgnoresGreenRow() {
	FishingBiteList.Result l = list(row("Roach", 90, 100, true), row("Pike", 40, 100, false));
	assertEquals("Pike", FishingAdvice.pick(FishingAdvice.Mode.TROPHY, "Pike", l).name);
    }

    @Test
    void trophyMissingFromList() {
	FishingAdvice.Situation s = new FishingAdvice.Situation(
	    FishingAdvice.Mode.TROPHY, "Sturgeon",
	    list(row("Pike", 86, 100, false)), readyKit(), false, FishingTackle.suggest("Sturgeon"));
	assertEquals("not on this list / wrong water or Will×Surv", FishingAdvice.advise(s));
    }

    @Test
    void trophyLowLandSuggestsTackle() {
	FishingAdvice.Situation s = new FishingAdvice.Situation(
	    FishingAdvice.Mode.TROPHY, "Pike",
	    list(row("Pike", 86, 40, false)), readyKit(), false, FishingTackle.suggest("Pike"));
	String a = FishingAdvice.advise(s);
	assertTrue(a.startsWith("swap tackle"));
	assertTrue(a.contains("lobster"));
    }

    @Test
    void trophyWalkThenOk() {
	FishingAdvice.Situation walk = new FishingAdvice.Situation(
	    FishingAdvice.Mode.TROPHY, "Pike",
	    list(row("Pike", 20, 100, false)), readyKit(), false, null);
	assertEquals("walk the node", FishingAdvice.advise(walk));
	FishingAdvice.Situation ok = new FishingAdvice.Situation(
	    FishingAdvice.Mode.TROPHY, "Pike",
	    list(row("Pike", 86, 100, false)), readyKit(), false, null);
	assertTrue(FishingAdvice.advise(ok).startsWith("ok — pick Pike"));
    }

    @Test
    void depletedBeatsWalk() {
	FishingAdvice.Situation s = new FishingAdvice.Situation(
	    FishingAdvice.Mode.TROPHY, "Pike",
	    list(row("Pike", 20, 100, false)), readyKit(), true, null);
	assertEquals("depleted — wait or move", FishingAdvice.advise(s));
    }

    @Test
    void foodPicksHighestLand100() {
	FishingBiteList.Result l = list(
	    row("Roach", 90, 70, true),
	    row("Pike", 60, 100, false),
	    row("Asp", 80, 100, false));
	assertEquals("Asp", FishingAdvice.pick(FishingAdvice.Mode.FOOD, "Pike", l).name);
    }

    @Test
    void failClosedList() {
	FishingAdvice.Situation s = new FishingAdvice.Situation(
	    FishingAdvice.Mode.FOOD, "", FishingBiteList.Result.fail(), readyKit(), false, null);
	assertEquals("bait list not parsed (fail-closed)", FishingAdvice.advise(s));
    }
}
