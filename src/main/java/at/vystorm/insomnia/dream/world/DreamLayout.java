package at.vystorm.insomnia.dream.world;

import java.util.List;
import java.util.Objects;

/** Immutable, seed-specific coordinates and interaction markers for a dream world. */
public record DreamLayout(BlockPos safeSpawn, List<Dungeon> dungeons, Bounds bounds, String layoutHash,
                          List<Tower> towers, List<BiomeArea> biomes) {
    public DreamLayout {
        Objects.requireNonNull(safeSpawn, "safeSpawn");
        dungeons = List.copyOf(dungeons);
        Objects.requireNonNull(bounds, "bounds");
        Objects.requireNonNull(layoutHash, "layoutHash");
        towers = List.copyOf(towers);
        biomes = List.copyOf(biomes);
    }

    public DreamLayout(BlockPos safeSpawn, List<Dungeon> dungeons, Bounds bounds, String layoutHash) {
        this(safeSpawn, dungeons, bounds, layoutHash, List.of(), List.of());
    }

    /** Integer coordinate; spawn/dungeon/arrival anchors identify feet above a solid floor. */
    public record BlockPos(int x, int y, int z) {}

    /** Inclusive horizontal generation bounds; all generated geometry is within these limits. */
    public record Bounds(int minX, int maxX, int minZ, int maxZ) {
        public boolean contains(int x, int z) { return x >= minX && x <= maxX && z >= minZ && z <= maxZ; }
    }

    public enum DungeonType { CLOUD_RUINS, CRYSTAL_VAULT, NIGHTMARE_KEEP }

    public enum DreamBiome { CLOUD_MEADOWS, MIRROR_GROVE, CRYSTAL_SEA, TWILIGHT_GARDENS, NIGHT_FRACTURE }

    /** Button is the clicked block itself; arrival and destination are safe feet coordinates. */
    public record Tower(String id, int side, BlockPos button, BlockPos arrival, BlockPos destination) {
        public Tower {
            Objects.requireNonNull(id); Objects.requireNonNull(button);
            Objects.requireNonNull(arrival); Objects.requireNonNull(destination);
            if (side != 0 && side != 1) throw new IllegalArgumentException("side must be 0 or 1");
        }
    }

    public record BiomeArea(String id, DreamBiome biome, int side, BlockPos center, int radius) {
        public BiomeArea {
            Objects.requireNonNull(id); Objects.requireNonNull(biome); Objects.requireNonNull(center);
            if (radius <= 0 || (side != 0 && side != 1)) throw new IllegalArgumentException("Invalid biome area");
        }
    }

    /** Stable dungeon metadata used by runtime systems to place encounters, puzzles and rewards. */
    public record Dungeon(String id, DungeonType type, BlockPos entry,
                          List<BlockPos> puzzleAnchors, BlockPos reward,
                          BlockPos encounterSpawn, Bounds bounds, int side) {
        public Dungeon(String id, DungeonType type, BlockPos entry, List<BlockPos> puzzleAnchors,
                       BlockPos reward, BlockPos encounterSpawn, Bounds bounds) {
            this(id, type, entry, puzzleAnchors, reward, encounterSpawn, bounds, 0);
        }
        public Dungeon {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(entry, "entry");
            puzzleAnchors = List.copyOf(puzzleAnchors);
            Objects.requireNonNull(reward, "reward");
            Objects.requireNonNull(encounterSpawn, "encounterSpawn");
            Objects.requireNonNull(bounds, "bounds");
            if (side != 0 && side != 1) throw new IllegalArgumentException("side must be 0 or 1");
        }
    }
}
