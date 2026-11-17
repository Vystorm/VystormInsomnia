package at.vystorm.insomnia.dream.world;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DreamTerrainTest {
    private static final DreamTerrain FIRST = new DreamTerrain(42);
    private static final DreamTerrain SECOND = new DreamTerrain(43);

    @Test void occupancyIsSeededBoundedAndKeepsInteriorCavities() {
        DreamTerrain repeat = new DreamTerrain(42);
        int changedColumns = 0, multiRunColumns = 0, land = 0;
        Set<Integer> biomes = new HashSet<>();
        for (int z = -256; z <= 256; z++) for (int x = -256; x <= 256; x++) {
            int top = FIRST.surfaceY(x, z), bottom = FIRST.bottomY(x, z);
            assertEquals(top, repeat.surfaceY(x, z));
            assertEquals(bottom, repeat.bottomY(x, z));
            assertEquals(FIRST.biomeAt(x, z), repeat.biomeAt(x, z));
            if (top != SECOND.surfaceY(x, z)) changedColumns++;
            int runs = 0;
            boolean previous = false;
            for (int y = 64; y <= DreamTerrain.MAX_Y; y++) {
                boolean solid = FIRST.solid(x, y, z);
                assertEquals(solid, repeat.solid(x, y, z));
                if (solid && !previous) runs++;
                if (solid) assertTrue(y >= bottom && y <= top);
                previous = solid;
            }
            if (runs > 1) multiRunColumns++;
            if (top == Integer.MIN_VALUE) {
                assertEquals(Integer.MIN_VALUE, bottom);
                assertEquals(0, runs);
            } else {
                land++;
                assertTrue(top <= 164, "natural terrain leaves room for trees above peaks");
                assertTrue(FIRST.solid(x, top, z));
                assertTrue(FIRST.solid(x, bottom, z));
                assertFalse(FIRST.solid(x, top + 1, z));
                assertFalse(FIRST.solid(x, bottom - 1, z));
                biomes.add(FIRST.biomeAt(x, z));
            }
        }
        assertTrue(land > 40_000 && land < 220_000, "substantial land and void coexist");
        assertTrue(changedColumns > 30_000, "seed changes the generated terrain");
        assertTrue(multiRunColumns > 100, "3-D density produces shelves/cavities within columns");
        assertEquals(Set.of(0, 1, 2, 3, 4), biomes);
        for (int edge = -256; edge <= 256; edge++) {
            assertEquals(Integer.MIN_VALUE, FIRST.surfaceY(edge, -256));
            assertEquals(Integer.MIN_VALUE, FIRST.surfaceY(256, edge));
        }
        assertEquals(Integer.MIN_VALUE, FIRST.surfaceY(Integer.MAX_VALUE, 0));
        assertEquals(Integer.MIN_VALUE, FIRST.bottomY(0, Integer.MIN_VALUE));
        assertFalse(FIRST.solid(0, 63, 0));
        assertFalse(FIRST.solid(0, DreamTerrain.MAX_Y + 1, 0));
    }

    @Test void landComponentsVaryWithSeedAndContainBroadBuildablePatches() {
        assertNotEquals(components(FIRST), components(SECOND), "land topology is generated from the seed");
        for (DreamTerrain terrain : new DreamTerrain[]{FIRST, SECOND}) {
            int broadSites = 0;
            for (int z = -220; z <= 220; z += 8) for (int x = -220; x <= 220; x += 8) {
                boolean supported = true;
                for (int dz = -24; dz <= 24 && supported; dz += 4)
                    for (int dx = -24; dx <= 24; dx += 4) {
                        if (dx * dx + dz * dz <= 24 * 24
                                && terrain.surfaceY(x + dx, z + dz) == Integer.MIN_VALUE) {
                            supported = false;
                            break;
                        }
                    }
                if (supported) broadSites++;
            }
            assertTrue(broadSites >= 12, "multiple broad patches support terrain-selected landmarks");
        }
    }

    @Test void climateCellsHaveBroadLandInteriorsAndSpawnOnNaturalCloudLand() {
        for (DreamTerrain terrain : new DreamTerrain[]{FIRST, SECOND, new DreamTerrain(510232)}) {
            int[] interior = new int[5];
            for (int z = -236; z <= 236; z++) for (int x = -236; x <= 236; x++) {
                if (terrain.surfaceY(x, z) == Integer.MIN_VALUE) continue;
                int biome = terrain.biomeAt(x, z);
                if (terrain.biomeAt(x - 16, z) == biome && terrain.biomeAt(x + 16, z) == biome
                        && terrain.biomeAt(x, z - 16) == biome && terrain.biomeAt(x, z + 16) == biome)
                    interior[biome]++;
            }
            for (int biome = 0; biome < 5; biome++)
                assertTrue(interior[biome] >= 1_000, "each climate cell has a broad interior on land: " + biome);
            assertEquals(0, terrain.biomeAt(0, 0));
            for (int z = -24; z <= 24; z++) for (int x = -24; x <= 35; x++)
                assertNotEquals(Integer.MIN_VALUE, terrain.surfaceY(x, z), "spawn selects naturally supported land");
        }
    }

    @Test void hillsHaveBroadReliefAndClimateBoundariesDoNotMakeVerticalSteps() {
        for (DreamTerrain terrain : new DreamTerrain[]{FIRST, SECOND}) {
            int[] heights = new int[513 * 513];
            int count = 0, boundaries = 0, steepBoundaries = 0;
            long boundaryDifference = 0;
            for (int z = -250; z <= 250; z++) for (int x = -250; x <= 250; x++) {
                int top = terrain.surfaceY(x, z);
                // Exclude thin cliff-edge shelves: they are 3-D density features,
                // rather than the broad height field measured here.
                if (top == Integer.MIN_VALUE || top - terrain.bottomY(x, z) < 12) continue;
                heights[count++] = top;
                for (int direction = 0; direction < 2; direction++) {
                    int nx = x + (direction == 0 ? 1 : 0), nz = z + (direction == 1 ? 1 : 0);
                    int adjacent = terrain.surfaceY(nx, nz);
                    if (terrain.biomeAt(x, z) == terrain.biomeAt(nx, nz)
                            || adjacent == Integer.MIN_VALUE || adjacent - terrain.bottomY(nx, nz) < 12)
                        continue;
                    int difference = Math.abs(top - adjacent);
                    boundaries++;
                    boundaryDifference += difference;
                    if (difference > 3) steepBoundaries++;
                }
            }
            Arrays.sort(heights, 0, count);
            assertTrue(count > 40_000);
            // The old 96 + 13*fractal + 5*fractal field had only 36 blocks
            // of theoretical floor range; most of that field varied far less.
            assertTrue(heights[count - 1] - heights[0] >= 45, "substantial hills and valleys");
            assertTrue(heights[count * 9 / 10] - heights[count / 10] >= 32,
                    "relief spans broad areas rather than a handful of extreme spikes");
            assertTrue(heights[count - 1] <= 164);
            assertTrue(boundaries > 500, "measure many actual climate crossings");
            assertTrue(boundaryDifference / (double) boundaries < 1,
                    "climate changes blend into the surrounding slopes");
            assertTrue(steepBoundaries < boundaries * .02,
                    "climate boundaries do not produce repeated vertical seams");
        }
    }

    private static int components(DreamTerrain terrain) {
        boolean[] seen = new boolean[513 * 513];
        ArrayDeque<Integer> pending = new ArrayDeque<>();
        int count = 0;
        for (int z = -256; z <= 256; z++) for (int x = -256; x <= 256; x++) {
            int index = (z + 256) * 513 + x + 256;
            if (seen[index] || terrain.surfaceY(x, z) == Integer.MIN_VALUE) continue;
            count++;
            seen[index] = true;
            pending.add(index);
            while (!pending.isEmpty()) {
                int next = pending.removeFirst(), nx = next % 513 - 256, nz = next / 513 - 256;
                for (int direction = 0; direction < 4; direction++) {
                    int ax = nx + (direction == 0 ? -1 : direction == 1 ? 1 : 0);
                    int az = nz + (direction == 2 ? -1 : direction == 3 ? 1 : 0);
                    if (ax < -256 || ax > 256 || az < -256 || az > 256) continue;
                    int adjacent = (az + 256) * 513 + ax + 256;
                    if (!seen[adjacent] && terrain.surfaceY(ax, az) != Integer.MIN_VALUE) {
                        seen[adjacent] = true;
                        pending.add(adjacent);
                    }
                }
            }
        }
        return count;
    }
}
