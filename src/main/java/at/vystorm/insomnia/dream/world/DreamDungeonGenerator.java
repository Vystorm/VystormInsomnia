package at.vystorm.insomnia.dream.world;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

import static at.vystorm.insomnia.dream.world.DreamLayout.*;

/** Pure seeded dungeon geometry. Coordinates identify feet; foundation and approaches belong to terrain generation. */
final class DreamDungeonGenerator {
    private DreamDungeonGenerator() {}

    record Voxel(int x, int y, int z, Material material) {}
    record Plan(Dungeon dungeon, List<Voxel> voxels, BlockPos approach) {
        Plan {
            Objects.requireNonNull(dungeon);
            voxels = List.copyOf(voxels);
        }
    }
    private record Cell(int x, int z) {}

    static Plan generate(long seed, DungeonType type, BlockPos entry) {
        Objects.requireNonNull(type);
        Objects.requireNonNull(entry);
        Random random = new Random(seed ^ (0x632be59bd9b4e019L * (type.ordinal() + 1)));
        int rotation = random.nextInt(4);
        Set<Cell> walk = new LinkedHashSet<>();
        List<Cell> anchors = new ArrayList<>();
        Cell reward;
        Cell encounter;
        if (type == DungeonType.NIGHTMARE_KEEP) {
            int halfWidth = 6 + random.nextInt(3), end = 21 + random.nextInt(4);
            rectangle(walk, -1, 1, 0, 7);
            rectangle(walk, -halfWidth, halfWidth, 6, end);
            // Asymmetric side chapels, joined by broad mouths to the central arena.
            for (int side : new int[]{-1, 1}) {
                int z = 9 + random.nextInt(7);
                int outer = halfWidth + 1 + random.nextInt(2);
                rectangle(walk, side < 0 ? -outer : halfWidth, side < 0 ? -halfWidth : outer, z, z + 3);
            }
            anchors.add(new Cell(-halfWidth + 2, 10));
            anchors.add(new Cell(halfWidth - 2, 12));
            anchors.add(new Cell(0, end - 3));
            encounter = new Cell(0, (6 + end) / 2);
            reward = new Cell(0, end - 1);
        } else {
            int count = type == DungeonType.CLOUD_RUINS ? 4 : 3 + random.nextInt(3);
            int cursor = 3;
            rectangle(walk, -1, 1, 0, 3);
            List<Cell> centers = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int length = type == DungeonType.CLOUD_RUINS ? 4 + random.nextInt(2)
                        : (count == 3 ? 5 + random.nextInt(2) : 3 + random.nextInt(2));
                int centerX = random.nextInt(5) - 2;
                int halfWidth = 3 + random.nextInt(3);
                rectangle(walk, centerX - halfWidth, centerX + halfWidth, cursor, cursor + length - 1);
                centers.add(new Cell(centerX, cursor + length / 2));
                // The uninterrupted central spine guarantees three-wide passages between offset rooms.
                rectangle(walk, -1, 1, cursor - 1, cursor + length);
                if (random.nextBoolean()) {
                    int side = random.nextBoolean() ? 1 : -1;
                    int edge = centerX + side * halfWidth;
                    rectangle(walk, Math.min(edge, edge + side * 2), Math.max(edge, edge + side * 2),
                            cursor + 1, cursor + Math.min(3, length - 1));
                }
                cursor += length + 1;
            }
            if (type == DungeonType.CLOUD_RUINS) anchors.addAll(centers);
            else {
                int last = cursor - 3;
                for (int i = 1; i <= 4; i++) anchors.add(new Cell(0, i * last / 5));
            }
            reward = new Cell(0, cursor - 2);
            encounter = centers.get(centers.size() / 2);
        }

