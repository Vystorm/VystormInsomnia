package at.vystorm.insomnia.dream.world;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DreamObjectGeneratorTest {
    private static final DreamObjectGenerator.Surface FLAT = surface(false, false);

    @Test void seedControlsPositionsShapesAndOrderWithoutDensityZeroWork() {
        var first = DreamObjectGenerator.generate(42, 100, FLAT);
        assertEquals(first, DreamObjectGenerator.generate(42, 100, FLAT));
        assertNotEquals(first, DreamObjectGenerator.generate(43, 100, FLAT));
        assertTrue(first.size() >= 60 && first.size() <= 450, "moderate scenery density");
        var sparse=DreamObjectGenerator.generate(42,35,FLAT);
        assertTrue(sparse.size()<first.size());
        assertTrue(first.containsAll(sparse),"density adds candidates without moving existing shapes");
        assertTrue(first.stream().anyMatch(DreamObjectGenerator.Feature::upper));
        assertTrue(first.stream().anyMatch(f -> !f.upper()));
        assertThrows(UnsupportedOperationException.class, () -> first.clear());
        assertThrows(UnsupportedOperationException.class, () -> first.getFirst().voxels().clear());
        assertEquals(List.of(), DreamObjectGenerator.generate(1, 0, new DreamObjectGenerator.Surface() {
            public int height(int x, int z, boolean upper) { fail("disabled generator sampled terrain"); return 0; }
            public int biome(int x, int z) { fail("disabled generator sampled biome"); return 0; }
        }));
    }

    @Test void biomeShapesAreVariedBoundedAndConnected() {
        Set<String> kinds = new HashSet<>();
        Set<org.bukkit.Material> materials = new HashSet<>();
        for (long seed = 40; seed < 44; seed++) {
            for (var feature : DreamObjectGenerator.generate(seed, 100, FLAT)) {
                kinds.add(feature.kind());
                checkSupportedAndConnected(feature, FLAT);
                feature.voxels().forEach(v -> materials.add(v.material()));
                assertTrue(Math.abs(feature.x()) <= 230 && Math.abs(feature.z()) <= 230);
            }
        }
        assertEquals(Set.of("fallen-tree", "boulder-cluster", "giant-mushroom", "mineral-cluster"), kinds);
        assertTrue(materials.contains(org.bukkit.Material.AMETHYST_BLOCK));
        assertTrue(materials.contains(org.bukkit.Material.QUARTZ_BLOCK));
        assertTrue(materials.contains(org.bukkit.Material.MUSHROOM_STEM));
    }

    @Test void noisyBoulderRimCannotLeaveAnIsolatedOuterCell() {
        // Seed 40 previously left a disconnected cell at (39,111,-190).
        var boulder = DreamObjectGenerator.generate(40, 100, FLAT).stream()
                .filter(f -> f.kind().equals("boulder-cluster") && f.x() == 42 && f.z() == -186)
                .findFirst().orElseThrow();
        assertFalse(boulder.voxels().stream().anyMatch(v -> v.x() == 39 && v.y() == 111 && v.z() == -190));
        checkSupportedAndConnected(boulder, FLAT);
    }

    @Test void gentleSlopesHaveGroundedBasesAndVoidRejectsWholeShapes() {
        var slope = surface(true, false);
        var features = DreamObjectGenerator.generate(42, 100, slope);
        assertTrue(features.size() > 40);
        features.forEach(f -> checkSupportedAndConnected(f, slope));
        assertTrue(DreamObjectGenerator.generate(42, 100, surface(false, true)).isEmpty());
        var holes = new DreamObjectGenerator.Surface() {
            public int height(int x, int z, boolean upper) {
                return Math.floorMod(x, 7) == 0 ? Integer.MIN_VALUE : 110;
            }
            public int biome(int x, int z) { return 3; }
        };
        DreamObjectGenerator.generate(42, 100, holes).forEach(f -> checkSupportedAndConnected(f, holes));
        var cliffs = new DreamObjectGenerator.Surface() {
            public int height(int x, int z, boolean upper) { return (Math.floorMod(x, 2) == 0 ? 100 : 108); }
            public int biome(int x, int z) { return 2; }
        };
        assertTrue(DreamObjectGenerator.generate(42, 100, cliffs).isEmpty());
        var ceiling = new DreamObjectGenerator.Surface() {
            public int height(int x, int z, boolean upper) { return 250; }
            public int biome(int x, int z) { return 1; }
        };
        assertTrue(DreamObjectGenerator.generate(42, 100, ceiling).isEmpty());
    }

    private record Point(int x, int y, int z) {}

    private static void checkSupportedAndConnected(DreamObjectGenerator.Feature feature,
                                                    DreamObjectGenerator.Surface surface) {
        Set<Point> remaining = new HashSet<>();
        boolean touching = false;
        for (var voxel : feature.voxels()) {
            assertTrue(Math.abs(voxel.x()) <= 244 && Math.abs(voxel.z()) <= 244 && voxel.y() <= 250);
            assertTrue(Math.max(Math.abs(voxel.x()), Math.abs(voxel.z())) >= 14);
            int ground = surface.height(voxel.x(), voxel.z(), feature.upper());
            assertNotEquals(Integer.MIN_VALUE, ground);
            assertTrue(voxel.y() > ground);
            touching |= voxel.y() == ground + 1;
            assertTrue(remaining.add(new Point(voxel.x(), voxel.y(), voxel.z())), "unique voxels");
        }
        assertTrue(touching, "every object meets the terrain");
        ArrayDeque<Point> pending = new ArrayDeque<>();
        Point first = remaining.iterator().next();
        remaining.remove(first);
        pending.add(first);
        while (!pending.isEmpty()) {
            Point p = pending.removeFirst();
            for (Point adjacent : List.of(new Point(p.x - 1, p.y, p.z), new Point(p.x + 1, p.y, p.z),
                    new Point(p.x, p.y - 1, p.z), new Point(p.x, p.y + 1, p.z),
                    new Point(p.x, p.y, p.z - 1), new Point(p.x, p.y, p.z + 1))) {
                if (remaining.remove(adjacent)) pending.add(adjacent);
            }
        }
        assertTrue(remaining.isEmpty(), "no detached debris in " + feature.kind());
    }

    private static DreamObjectGenerator.Surface surface(boolean slope, boolean voidOnly) {
        return new DreamObjectGenerator.Surface() {
            public int height(int x, int z, boolean upper) {
                return voidOnly ? Integer.MIN_VALUE : (upper ? 200 : 110) + (slope ? Math.floorMod(x, 4) : 0);
            }
            public int biome(int x, int z) { return Math.floorMod(Math.floorDiv(x, 70), 5); }
        };
    }
}
