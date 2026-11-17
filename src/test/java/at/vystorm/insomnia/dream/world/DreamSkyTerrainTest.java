package at.vystorm.insomnia.dream.world;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DreamSkyTerrainTest {
    private static final long[] SEEDS = {42, 43, 510232};
    private static final DreamTerrain[] BASES = {
            new DreamTerrain(SEEDS[0]), new DreamTerrain(SEEDS[1]), new DreamTerrain(SEEDS[2])};
    private static final DreamSkyTerrain[] SKIES = {
            new DreamSkyTerrain(SEEDS[0], BASES[0], 100),
            new DreamSkyTerrain(SEEDS[1], BASES[1], 100),
            new DreamSkyTerrain(SEEDS[2], BASES[2], 100)};

    @Test void completeDensityFieldIsDeterministicAndChangesWithSeed() {
        assertEquals(signature(SKIES[0]), signature(new DreamSkyTerrain(SEEDS[0], BASES[0], 100)));
        assertNotEquals(signature(SKIES[0]), signature(SKIES[1]));
        assertNotEquals(signature(SKIES[1]), signature(SKIES[2]));
    }

    @Test void zeroDisablesTheLayerAndDensityControlsCoverage() {
        DreamSkyTerrain disabled = new DreamSkyTerrain(SEEDS[0], BASES[0], 0);
        DreamSkyTerrain sparse = new DreamSkyTerrain(SEEDS[0], BASES[0], 35);
        int sparseColumns = 0, fullColumns = 0;
        for (int z = -256; z <= 256; z++) for (int x = -256; x <= 256; x++) {
            assertEquals(Integer.MIN_VALUE, disabled.surfaceY(x, z));
            assertEquals(Integer.MIN_VALUE, disabled.bottomY(x, z));
            for (int y = DreamSkyTerrain.MIN_Y; y <= DreamSkyTerrain.MAX_Y; y++)
                if (disabled.solid(x, y, z)) fail("disabled density contains a voxel");
            if (sparse.surfaceY(x, z) != Integer.MIN_VALUE) sparseColumns++;
            if (SKIES[0].surfaceY(x, z) != Integer.MIN_VALUE) fullColumns++;
        }
        assertTrue(sparseColumns < fullColumns / 2, "density changes the noise threshold substantially");
    }

    @Test void occupancyStaysBoundedWithAirAboveBaseAndClearArrival() {
        for (int seed = 0; seed < SEEDS.length; seed++) {
            DreamSkyTerrain sky = SKIES[seed];
            for (int z = -256; z <= 256; z++) for (int x = -256; x <= 256; x++) {
                int top = sky.surfaceY(x, z), bottom = sky.bottomY(x, z);
                if (Math.max(Math.abs(x), Math.abs(z)) < 24
                        || Math.max(Math.abs(x), Math.abs(z)) >= 248) {
                    if (top != Integer.MIN_VALUE) fail("sky violates arrival/edge clearance");
                }
                if (top == Integer.MIN_VALUE) {
                    if (bottom != Integer.MIN_VALUE) fail("empty column has a bottom");
                } else {
                    assertTrue(bottom >= DreamSkyTerrain.MIN_Y && top <= DreamSkyTerrain.MAX_Y);
                    assertTrue(sky.solid(x, top, z));
                    assertTrue(sky.solid(x, bottom, z));
                    assertFalse(sky.solid(x, top + 1, z));
                    assertFalse(sky.solid(x, bottom - 1, z));
                    int baseTop = BASES[seed].surfaceY(x, z);
                    if (baseTop != Integer.MIN_VALUE)
                        assertTrue(bottom - baseTop >= 25, "at least 24 air blocks separate both density fields");
                }
                for (int y = DreamSkyTerrain.MIN_Y; y <= DreamSkyTerrain.MAX_Y; y++)
                    if (sky.solid(x, y, z) && (y < bottom || y > top))
                        fail("occupancy escapes cached extrema");
            }
            assertEquals(Integer.MIN_VALUE, sky.surfaceY(Integer.MIN_VALUE, 0));
            assertEquals(Integer.MIN_VALUE, sky.bottomY(0, Integer.MAX_VALUE));
            assertFalse(sky.solid(257, 190, 0));
            assertFalse(sky.solid(0, 190, -257));
            assertFalse(sky.solid(50, DreamSkyTerrain.MIN_Y - 1, 50));
            assertFalse(sky.solid(50, DreamSkyTerrain.MAX_Y + 1, 50));
        }
    }

    @Test void noiseProducesBroadDisconnectedInteriorsHillsAndOpenings() {
        for (DreamSkyTerrain sky : SKIES) {
            int columns = 0, voxels = 0, thick = 0, openings = 0, broadSites = 0;
            Set<Integer> heights = new HashSet<>();
            for (int z = -256; z <= 256; z++) for (int x = -256; x <= 256; x++) {
                int top = sky.surfaceY(x, z);
                if (top == Integer.MIN_VALUE) continue;
                columns++;
                heights.add(top);
                if (top - sky.bottomY(x, z) >= 16) thick++;
                int runs = 0;
                boolean previous = false;
                for (int y = DreamSkyTerrain.MIN_Y; y <= DreamSkyTerrain.MAX_Y; y++) {
                    boolean solid = sky.solid(x, y, z);
                    if (solid) voxels++;
                    if (solid && !previous) runs++;
                    previous = solid;
                }
                if (runs > 1) openings++;
                if (x % 8 == 0 && z % 8 == 0
                        && sky.surfaceY(x - 8, z) != Integer.MIN_VALUE
                        && sky.surfaceY(x + 8, z) != Integer.MIN_VALUE
                        && sky.surfaceY(x, z - 8) != Integer.MIN_VALUE
                        && sky.surfaceY(x, z + 8) != Integer.MIN_VALUE) broadSites++;
            }
            assertTrue(columns > 5_000 && columns < 60_000, "noticeable sky layer still leaves broad open sky");
            assertTrue(voxels > 100_000);
            assertTrue(thick > 1_000, "substantial hanging undersides");
            assertTrue(heights.size() >= 15, "uneven hills instead of flat clipped tops");
            assertTrue(openings > 100, "three-dimensional openings survive inside islands");
            assertTrue(broadSites > 40, "broad island interiors rather than isolated noise speckles");
            assertTrue(broadComponents(sky) >= 3, "several disconnected broad noise contours");
        }
    }

    private static long signature(DreamSkyTerrain sky) {
        long hash = 1;
        for (int z = -256; z <= 256; z++) for (int x = -256; x <= 256; x++)
            for (int y = DreamSkyTerrain.MIN_Y; y <= DreamSkyTerrain.MAX_Y; y++)
                hash = hash * 31 + (sky.solid(x, y, z) ? 1 : 0);
        return hash;
    }

    private static int broadComponents(DreamSkyTerrain sky) {
        boolean[] seen = new boolean[513 * 513];
        ArrayDeque<Integer> pending = new ArrayDeque<>();
        int broad = 0;
        for (int z = -256; z <= 256; z++) for (int x = -256; x <= 256; x++) {
            int index = (z + 256) * 513 + x + 256;
            if (seen[index] || sky.surfaceY(x, z) == Integer.MIN_VALUE) continue;
            seen[index] = true;
            pending.add(index);
            int size = 0;
            while (!pending.isEmpty()) {
                int next = pending.removeFirst(), nx = next % 513 - 256, nz = next / 513 - 256;
                size++;
                for (int direction = 0; direction < 4; direction++) {
                    int ax = nx + (direction == 0 ? -1 : direction == 1 ? 1 : 0);
                    int az = nz + (direction == 2 ? -1 : direction == 3 ? 1 : 0);
                    if (ax < -256 || ax > 256 || az < -256 || az > 256) continue;
                    int adjacent = (az + 256) * 513 + ax + 256;
                    if (!seen[adjacent] && sky.surfaceY(ax, az) != Integer.MIN_VALUE) {
                        seen[adjacent] = true;
                        pending.add(adjacent);
                    }
                }
            }
            if (size >= 500) broad++;
        }
        return broad;
    }
}
