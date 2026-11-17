package at.vystorm.insomnia.dream.world;

import org.bukkit.Material;

/** Seeded surface patches and single-block plants; contains no world access or mutable state. */
final class DreamGroundCover {
    private DreamGroundCover() { }

    /** Null leaves the existing surface intact. Density changes patch extent, not their location. */
    static Material ground(long seed, int x, int z, int biome, int slope, int density) {
        if (density == 0) return null;
        double patch = DreamTerrain.fractal(seed + 931, x / 19.0, z / 19.0);
        if (patch < .55 - density * .004) return null;
        double palette = DreamTerrain.noise(seed + 932, x / 11.0, 0, z / 11.0);
        if (slope >= 3) {
            if (biome == 4) return palette > .15 ? Material.BLACKSTONE : Material.BASALT;
            return palette > .1 ? Material.TUFF : Material.STONE;
        }
        if (biome != 1 && biome != 3) return null;
        return palette < -.2 ? Material.COARSE_DIRT
                : palette > .25 ? Material.ROOTED_DIRT : Material.MOSS_BLOCK;
    }

    /** Plants occupy coherent habitat patches with sparse, seeded gaps within them. */
    static Material cover(long seed, int x, int z, int biome, int slope, int density) {
        if (density == 0 || slope > 2) return null;
        double habitat = DreamTerrain.fractal(seed + 941, x / 24.0, z / 24.0);
        if (habitat < -.15) return null;
        double scatter = DreamTerrain.noise(seed + 942, x * 1.87, 0, z * 1.87);
        if (scatter < .84 - density * .006) return null;
        double palette = DreamTerrain.noise(seed + 943, x / 8.0, 0, z / 8.0);
        return switch (biome) {
            case 0 -> palette < -.3 ? Material.CORNFLOWER
                    : palette > .35 ? Material.OXEYE_DAISY : Material.SHORT_GRASS;
            case 1 -> palette < -.3 ? Material.AZALEA
                    : palette > .4 ? Material.MOSS_CARPET : Material.FERN;
            case 2 -> palette < -.3 ? Material.AZURE_BLUET
                    : palette > .35 ? Material.WHITE_TULIP : Material.MOSS_CARPET;
            case 3 -> palette < -.3 ? Material.FLOWERING_AZALEA
                    : palette > .35 ? Material.ALLIUM : Material.PINK_TULIP;
            case 4 -> palette < -.3 ? Material.BLUE_ORCHID
                    : palette > .35 ? Material.MOSS_CARPET : Material.FERN;
            default -> null;
        };
    }
}