        Set<Cell> floor = new LinkedHashSet<>(walk);
        // A one-block perimeter carries every wall, including the corners; no unsupported decoration.
        for (Cell cell : walk) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
            floor.add(new Cell(cell.x + dx, cell.z + dz));
        Map<BlockPos, Material> blocks = new LinkedHashMap<>();
        int clearHeight = type == DungeonType.CRYSTAL_VAULT ? 7 : 6;
        for (Cell cell : floor) {
            Material floorMaterial = floorMaterial(type, random);
            put(blocks, entry, rotation, cell, -1, floorMaterial);
            for (int y = 0; y < clearHeight; y++) put(blocks, entry, rotation, cell, y, Material.AIR);
            if (!walk.contains(cell) && !(cell.z == -1 && Math.abs(cell.x) <= 1)) {
                int height = switch (type) {
                    case CLOUD_RUINS -> random.nextInt(5); // broken open courtyards, never broken walking floors
                    case CRYSTAL_VAULT -> 3 + random.nextInt(3);
                    case NIGHTMARE_KEEP -> 4 + random.nextInt(3);
                };
                for (int y = 0; y < height; y++) {
                    Material wall = switch (type) {
                        case CLOUD_RUINS -> random.nextInt(4) == 0 ? Material.CALCITE : Material.MOSSY_STONE_BRICKS;
                        case CRYSTAL_VAULT -> (cell.z % 3 == 0 || y == height - 1) ? Material.AMETHYST_BLOCK : Material.CALCITE;
                        case NIGHTMARE_KEEP -> y == height - 1 ? Material.CHISELED_POLISHED_BLACKSTONE : Material.POLISHED_BLACKSTONE_BRICKS;
                    };
                    put(blocks, entry, rotation, cell, y, wall);
                }
            }
        }
        if (type == DungeonType.CRYSTAL_VAULT) {
            // High ribs follow each generated chamber cross-section; no copied roof template.
            for (int z = 4; z <= 28; z += 4 + random.nextInt(2)) {
                int left = 100, right = -100;
                for (Cell cell : walk) if (cell.z == z) {
                    left = Math.min(left, cell.x);
                    right = Math.max(right, cell.x);
                }
                if (right - left < 6) continue;
                for (int x = left; x <= right; x++) {
                    int edge = Math.min(x - left, right - x);
                    put(blocks, entry, rotation, new Cell(x, z), Math.min(7, 5 + edge),
                            x == (left + right) / 2 ? Material.GLOWSTONE : Material.AMETHYST_BLOCK);
                }
            }
        }
        if (type == DungeonType.CLOUD_RUINS) put(blocks, entry, rotation, reward, -1, Material.GOLD_BLOCK);
        List<Voxel> voxels = blocks.entrySet().stream().map(e -> new Voxel(e.getKey().x(), e.getKey().y(), e.getKey().z(), e.getValue())).toList();
        int minX = voxels.stream().mapToInt(Voxel::x).min().orElseThrow();
        int maxX = voxels.stream().mapToInt(Voxel::x).max().orElseThrow();
        int minZ = voxels.stream().mapToInt(Voxel::z).min().orElseThrow();
        int maxZ = voxels.stream().mapToInt(Voxel::z).max().orElseThrow();
        String id = switch (type) {
            case CLOUD_RUINS -> "cloud-ruins";
            case CRYSTAL_VAULT -> "crystal-vault";
            case NIGHTMARE_KEEP -> "nightmare-keep";
        };
        Dungeon dungeon = new Dungeon(id, type, entry, anchors.stream().map(c -> world(entry, rotation, c, 0)).toList(),
                world(entry, rotation, reward, 0), world(entry, rotation, encounter, 0), new Bounds(minX, maxX, minZ, maxZ), 0);
        return new Plan(dungeon, voxels, world(entry, rotation, new Cell(0, -2), 0));
    }

    private static Material floorMaterial(DungeonType type, Random random) {
        return switch (type) {
            case CLOUD_RUINS -> random.nextInt(5) == 0 ? Material.CALCITE : Material.MOSSY_STONE_BRICKS;
            case CRYSTAL_VAULT -> random.nextInt(4) == 0 ? Material.CALCITE : Material.STONE_BRICKS;
            case NIGHTMARE_KEEP -> random.nextInt(4) == 0 ? Material.CRACKED_POLISHED_BLACKSTONE_BRICKS : Material.POLISHED_BLACKSTONE_BRICKS;
        };
    }

    private static void rectangle(Set<Cell> cells, int minX, int maxX, int minZ, int maxZ) {
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) cells.add(new Cell(x, z));
    }

    private static void put(Map<BlockPos, Material> blocks, BlockPos entry, int rotation, Cell cell, int y, Material material) {
        blocks.put(world(entry, rotation, cell, y), material);
    }

    private static BlockPos world(BlockPos entry, int rotation, Cell cell, int y) {
        int x = switch (rotation) {
            case 0 -> cell.x;
            case 1 -> -cell.z;
            case 2 -> -cell.x;
            default -> cell.z;
        };
        int z = switch (rotation) {
            case 0 -> cell.z;
            case 1 -> cell.x;
            case 2 -> -cell.z;
            default -> -cell.x;
        };
        return new BlockPos(entry.x() + x, entry.y() + y, entry.z() + z);
    }
}
