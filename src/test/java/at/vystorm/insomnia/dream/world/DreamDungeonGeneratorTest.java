package at.vystorm.insomnia.dream.world;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static at.vystorm.insomnia.dream.world.DreamLayout.*;
import static org.junit.jupiter.api.Assertions.*;

class DreamDungeonGeneratorTest {
    private static final BlockPos ENTRY = new BlockPos(50, 100, -70);

    @Test void deterministicImmutableAndTranslationIndependent() {
        for (DungeonType type : DungeonType.values()) {
            var first = DreamDungeonGenerator.generate(42, type, ENTRY);
            assertEquals(first, DreamDungeonGenerator.generate(42, type, ENTRY));
            assertNotEquals(first, DreamDungeonGenerator.generate(43, type, ENTRY));
            assertThrows(UnsupportedOperationException.class, () -> first.voxels().clear());
            assertThrows(UnsupportedOperationException.class, () -> first.dungeon().puzzleAnchors().clear());
            var local = DreamDungeonGenerator.generate(42, type, new BlockPos(0, 1, 0));
            assertEquals(first.voxels().size(), local.voxels().size());
            for (int i = 0; i < first.voxels().size(); i++) {
                var a = first.voxels().get(i); var b = local.voxels().get(i);
                assertEquals(a.x() - ENTRY.x(), b.x());
                assertEquals(a.y() - ENTRY.y(), b.y() - 1);
                assertEquals(a.z() - ENTRY.z(), b.z());
                assertEquals(a.material(), b.material());
            }
        }
    }

    @Test void everySeedHasBoundedSupportedUniqueGeometryAndWalkableObjectives() {
        for (DungeonType type : DungeonType.values()) for (long seed = 0; seed < 32; seed++) {
            var plan = DreamDungeonGenerator.generate(seed, type, ENTRY);
            Map<BlockPos, Material> blocks = new HashMap<>();
            for (var voxel : plan.voxels()) {
                var point = new BlockPos(voxel.x(), voxel.y(), voxel.z());
                assertNull(blocks.put(point, voxel.material()), "duplicate voxel");
                assertTrue(plan.dungeon().bounds().contains(point.x(), point.z()));
                assertTrue(Math.hypot(point.x() - ENTRY.x(), point.z() - ENTRY.z()) <= 32);
                assertTrue(point.y() >= ENTRY.y() - 1 && point.y() <= ENTRY.y() + 7);
            }
            for (var point : blocks.keySet())
                assertNotEquals(Material.AIR, blocks.get(new BlockPos(point.x(), ENTRY.y() - 1, point.z())), "supported column");
            var bounds = plan.dungeon().bounds();
            assertEquals(bounds.minX(), blocks.keySet().stream().mapToInt(BlockPos::x).min().orElseThrow());
            assertEquals(bounds.maxX(), blocks.keySet().stream().mapToInt(BlockPos::x).max().orElseThrow());
            assertEquals(bounds.minZ(), blocks.keySet().stream().mapToInt(BlockPos::z).min().orElseThrow());
            assertEquals(bounds.maxZ(), blocks.keySet().stream().mapToInt(BlockPos::z).max().orElseThrow());
            Set<BlockPos> reachable = reachable(blocks);
            var dungeon = plan.dungeon();
            assertEquals(ENTRY, dungeon.entry());
            assertEquals(0, dungeon.side());
            assertEquals(type == DungeonType.NIGHTMARE_KEEP ? 3 : 4, dungeon.puzzleAnchors().size());
            assertEquals(dungeon.puzzleAnchors().size(), new HashSet<>(dungeon.puzzleAnchors()).size());
            assertFalse(dungeon.puzzleAnchors().contains(dungeon.reward()));
            assertTrue(reachable.containsAll(dungeon.puzzleAnchors()));
            assertTrue(reachable.contains(dungeon.reward()));
            assertTrue(reachable.contains(dungeon.encounterSpawn()));
            if (type == DungeonType.NIGHTMARE_KEEP) {
                for (var anchor : dungeon.puzzleAnchors()) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
                    assertTrue(walkable(blocks, new BlockPos(anchor.x() + dx, anchor.y(), anchor.z() + dz)), "safe 3x3 spawn clearance");
                // Roofless arena has a broad unobstructed central rectangle in either rotation.
                var center = dungeon.encounterSpawn();
                int clearX = run(blocks, center, 1, 0) + run(blocks, center, -1, 0) - 1;
                int clearZ = run(blocks, center, 0, 1) + run(blocks, center, 0, -1) - 1;
                assertTrue(Math.min(clearX, clearZ) >= 11 && Math.max(clearX, clearZ) >= 13);
            }
        }
    }

    @Test void seedsChangeFootprintsAndPalettesDistinguishTypes() {
        for (DungeonType type : DungeonType.values()) {
            Set<Set<BlockPos>> footprints = new HashSet<>();
            for (long seed = 0; seed < 12; seed++) {
                Set<BlockPos> floor = new HashSet<>();
                DreamDungeonGenerator.generate(seed, type, ENTRY).voxels().stream()
                        .filter(v -> v.y() == ENTRY.y() - 1).forEach(v -> floor.add(new BlockPos(v.x(), v.y(), v.z())));
                footprints.add(floor);
            }
            assertTrue(footprints.size() >= 8, "procedural footprint variety");
        }
        assertTrue(DreamDungeonGenerator.generate(42, DungeonType.CLOUD_RUINS, ENTRY).voxels().stream().anyMatch(v -> v.material() == Material.GOLD_BLOCK));
        assertTrue(DreamDungeonGenerator.generate(42, DungeonType.CRYSTAL_VAULT, ENTRY).voxels().stream().anyMatch(v -> v.material() == Material.AMETHYST_BLOCK));
        assertTrue(DreamDungeonGenerator.generate(42, DungeonType.NIGHTMARE_KEEP, ENTRY).voxels().stream().anyMatch(v -> v.material() == Material.POLISHED_BLACKSTONE_BRICKS));
    }

    private static int run(Map<BlockPos, Material> blocks, BlockPos p, int dx, int dz) {
        int count = 0;
        while (count < 40 && walkable(blocks, new BlockPos(p.x() + dx * count, p.y(), p.z() + dz * count))) count++;
        return count;
    }

    private static Set<BlockPos> reachable(Map<BlockPos, Material> blocks) {
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        assertTrue(walkable(blocks, ENTRY));
        seen.add(ENTRY); queue.add(ENTRY);
        while (!queue.isEmpty()) {
            var p = queue.removeFirst();
            for (var next : List.of(new BlockPos(p.x() - 1, p.y(), p.z()), new BlockPos(p.x() + 1, p.y(), p.z()),
                    new BlockPos(p.x(), p.y(), p.z() - 1), new BlockPos(p.x(), p.y(), p.z() + 1)))
                if (walkable(blocks, next) && seen.add(next)) queue.add(next);
        }
        return seen;
    }

    private static boolean walkable(Map<BlockPos, Material> blocks, BlockPos point) {
        if (blocks.getOrDefault(new BlockPos(point.x(), point.y() - 1, point.z()), Material.AIR)==Material.AIR) return false;
        for (int dy = 0; dy < 3; dy++)
            if (blocks.get(new BlockPos(point.x(), point.y() + dy, point.z())) != Material.AIR) return false;
        return true;
    }
}
