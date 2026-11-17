package at.vystorm.insomnia.service;

import java.util.EnumSet;
import java.util.Set;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Painting;

/**
 * A cozy bedroom sleeps better: every kind of furnishing around the bed (soft floor, plants, books, warm light,
 * pictures, storage) counts once. Furniture types, not amounts, so a single carpet is as good as fifty.
 */
public final class BedroomComfort {
    public enum Category { SOFT, PLANT, BOOKS, LIGHT, ART, STORAGE }
    public record Result(Set<Category> found) {
        public Result { found = found.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(found)); }
        public int count() { return found.size(); }
        public Set<Category> missing() {
            EnumSet<Category> missing = EnumSet.allOf(Category.class);
            missing.removeAll(found);
            return missing;
        }
        /** Quality multiplier: +{@code perCategory} for every furnishing type. */
        public double multiplier(double perCategory) { return 1.0 + Math.max(0, perCategory) * found.size(); }
    }

    private BedroomComfort() {}

    /** Furnishing type of a block, or null when it does not make a room cozier. */
    public static Category classify(Material material) {
        String name = material.name();
        if (name.endsWith("_CARPET") || name.endsWith("_WOOL")) return Category.SOFT;
        if (name.startsWith("POTTED_") || material == Material.FLOWER_POT || material == Material.AZALEA
                || material == Material.FLOWERING_AZALEA || material == Material.SPORE_BLOSSOM) return Category.PLANT;
        if (material == Material.BOOKSHELF || material == Material.CHISELED_BOOKSHELF || material == Material.LECTERN) return Category.BOOKS;
        if (material == Material.LANTERN || material == Material.SOUL_LANTERN || material == Material.CANDLE
                || name.endsWith("_CANDLE") || name.endsWith("_CANDLE_CAKE") || material == Material.CANDLE_CAKE) return Category.LIGHT;
        if (material == Material.CHEST || material == Material.BARREL || material == Material.TRAPPED_CHEST
                || name.endsWith("SHULKER_BOX") || material == Material.DECORATED_POT) return Category.STORAGE;
        return null;
    }

    /** Scans a box around the sleeping spot; only loaded chunks, bounded to radius 8. */
    public static Result scan(Location at, int radius) {
        World world = at.getWorld();
        int r = Math.max(1, Math.min(8, radius));
        EnumSet<Category> found = EnumSet.noneOf(Category.class);
        int bx = at.getBlockX(), by = at.getBlockY(), bz = at.getBlockZ();
        for (int dx = -r; dx <= r && found.size() < Category.values().length; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (!world.isChunkLoaded((bx + dx) >> 4, (bz + dz) >> 4)) continue;
                for (int dy = -1; dy <= 3; dy++) {
                    int y = by + dy;
                    if (y < world.getMinHeight() || y >= world.getMaxHeight()) continue;
                    Block block = world.getBlockAt(bx + dx, y, bz + dz);
                    Category category = classify(block.getType());
                    if (category != null) found.add(category);
                }
            }
        }
        if (!world.getNearbyEntities(at, r + 0.5, 3.5, r + 0.5, entity -> entity instanceof Painting || entity instanceof ItemFrame).isEmpty())
            found.add(Category.ART);
        return new Result(found);
    }
}
