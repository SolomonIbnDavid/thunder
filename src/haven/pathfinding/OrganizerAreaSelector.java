package haven.pathfinding;

import haven.Coord;
import haven.Coord2d;
import haven.MCache;

/** Pure state and geometry for the opt-in organizer's two-click area tool. */
public final class OrganizerAreaSelector {
   public enum State { EMPTY, FIRST_POINT, COMPLETE }

   public static final class Selection {
      public final Coord minTile;
      public final Coord maxTile;
      public final Coord2d min;
      public final Coord2d max;

      private Selection(Coord minTile, Coord maxTile) {
         this.minTile = minTile;
         this.maxTile = maxTile;
         this.min = Coord2d.of(minTile).mul(MCache.tilesz);
         this.max = Coord2d.of(maxTile.add(1, 1)).mul(MCache.tilesz);
      }

      public int widthTiles() { return maxTile.x - minTile.x + 1; }
      public int heightTiles() { return maxTile.y - minTile.y + 1; }
      public int tileCount() { return widthTiles() * heightTiles(); }

      @Override public String toString() {
         return widthTiles() + "x" + heightTiles() + " tiles (" + minTile + ".." + maxTile + ")";
      }
   }

   private State state = State.EMPTY;
   private Coord firstTile;
   private Selection selection;

   public State state() { return state; }
   public Coord firstTile() { return firstTile; }
   public Selection selection() { return selection; }

   /** Records a ground click, snapping it to the containing world tile. */
   public Selection click(Coord2d world) {
      if (world == null) throw new IllegalArgumentException("world");
      Coord tile = world.floor(MCache.tilesz);
      if (state == State.EMPTY || state == State.COMPLETE) {
         firstTile = tile;
         selection = null;
         state = State.FIRST_POINT;
         return null;
      }
      Coord min = Coord.of(Math.min(firstTile.x, tile.x), Math.min(firstTile.y, tile.y));
      Coord max = Coord.of(Math.max(firstTile.x, tile.x), Math.max(firstTile.y, tile.y));
      selection = new Selection(min, max);
      state = State.COMPLETE;
      return selection;
   }

   public void cancel() {
      state = State.EMPTY;
      firstTile = null;
      selection = null;
   }
}
