package at.vystorm.insomnia.dream.world;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DreamGroundCoverTest {
    @Test void decisionsRepeatAndSeedsChangeTheLandscape() {
        int changedGround = 0, changedCover = 0;
        for (int z = -64; z <= 64; z++) for (int x = -64; x <= 64; x++) {
            Material ground = DreamGroundCover.ground(42, x, z, 1, 1, 80);
            Material cover = DreamGroundCover.cover(42, x, z, 1, 1, 80);
            assertSame(ground, DreamGroundCover.ground(42, x, z, 1, 1, 80));
            assertSame(cover, DreamGroundCover.cover(42, x, z, 1, 1, 80));
            if (ground != DreamGroundCover.ground(43, x, z, 1, 1, 80)) changedGround++;
            if (cover != DreamGroundCover.cover(43, x, z, 1, 1, 80)) changedCover++;
        }
        assertTrue(changedGround > 500);
        assertTrue(changedCover > 500);
    }

    @Test void disabledDensityAndSteepPlantSitesAreEmpty() {
        for (int biome = 0; biome < 5; biome++)
            for (int z = -64; z <= 64; z++) for (int x = -64; x <= 64; x++) {
                assertNull(DreamGroundCover.ground(42, x, z, biome, 1, 0));
                assertNull(DreamGroundCover.ground(42, x, z, biome, 8, 0));
                assertNull(DreamGroundCover.cover(42, x, z, biome, 1, 0));
                assertNull(DreamGroundCover.cover(42, x, z, biome, 3, 100));
            }
    }

    @Test void biomesUseDistinctSingleBlockPlantPalettes() {
        Set<Set<Material>> palettes = new HashSet<>();
        for (int biome = 0; biome < 5; biome++) {
            Set<Material> found = new HashSet<>();
            for (int z = -128; z <= 128; z++) for (int x = -128; x <= 128; x++) {
                Material material = DreamGroundCover.cover(42, x, z, biome, 2, 100);
                if (material != null) found.add(material);
            }
            Set<Material> expected = switch (biome) {
                case 0 -> Set.of(Material.CORNFLOWER, Material.OXEYE_DAISY, Material.SHORT_GRASS);
                case 1 -> Set.of(Material.AZALEA, Material.MOSS_CARPET, Material.FERN);
                case 2 -> Set.of(Material.AZURE_BLUET, Material.WHITE_TULIP, Material.MOSS_CARPET);
                case 3 -> Set.of(Material.FLOWERING_AZALEA, Material.ALLIUM, Material.PINK_TULIP);
                default -> Set.of(Material.BLUE_ORCHID, Material.MOSS_CARPET, Material.FERN);
            };
            assertEquals(expected, found);
            palettes.add(found);
        }
        assertEquals(5, palettes.size());
    }

    @Test void groundPatchesRemainCoherentAndLeaveMostGrassIntact() {
        int replaced = 0, boundaries = 0, adjacentPatches = 0, expanded = 0;
        Set<Material> gentle = new HashSet<>(), rocky = new HashSet<>(), nightRocky = new HashSet<>();
        for (int z = -128; z <= 128; z++) for (int x = -128; x <= 128; x++) {
            Material current = DreamGroundCover.ground(42, x, z, 1, 2, 100);
            Material neighbor = DreamGroundCover.ground(42, x + 1, z, 1, 2, 100);
            Material sparse = DreamGroundCover.ground(42, x, z, 1, 2, 40);
            if (current != null) {
                replaced++;
                gentle.add(current);
                if (neighbor != null) adjacentPatches++;
                if (sparse == null) expanded++;
            }
            if ((current == null) != (neighbor == null)) boundaries++;
            if (sparse != null) assertSame(sparse, current, "increasing density expands existing patches");
            assertEquals(current, DreamGroundCover.ground(42, x, z, 3, 2, 100));
            for (int biome : new int[]{0, 2, 4})
                assertNull(DreamGroundCover.ground(42, x, z, biome, 2, 100));
            Material rock = DreamGroundCover.ground(42, x, z, 2, 3, 100);
            Material nightRock = DreamGroundCover.ground(42, x, z, 4, 3, 100);
            if (rock != null) rocky.add(rock);
            if (nightRock != null) nightRocky.add(nightRock);
        }
        assertTrue(replaced > 1_000 && replaced < 257 * 257 / 2, "grass remains the majority surface");
        assertTrue(adjacentPatches > replaced * .9, "neighbouring sites form continuous patches");
        assertTrue(boundaries > 100, "patches have multiple irregular edges");
        assertTrue(expanded > 500);
        assertEquals(Set.of(Material.MOSS_BLOCK, Material.ROOTED_DIRT, Material.COARSE_DIRT), gentle);
        assertEquals(Set.of(Material.TUFF, Material.STONE), rocky);
        assertEquals(Set.of(Material.BASALT, Material.BLACKSTONE), nightRocky);
    }
}
