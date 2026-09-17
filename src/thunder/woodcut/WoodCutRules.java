package thunder.woodcut;

import haven.Coord;

import java.util.Locale;
import java.util.ArrayList;
import java.util.List;

/** Pure resource-name rules, kept separate so captures can be replayed headlessly. */
public final class WoodCutRules {
    private WoodCutRules() {}

    public enum Product {
        BLOCKS("Chop into blocks", "gfx/terobjs/stockpile-wblock", new Coord(2, 1)),
        BOARDS("Make boards", "gfx/terobjs/stockpile-board", new Coord(1, 4));

        public final String flower;
        public final String stockpile;
        /** Exact inventory footprint needed for one produced item. */
        public final Coord inventorySize;
        public final int inventoryCells;
        Product(String flower, String stockpile, Coord inventorySize) {
            this.flower = flower;
            this.stockpile = stockpile;
            this.inventorySize = inventorySize;
            this.inventoryCells = inventorySize.x * inventorySize.y;
        }
    }

    public static boolean isLog(String resid) {
        if(resid == null) return false;
        String n = resid.toLowerCase(Locale.ROOT);
        return n.startsWith("gfx/terobjs/trees/") && (n.endsWith("log") || n.endsWith("oldtrunk"));
    }

    public static boolean isCart(String resid) {
        if(resid == null) return false;
        String n = resid.toLowerCase(Locale.ROOT);
        return n.equals("gfx/terobjs/vehicle/cart") || n.endsWith("/cart");
    }

    public static boolean isProduct(String resid, Product product) {
        if(resid == null || product == null) return false;
        String n = resid.toLowerCase(Locale.ROOT);
        if(product == Product.BOARDS) return n.startsWith("gfx/invobjs/board-") || n.equals("gfx/invobjs/board");
        return n.startsWith("gfx/invobjs/wblock-") || n.equals("gfx/invobjs/wblock");
    }

    /** Decode the six cart cargo bits into the server interaction flags 2..7. */
    static List<Integer> occupiedCartSlots(int state) {
        List<Integer> slots = new ArrayList<>();
        if(state < 0) return slots;
        int bit = 2;
        for(int i = 0; i < 6; i++) {
            bit <<= 1;
            if((state & bit) != 0) slots.add(i + 2);
        }
        return slots;
    }
}
