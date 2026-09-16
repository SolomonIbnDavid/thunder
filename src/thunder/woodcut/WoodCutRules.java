package thunder.woodcut;

import java.util.Locale;
import java.util.ArrayList;
import java.util.List;

/** Pure resource-name rules, kept separate so captures can be replayed headlessly. */
public final class WoodCutRules {
    private WoodCutRules() {}

    public enum Product {
        BLOCKS("Chop into blocks", "gfx/terobjs/stockpile-wblock", 2),
        BOARDS("Make boards", "gfx/terobjs/stockpile-board", 4);

        public final String flower;
        public final String stockpile;
        /** Minimum empty inventory cells needed for one produced item. */
        public final int inventoryCells;
        Product(String flower, String stockpile, int inventoryCells) {
            this.flower = flower;
            this.stockpile = stockpile;
            this.inventoryCells = inventoryCells;
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
