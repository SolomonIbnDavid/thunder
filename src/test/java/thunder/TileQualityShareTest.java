package thunder;

import haven.MessageBuf;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TileQualityShareTest {
    @Test
    void sharedGridNormalizesKeysAndKeepsHighestDuplicateQuality() {
        MessageBuf out = new MessageBuf();
        out.adduint8(1);
        out.addint64(0x1234L);
        out.addint32(3);
        out.adduint16(205);
        out.addstring("stone/magnetite");
        out.addint16((short)400);
        out.adduint16(205);
        out.addstring("ore/black-ore");
        out.addint16((short)525);
        out.adduint16(307);
        out.addstring("ruby");
        out.addint16((short)101);

        TileQuality.SharedGrid shared = TileQuality.readSharedGrid(new MessageBuf(out.fin()));
        assertEquals(0x1234L, shared.gridId);
        assertEquals(Map.of("ore/black-ore", (short)525), shared.tiles.get(205));
        assertEquals(Map.of("gem/ruby", (short)101), shared.tiles.get(307));
    }

    @Test
    void sharedGridRejectsOutOfRangeTiles() {
        MessageBuf out = new MessageBuf();
        out.adduint8(1);
        out.addint64(1L);
        out.addint32(1);
        out.adduint16(65535);
        out.addstring("stone/granite");
        out.addint16((short)100);

        assertThrows(RuntimeException.class, () -> TileQuality.readSharedGrid(new MessageBuf(out.fin())));
    }
}
