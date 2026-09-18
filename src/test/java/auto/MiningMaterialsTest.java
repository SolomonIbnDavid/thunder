package auto;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class MiningMaterialsTest {
    @Test
    void classifiesOnlyTheConfiguredHardMetalBars() {
        Assertions.assertTrue(MiningMaterials.isHardBarName("Bar of Bronze"));
        Assertions.assertTrue(MiningMaterials.isHardBarName("Bar of Wrought Iron"));
        Assertions.assertFalse(MiningMaterials.isHardBarName("Bar of Iron"));
        Assertions.assertFalse(MiningMaterials.isHardBarName("Bronze Bar"));
        Assertions.assertFalse(MiningMaterials.isHardBarName(null));
    }

    @Test
    void recognizesInventoryOpeningTablesAsFoodSources() {
        Assertions.assertTrue(MiningMaterials.isFoodTableResid("gfx/terobjs/htable"));
        Assertions.assertTrue(MiningMaterials.isFoodTableResid("gfx/terobjs/table"));
        Assertions.assertTrue(MiningMaterials.isFoodTableResid("gfx/terobjs/furn/table-rustic"));
        Assertions.assertTrue(MiningMaterials.isFoodTableResid("gfx/terobjs/furn/table-elegant[full]"));
        Assertions.assertFalse(MiningMaterials.isFoodTableResid("gfx/terobjs/studydesk"));
        Assertions.assertFalse(MiningMaterials.isFoodTableResid("gfx/terobjs/chair"));
        Assertions.assertFalse(MiningMaterials.isFoodTableResid(null));
    }

    @Test
    void recognizesEveryBasketResourceAsInventoryStorage() {
        Assertions.assertTrue(MiningMaterials.isInventoryBasketResid("gfx/terobjs/wbasket"));
        Assertions.assertTrue(MiningMaterials.isInventoryBasketResid("gfx/terobjs/birchbasket"));
        Assertions.assertTrue(MiningMaterials.isInventoryBasketResid("gfx/terobjs/leatherbasket[full]"));
        Assertions.assertTrue(MiningMaterials.isInventoryBasketResid("gfx/terobjs/thatchbasket"));
        Assertions.assertTrue(MiningMaterials.isInventoryBasketResid("gfx/terobjs/futurebasket"));
        Assertions.assertFalse(MiningMaterials.isInventoryBasketResid("gfx/invobjs/basket"));
        Assertions.assertFalse(MiningMaterials.isInventoryBasketResid("gfx/terobjs/basketweaver"));
        Assertions.assertFalse(MiningMaterials.isInventoryBasketResid(null));
    }

    @Test
    void nullIsNotRockMaterial() {
        Assertions.assertFalse(MiningMaterials.isRockMaterial((haven.GItem)null));
    }
}
