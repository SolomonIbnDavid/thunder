package haven.pathfinding;

import haven.Coord2d;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class OrganizerAreaSelectorTest {

   @Test
   void firstClickReturnsNullAndSetsFirstPoint() {
      OrganizerAreaSelector selector = new OrganizerAreaSelector();
      Assertions.assertEquals(OrganizerAreaSelector.State.EMPTY, selector.state());
      Assertions.assertNull(selector.click(Coord2d.of(5.0, 5.0)));
      Assertions.assertEquals(OrganizerAreaSelector.State.FIRST_POINT, selector.state());
      Assertions.assertNull(selector.selection());
   }

   @Test
   void secondClickCompletesRectangleInEitherDirection() {
      OrganizerAreaSelector selector = new OrganizerAreaSelector();
      selector.click(Coord2d.of(5.0, 5.0));       // tile (0,0)
      OrganizerAreaSelector.Selection sel = selector.click(Coord2d.of(20.0, 20.0)); // tile (1,1)
      Assertions.assertNotNull(sel);
      Assertions.assertEquals(OrganizerAreaSelector.State.COMPLETE, selector.state());
      Assertions.assertEquals(2, sel.widthTiles());
      Assertions.assertEquals(2, sel.heightTiles());
      Assertions.assertEquals(4, sel.tileCount());
      Assertions.assertEquals(Coord2d.of(0.0, 0.0), sel.min);
      Assertions.assertEquals(Coord2d.of(22.0, 22.0), sel.max);

      // Reversed drag order yields the same normalized rectangle.
      OrganizerAreaSelector rev = new OrganizerAreaSelector();
      rev.click(Coord2d.of(20.0, 20.0));
      OrganizerAreaSelector.Selection revSel = rev.click(Coord2d.of(5.0, 5.0));
      Assertions.assertEquals(2, revSel.widthTiles());
      Assertions.assertEquals(2, revSel.heightTiles());
      Assertions.assertEquals(Coord2d.of(0.0, 0.0), revSel.min);
      Assertions.assertEquals(Coord2d.of(22.0, 22.0), revSel.max);
   }

   @Test
   void singleTileSelectionHasUnitBounds() {
      OrganizerAreaSelector selector = new OrganizerAreaSelector();
      selector.click(Coord2d.of(1.0, 2.0));
      OrganizerAreaSelector.Selection sel = selector.click(Coord2d.of(3.0, 4.0)); // same tile (0,0)
      Assertions.assertEquals(1, sel.widthTiles());
      Assertions.assertEquals(1, sel.heightTiles());
      Assertions.assertEquals(1, sel.tileCount());
      Assertions.assertEquals(Coord2d.of(0.0, 0.0), sel.min);
      Assertions.assertEquals(Coord2d.of(11.0, 11.0), sel.max);
   }

   @Test
   void clickingAgainAfterCompleteStartsANewSelection() {
      OrganizerAreaSelector selector = new OrganizerAreaSelector();
      selector.click(Coord2d.of(0.0, 0.0));
      selector.click(Coord2d.of(20.0, 20.0));
      Assertions.assertEquals(OrganizerAreaSelector.State.COMPLETE, selector.state());
      Assertions.assertNull(selector.click(Coord2d.of(50.0, 50.0)));
      Assertions.assertEquals(OrganizerAreaSelector.State.FIRST_POINT, selector.state());
      Assertions.assertNull(selector.selection());
   }

   @Test
   void cancelResetsEverything() {
      OrganizerAreaSelector selector = new OrganizerAreaSelector();
      selector.click(Coord2d.of(0.0, 0.0));
      selector.click(Coord2d.of(20.0, 20.0));
      Assertions.assertNotNull(selector.selection());
      selector.cancel();
      Assertions.assertEquals(OrganizerAreaSelector.State.EMPTY, selector.state());
      Assertions.assertNull(selector.selection());
      Assertions.assertNull(selector.firstTile());
   }
}
