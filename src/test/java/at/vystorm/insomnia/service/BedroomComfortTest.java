package at.vystorm.insomnia.service;

import static org.junit.jupiter.api.Assertions.*;
import at.vystorm.insomnia.service.BedroomComfort.Category;
import java.util.EnumSet;
import java.util.Set;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class BedroomComfortTest {
    @Test void classifiesFurnishings() {
        assertEquals(Category.SOFT, BedroomComfort.classify(Material.RED_CARPET));
        assertEquals(Category.SOFT, BedroomComfort.classify(Material.WHITE_WOOL));
        assertEquals(Category.PLANT, BedroomComfort.classify(Material.POTTED_FERN));
        assertEquals(Category.BOOKS, BedroomComfort.classify(Material.CHISELED_BOOKSHELF));
        assertEquals(Category.LIGHT, BedroomComfort.classify(Material.LANTERN));
        assertEquals(Category.LIGHT, BedroomComfort.classify(Material.BLUE_CANDLE));
        assertEquals(Category.STORAGE, BedroomComfort.classify(Material.BARREL));
        assertEquals(Category.STORAGE, BedroomComfort.classify(Material.PURPLE_SHULKER_BOX));
        assertNull(BedroomComfort.classify(Material.STONE));
        assertNull(BedroomComfort.classify(Material.TORCH));
        assertNull(BedroomComfort.classify(Material.RED_BED));
    }

    @Test void eachTypeCountsOnceAndMissingListsTheRest() {
        var result = new BedroomComfort.Result(EnumSet.of(Category.SOFT, Category.LIGHT));
        assertEquals(2, result.count());
        assertEquals(Set.of(Category.PLANT, Category.BOOKS, Category.ART, Category.STORAGE), result.missing());
        assertEquals(1.08, result.multiplier(0.04), 1e-9);
        assertEquals(1.0, new BedroomComfort.Result(EnumSet.noneOf(Category.class)).multiplier(0.04), 1e-9);
        assertEquals(1.24, new BedroomComfort.Result(EnumSet.allOf(Category.class)).multiplier(0.04), 1e-9);
        assertEquals(1.0, result.multiplier(-1), 1e-9);
    }
}
