package thunder;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

public class FishingKitTest {
    @Test
    void classifyParts() {
	assertEquals(FishingKit.Kind.POLE, FishingKit.classify("Primitive Casting-Rod"));
	assertEquals(FishingKit.Kind.POLE, FishingKit.classify("Bushcraft Fishingpole"));
	assertEquals(FishingKit.Kind.LINE, FishingKit.classify("Farmer's Fishline"));
	assertEquals(FishingKit.Kind.HOOK, FishingKit.classify("Metal Hook"));
	assertEquals(FishingKit.Kind.LURE, FishingKit.classify("Copper Comet"));
	assertEquals(FishingKit.Kind.BAIT, FishingKit.classify("Earthworm"));
	assertEquals(FishingKit.Kind.OTHER, FishingKit.classify("Pike"));
    }

    @Test
    void readyAndEmptyKit() {
	FishingKit.Snapshot ready = FishingKit.of("Primitive Casting-Rod", 40,
						  "Farmer's Fishline", 30,
						  "Bone Hook", 20,
						  "Woodfish", 10);
	assertTrue(ready.ready());
	assertFalse(ready.emptyKit());
	assertTrue(ready.castingRod());

	FishingKit.Snapshot empty = FishingKit.of("Primitive Casting-Rod", 40,
						  "Farmer's Fishline", 30,
						  "Bone Hook", 20,
						  null, 0);
	assertTrue(empty.emptyKit());
	assertEquals("lure", empty.missing());
    }

    @Test
    void gearMeanEvenWeights() {
	FishingKit.Snapshot s = FishingKit.of("Primitive Casting-Rod", 16,
					      "Farmer's Fishline", 16,
					      "Bone Hook", 16,
					      "Woodfish", 16);
	assertEquals(16.0, s.gearMean(), 1e-9);
    }

    @Test
    void fromPartsAssignsFirstOfEachKind() {
	FishingKit.Snapshot s = FishingKit.fromParts(
	    Arrays.asList("Primitive Casting-Rod", "Tanner's Fishline", "Metal Hook", "Rock Lobster"),
	    Arrays.asList(10.0, 20.0, 30.0, 40.0));
	assertEquals("Tanner's Fishline", s.line);
	assertEquals(20.0, s.lineQ);
	assertEquals("Rock Lobster", s.lure);
    }
}
