package thunder;

import haven.Coord;
import haven.Coord2d;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class MiningHeatMapTest {
    @Test void followLatestCanBeLockedAndResumed() {
	MiningHeatMap heat = new MiningHeatMap();
	heat.noteObservation("stone/granite");
	assertEquals("stone/granite", heat.target());
	heat.select("stone/quarryquartz");
	assertFalse(heat.followLatest());
	assertEquals("stone/quarryartz", heat.target());
	heat.noteObservation("ore/black-ore");
	assertEquals("stone/quarryartz", heat.target());
	heat.setFollowLatest(true);
	heat.noteObservation("ore/black-ore");
	assertEquals("ore/black-ore", heat.target());
    }

    @Test void nonMiningObservationsCannotRetarget() {
	MiningHeatMap heat = new MiningHeatMap();
	heat.noteObservation(TileQuality.KEY_WATER);
	assertNull(heat.target());
    }

    @Test void localPaletteRunsBlueThroughYellowToGreen() {
	Color low = MiningHeatMap.localColor(10, 10, 30, 200);
	Color middle = MiningHeatMap.localColor(20, 10, 30, 200);
	Color high = MiningHeatMap.localColor(30, 10, 30, 200);
	assertTrue(low.getBlue() > low.getRed());
	assertTrue(middle.getRed() > 200 && middle.getGreen() > 200);
	assertTrue(high.getGreen() > high.getRed());
	assertEquals(200, high.getAlpha());
    }

    @Test void frontierRequiresLoadedClosedTileBesideFloor() {
	Set<Coord> loaded = new HashSet<>();
	loaded.add(Coord.of(0, 0));
	loaded.add(Coord.of(1, 0));
	Set<Coord> floors = Collections.singleton(Coord.of(0, 0));
	MiningHeatMap.TerrainProbe probe = new MiningHeatMap.TerrainProbe() {
	    public boolean loaded(Coord tile) {return loaded.contains(tile);}
	    public boolean minedFloor(Coord tile) {return floors.contains(tile);}
	};
	Coord2d next = MiningHeatMap.snapFrontier(Coord2d.of(16.5, 5.5), Coord2d.of(5.5, 5.5),
	    Collections.emptySet(), probe);
	assertNotNull(next);
	assertEquals(16.5, next.x, .001);
	assertEquals(5.5, next.y, .001);

	assertNull(MiningHeatMap.snapFrontier(Coord2d.of(16.5, 5.5), Coord2d.of(5.5, 5.5),
	    Collections.singleton(Coord.of(1, 0)), probe));
	loaded.remove(Coord.of(0, 0));
	assertNull(MiningHeatMap.snapFrontier(Coord2d.of(16.5, 5.5), Coord2d.of(5.5, 5.5),
	    Collections.emptySet(), probe));
    }
}
