package at.vystorm.insomnia.dream.world;

import org.bukkit.Material;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static at.vystorm.insomnia.dream.world.DreamLayout.*;

/** Immutable finite world plan. No world, entity or neighbouring-chunk access during generation. */
public final class DreamWorldGenerator extends ChunkGenerator {
    public static final int FLOOR_Y = 96;
    public static final int LAYOUT_VERSION = 17;
    public static final int MIN_Y = 64, MAX_Y = 255;
    private static final int LIMIT = 256, SIZE = LIMIT * 2 + 1, LOW = 64, HIGH = 255;
    private static final Bounds WORLD_BOUNDS = new Bounds(-LIMIT, LIMIT, -LIMIT, LIMIT);
    private final DreamWorldPlan plan;
    private final DreamTerrain terrain;
    private final DreamSkyTerrain sky;
    private final int skyDensity, accentDensity, objectDensity;
    private final List<DreamObjectGenerator.Feature> objects;
    private final List<DreamDungeonPlacement.Site> dungeonSites;
    private final boolean dungeonsEnabled;
    private final boolean[] dungeonReserved = new boolean[SIZE * SIZE];
    private final int spawnFloor;
    private final long seed;
    private final int density;
    private final boolean ambientLight;
    private final short[] tops = new short[SIZE * SIZE];
    private final short[] bottoms = new short[SIZE * SIZE];
    private final byte[] biomes = new byte[SIZE * SIZE];
    private final boolean[] naturalLand = new boolean[SIZE * SIZE];
    private final boolean[] steep = new boolean[SIZE * SIZE];
    private final byte[] rockBands = new byte[SIZE * SIZE];
    /** Sparse column overlays; every column has at most HIGH-LOW+1 entries. */
    private final Material[][][] detail = new Material[1][SIZE * SIZE][];
    private final DreamLayout layout;

    public DreamWorldGenerator(long seed) {
        this(seed, 100);
    }

    public DreamWorldGenerator(long seed, int decorationDensity) {
        this(seed, decorationDensity, true);
    }

    public DreamWorldGenerator(long seed, int decorationDensity, boolean ambientLight) {
        this(seed, decorationDensity, ambientLight, 100, 65);
    }

    public DreamWorldGenerator(long seed, int decorationDensity, boolean ambientLight, int skyDensity, int accentDensity) {
        this(seed, decorationDensity, ambientLight, skyDensity, accentDensity, 65);
    }

    public DreamWorldGenerator(long seed, int decorationDensity, boolean ambientLight, int skyDensity, int accentDensity, int objectDensity) {
        this(seed, decorationDensity, ambientLight, skyDensity, accentDensity, objectDensity, true);
    }

    public DreamWorldGenerator(long seed, int decorationDensity, boolean ambientLight, int skyDensity, int accentDensity, int objectDensity, boolean dungeonsEnabled) {
        this.dungeonsEnabled = dungeonsEnabled;
        if (objectDensity < 0 || objectDensity > 100) throw new IllegalArgumentException("object density must be 0..100");
        this.objectDensity = objectDensity;
        if (skyDensity < 0 || skyDensity > 100 || accentDensity < 0 || accentDensity > 100)
            throw new IllegalArgumentException("sky/accent density must be 0..100");
        this.skyDensity = skyDensity;
        this.accentDensity = accentDensity;
        if (decorationDensity < 0 || decorationDensity > 100) throw new IllegalArgumentException("decorationDensity must be 0..100");
        this.seed = seed;
        this.density = decorationDensity;
        this.ambientLight = ambientLight;
        this.terrain = new DreamTerrain(seed);
        this.sky = new DreamSkyTerrain(seed, terrain, skyDensity);
        this.spawnFloor = terrain.surfaceY(0, 0);
        this.plan = new DreamWorldPlan(terrain);
        planTerrain();
        classifySlopes();
        dungeonSites = dungeonsEnabled ? DreamDungeonPlacement.generate(seed, new DreamDungeonPlacement.Surface() {
            public int height(int x, int z) {
                return surfaceY(x, z);
            }
            public int biome(int x, int z) {
                return terrain.biomeAt(x, z);
            }
        }) : List.of();
        reserveDungeons();
        this.layout = makeLayout();
        decorate(0);
        crystalOutcrops(0);
        naturalAccents();
        skyVegetation();
        objects = placeNaturalObjects();
        groundCover();
        if (ambientLight) illuminate(0);
        buildDungeons();
        // Only a small unobstructed arrival area; no plaza, towers or dungeon templates.
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++)
            for (int y = spawnFloor + 1; y <= spawnFloor + 3; y++) put(0, x, y, z, Material.AIR);
    }

    public DreamLayout layout() {
        return layout;
    }
    public boolean matchesConfiguration(long configuredSeed, int configuredDensity, boolean configuredLight) {
        return matchesConfiguration(configuredSeed, configuredDensity, configuredLight, 100, 65);
    }
    public boolean matchesConfiguration(long configuredSeed, int configuredDensity, boolean configuredLight, int configuredSky, int configuredAccents) {
        return matchesConfiguration(configuredSeed, configuredDensity, configuredLight, configuredSky, configuredAccents, 65);
    }
    public boolean matchesConfiguration(long configuredSeed, int configuredDensity, boolean configuredLight, int configuredSky, int configuredAccents, int configuredObjects) {
        return matchesConfiguration(configuredSeed, configuredDensity, configuredLight, configuredSky, configuredAccents, configuredObjects, true);
    }
    public boolean matchesConfiguration(long configuredSeed, int configuredDensity, boolean configuredLight, int configuredSky, int configuredAccents, int configuredObjects, boolean configuredDungeons) {
        return seed == configuredSeed && density == configuredDensity && ambientLight == configuredLight
                && skyDensity == configuredSky && accentDensity == configuredAccents && objectDensity == configuredObjects
                && dungeonsEnabled == configuredDungeons;
    }

    /** Optional static ambient light beneath vegetation; no entities or runtime scans. */
    private void illuminate(int side) {
        for (int x = -240; x <= 240; x += 7) for (int z = -240; z <= 240; z += 7) {
            int i = index(x, z), top = tops[i];
            if (!naturalLand[i] || top == 0 || reservedExpanded(x, z, 2)) continue;
            int y = top + 3;
            if (upright(side, i, y) == Material.AIR) put(side, x, y, z, Material.LIGHT);
        }
    }
    @Override public org.bukkit.generator.BiomeProvider getDefaultBiomeProvider(WorldInfo info) {
        // Material regions and Minecraft's grass/foliage tint must use the same climate field.
        List<org.bukkit.block.Biome> palette = List.of(org.bukkit.block.Biome.MEADOW,
                org.bukkit.block.Biome.DARK_FOREST, org.bukkit.block.Biome.STONY_PEAKS,
                org.bukkit.block.Biome.FLOWER_FOREST, org.bukkit.block.Biome.DRIPSTONE_CAVES);
        return new org.bukkit.generator.BiomeProvider() {
            @Override public org.bukkit.block.Biome getBiome(WorldInfo world, int x, int y, int z) {
                return palette.get(terrain.biomeAt(x, z));
            }
            @Override public List<org.bukkit.block.Biome> getBiomes(WorldInfo world) {
                return palette;
            }
        };
    }

    @Override public boolean shouldGenerateNoise() {
        return false;
    }
    @Override public boolean shouldGenerateSurface() {
        return false;
    }
    @Override public boolean shouldGenerateCaves() {
        return false;
    }
    @Override public boolean shouldGenerateDecorations() {
        return false;
    }
    @Override public boolean shouldGenerateStructures() {
        return false;
    }
    @Override public boolean shouldGenerateMobs() {
        return false;
    }

    @Override public void generateNoise(WorldInfo info, Random random, int chunkX, int chunkZ, ChunkData data) {
        for (int lx = 0; lx < 16; lx++) for (int lz = 0; lz < 16; lz++) {
            int x = (chunkX<<4) + lx, z = (chunkZ<<4) + lz;
            if (!inside(x, z)) continue;
            for (int y = LOW; y <= HIGH; y++) {
                Material block = upright(0, index(x, z), y);
                if (block != Material.AIR && (info == null || y >= info.getMinHeight() && y < info.getMaxHeight()))
                    writeBlock(data, lx, y, lz, block, x, z);
            }
        }
    }

    /** Deterministic single-world voxel query. No mirrored or offset representation. */
    public Material blockAt(int x, int y, int z) {
        return inside(x, z) && y >= LOW && y <= HIGH ? upright(0, index(x, z), y) : Material.AIR;
    }

    public int surfaceY(int x, int z) {
        return inside(x, z) && tops[index(x, z)] != 0 ? tops[index(x, z)] : Integer.MIN_VALUE;
    }

    @Override public int getBaseHeight(WorldInfo info, Random random, int x, int z, org.bukkit.HeightMap heightMap) {
        for (int y = HIGH; y >= LOW; y--) if (blockAt(x, y, z) != Material.AIR) return y + 1;
        return info.getMinHeight();
    }

    private Material upright(int side, int index, int y) {
        if (y < LOW || y > HIGH) return Material.AIR;
        Material[] column = detail[side][index];
        if (column != null && column[y - LOW] != null) return column[y - LOW];
        int x = index / SIZE - LIMIT, z = index % SIZE - LIMIT;
        if (sky.solid(x, y, z)) {
            int biome = terrain.biomeAt(x, z), skyTop = sky.surfaceY(x, z);
            if (y == skyTop) return switch (biome) {
                case 1 -> Material.MOSS_BLOCK;
                case 2 -> Material.CALCITE;
                case 4 -> Material.DEEPSLATE;
                default -> Material.GRASS_BLOCK;
            };
            if (y >= skyTop - 2 && biome != 2 && biome != 4) return Material.DIRT;
            return switch (biome) {
                case 1 -> Material.TUFF;
                case 4 -> Material.BLACKSTONE;
                default -> Material.CALCITE;
            };
        }
        int top = tops[index];
        if (top == 0 || y > top || y < bottoms[index]) return Material.AIR;
        if (naturalLand[index]) {
            int originalTop = terrain.surfaceY(x, z);
            int sourceY = y - (top - originalTop);
            // Preserve the 3D field, including gaps and overhangs, when blending a local landing.
            // Only the top two blocks of the small arrival clearing are reinforced.
            if (!terrain.solid(x, sourceY, z) && !(y >= top - 1 && reserved(x, z)))
                return Material.AIR;
        }
        int biome = biomes[index];
        // A narrow mottled seam softens climate boundaries without scattering whole biomes.
        if (y == top) {
            int bx = x + Math.floorMod(hash(x, z, 73), 7) - 3, bz = z + Math.floorMod(hash(x, z, 74), 7) - 3;
            if (inside(bx, bz)) biome = terrain.biomeAt(bx, bz);
        }
        if (y == top && steep[index] && !reserved(x, z)) return switch (biome) {
            case 1 -> Material.TUFF;
            case 4 -> Material.DEEPSLATE;
            default -> Material.CALCITE;
        };
        if (y == top) return switch (biome) {
            case 1 -> Material.MOSS_BLOCK;
            case 2 -> Material.CALCITE;
            case 3 -> Material.GRASS_BLOCK;
            case 4 -> Material.DEEPSLATE;
            default -> Material.GRASS_BLOCK;
        };
        if (y >= top - 1 && biome != 2 && biome != 4) return Material.DIRT;
        int band = Math.floorMod(y + rockBands[index], 13);
        if (biome == 4) return band < 3 ? Material.BLACKSTONE : Material.DEEPSLATE;
        if (biome == 1) return band < 4 ? Material.MOSSY_COBBLESTONE : Material.TUFF;
        return band < 2 ? Material.DIORITE : Material.CALCITE;
    }

    private void planTerrain() {
        for (int x = -LIMIT; x <= LIMIT; x++) for (int z = -LIMIT; z <= LIMIT; z++) {
            int naturalTop = terrain.surfaceY(x, z);
            if (naturalTop == Integer.MIN_VALUE) continue;
            int i = index(x, z), top = naturalTop;
            // Blend only the five-by-five arrival clearing into its natural shelf.
            int distance = Math.max(Math.abs(x), Math.abs(z));
            if (distance < 8) {
                int rise = Math.max(0, distance - 2);
                top = Math.max(spawnFloor - rise, Math.min(spawnFloor + rise, top));
            }
            tops[i] = (short)top;
            naturalLand[i] = true;
            bottoms[i] = (short)Math.max(LOW, terrain.bottomY(x, z) + top - naturalTop);
            biomes[i] = (byte)terrain.biomeAt(x, z);
            rockBands[i] = (byte)Math.round(3 * wave(x, z, 29) + 2 * wave(x, z, 11));
        }
    }

    private void classifySlopes() {
        int[] neighbours ={3 * SIZE, -3 * SIZE, 3, -3};
        for (int x = -LIMIT + 3; x <= LIMIT - 3; x++) for (int z = -LIMIT + 3; z <= LIMIT - 3; z++) {
            int i = index(x, z), top = tops[i];
            if (top == 0) continue;
            for (int offset : neighbours) {
                int next = tops[i + offset];
                if (next != 0 && Math.abs(next - top) >= 4) {
                    steep[i] = true;
                    break;
                }
            }
        }
    }

    private void reserveDungeons() {
        for (var site : dungeonSites) {
            Bounds b = site.plan().dungeon().bounds();
            for (int x = b.minX() - 2; x <= b.maxX() + 2; x++) for (int z = b.minZ() - 2; z <= b.maxZ() + 2; z++)
                if (inside(x, z)) dungeonReserved[index(x, z)] = true;
            for (var p : site.trail()) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
                if (inside(p.x() + dx, p.z() + dz)) dungeonReserved[index(p.x() + dx, p.z() + dz)] = true;
        }
    }

    private void buildDungeons() {
        for (var site : dungeonSites) {
            var dungeon = site.plan().dungeon();
            int floor = dungeon.entry().y() - 1;
            for (var v : site.plan().voxels()) if (v.y() == floor) {
                int natural = surfaceY(v.x(), v.z());
                for (int y = Math.min(natural, floor - 3); y < floor; y++) put(0, v.x(), y, v.z(), Material.TUFF);
                for (int y = floor + 1; y <= Math.max(natural + 2, floor + 9); y++) put(0, v.x(), y, v.z(), Material.AIR);
            }
            for (var v : site.plan().voxels()) put(0, v.x(), v.y(), v.z(), v.material());
            // Natural one-block steps remain; clear only the surveyed walking line.
            for (var p : site.trail()) {
                if (!reservedExpanded(p.x(), p.z(), 10)) put(0, p.x(), p.y() - 1, p.z(), Material.ROOTED_DIRT);
                for (int y = p.y(); y <= p.y() + 2; y++) put(0, p.x(), y, p.z(), Material.AIR);
            }
        }
    }
    List<DreamDungeonPlacement.Site> dungeonSites() {
        return dungeonSites;
    }

    private void decorate(int side) {
        // Ground cover follows biome and broad vegetation patches, with open meadows between woods.
        for (int x = -LIMIT; x <= LIMIT; x++) for (int z = -LIMIT; z <= LIMIT; z++) {
            int i = index(x, z), y = tops[i];
            if (y == 0 || steep[i] || reservedExpanded(x, z, 3) || density == 0) continue;
            double meadow = wave(x, z, 10) + .6 * wave(x, z, 23);
            int pick = Math.floorMod(hash(x, z, 44), 100);
            if (pick >= density * .72 || biomes[i] == 2 || biomes[i] == 4 || meadow < -.15) continue;
            put(side, x, y, z, meadow > .5 ? Material.MOSS_BLOCK : Material.GRASS_BLOCK);
            if (pick < density * .44) put(side, x, y + 1, z, meadow > .45 ? pick % 4 == 0 ? Material.PINK_TULIP : Material.SHORT_GRASS : pick % 8 == 0 ? Material.DANDELION : Material.SHORT_GRASS);
        }
        // Deterministic feature lattice avoids unbounded placement attempts and chunk seams.
        for (int x = -226; x <= 226; x += 14) for (int z = -226; z <= 226; z += 14) {
            int px = x + Math.floorMod(hash(x, z, 1), 7), pz = z + Math.floorMod(hash(x, z, 2), 7);
            if (!inside(px, pz) || tops[index(px, pz)] == 0 || Math.floorMod(hash(x, z, 3), 100) >= density) continue;
            if (reservedExpanded(px, pz, 6) || wave(px, pz, 24) < -.15) continue;
            boolean safe = true;
            int y = tops[index(px, pz)];
            for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) if (tops[index(px + dx, pz + dz)] == 0 || Math.abs(tops[index(px + dx, pz + dz)] - y) > 4) safe = false;
            if (!safe) continue;
            switch (biomes[index(px, pz)]) {
                case 0 -> {
                    if (wave(px, pz, 42) > .18) tree(side, px, y + 1, pz, Material.CHERRY_LOG, Material.CHERRY_LEAVES);
                }
                case 1 -> tree(side, px, y + 1, pz, Material.DARK_OAK_LOG, Material.DARK_OAK_LEAVES);
                case 2 -> {
                    if (Math.floorMod(hash(x, z, 4), 3) == 0) crystal(side, px, y + 1, pz, Material.AMETHYST_BLOCK, 3 + Math.floorMod(hash(x, z, 4), 4));
                }
                case 3 -> tree(side, px, y + 1, pz, Material.BIRCH_LOG, Material.AZALEA_LEAVES);
                case 4 -> {
                    if (Math.floorMod(hash(x, z, 9), 3) == 0) crystal(side, px, y + 1, pz, Material.BASALT, 3);
                }
                default -> throw new IllegalStateException();
            }
        }
    }

    private void crystalOutcrops(int side) {
        for (int x = -222; x <= 222; x += 9) for (int z = -222; z <= 222; z += 9) {
            int i = index(x, z), biome = biomes[i], y = tops[i];
            if (y == 0 || !naturalLand[i] || (biome != 2 && biome != 4) || reservedExpanded(x, z, 3)) continue;
            if (Math.floorMod(hash(x, z, 91), 100) >= density * (biome == 2 ? .28 : .10)) continue;
            boolean supported = true;
            for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++)
                if (tops[index(x + dx, z + dz)] == 0) supported = false;
            if (!supported) continue;
            crystal(side, x, y + 1, z, biome == 2 ? Material.AMETHYST_BLOCK : Material.BASALT,
                    biome == 2 ? 2 + Math.floorMod(hash(x, z, 92), 4) : 2 + Math.floorMod(hash(x, z, 93), 3));
        }
    }

    /** Validate an entire generated object before writing any block; collisions never leave half an object. */
    private List<DreamObjectGenerator.Feature> placeNaturalObjects() {
        var candidates = DreamObjectGenerator.generate(seed, objectDensity, new DreamObjectGenerator.Surface() {
            public int height(int x, int z, boolean upper) {
                return upper ? sky.surfaceY(x, z) : surfaceY(x, z);
            }
            public int biome(int x, int z) {
                return terrain.biomeAt(x, z);
            }
        });
        List<DreamObjectGenerator.Feature> placed = new ArrayList<>();
        for (var candidate : candidates) {
            boolean clear = !candidate.voxels().isEmpty();
            for (var v : candidate.voxels()) {
                if (!inside(v.x(), v.z()) || v.y() < LOW || v.y() > HIGH || reservedExpanded(v.x(), v.z(), 12)
                        || dungeonReserved[index(v.x(), v.z())] || !objectReplaceable(blockAt(v.x(), v.y(), v.z()))) {
                            clear = false;
                            break;
                        }
            }
            if (!clear) continue;
            for (var v : candidate.voxels()) put(0, v.x(), v.y(), v.z(), v.material());
            placed.add(candidate);
        }
        return List.copyOf(placed);
    }
    List<DreamObjectGenerator.Feature> objects() {
        return objects;
    }

    /** Final surface dressing cannot overwrite trees, objects or supported terrain geometry. */
    private void groundCover() {
        if (accentDensity == 0) return;
        for (int x = -244; x <= 244; x++) for (int z = -244; z <= 244; z++) {
            if (reservedExpanded(x, z, 10) || dungeonReserved[index(x, z)]) continue;
            int biome = terrain.biomeAt(x, z);
            dressSurface(x, z, false, biome);
            dressSurface(x, z, true, biome);
        }
    }

    private void dressSurface(int x, int z, boolean upper, int biome) {
        int top = upper ? sky.surfaceY(x, z) : surfaceY(x, z);
        if (top == Integer.MIN_VALUE || top >= HIGH - 1) return;
        int slope = 0;
        for (int direction = 0; direction < 4; direction++) {
            int nx = x + (direction == 0 ? -1 : direction == 1 ? 1 : 0);
            int nz = z + (direction == 2 ? -1 : direction == 3 ? 1 : 0);
            int next = upper ? sky.surfaceY(nx, nz) : surfaceY(nx, nz);
            if (next == Integer.MIN_VALUE) return;
            slope = Math.max(slope, Math.abs(next - top));
        }
        Material above = blockAt(x, top + 1, z);
        if (!objectReplaceable(above)) return;
        Material ground = DreamGroundCover.ground(seed, x, z, biome, slope, accentDensity);
        // Do not exchange a planted surface for rock: existing flowers need soil.
        if (ground != null && (above == Material.AIR || above == Material.LIGHT || plantSoil(ground)))
            put(0, x, top, z, ground);
        Material cover = DreamGroundCover.cover(seed, x, z, biome, slope, accentDensity);
        if (cover != null && plantSoil(blockAt(x, top, z))) put(0, x, top + 1, z, cover);
    }

    private static boolean plantSoil(Material material) {
        return switch (material) {
            case GRASS_BLOCK, DIRT, COARSE_DIRT, ROOTED_DIRT, MOSS_BLOCK -> true;
            default -> false;
        };
    }
    private static boolean objectReplaceable(Material material) {
        return switch (material) {
            case AIR, LIGHT, SHORT_GRASS, TALL_GRASS, PINK_TULIP, DANDELION, RED_MUSHROOM, BROWN_MUSHROOM,
                    ALLIUM, BLUE_ORCHID, WHITE_TULIP, AZURE_BLUET -> true;
            default -> false;
        };
    }

    /** Noise masks form irregular patches and ridges; there are no placed landmark templates. */
    private void naturalAccents() {
        if (accentDensity == 0) return;
        double threshold = .60 - accentDensity * .003;
        for (int x = -244; x <= 244; x++) for (int z = -244; z <= 244; z++) {
            if (reservedExpanded(x, z, 10)) continue;
            int biome = terrain.biomeAt(x, z);
            double patch = DreamTerrain.fractal(seed + 811, x / 19.0, z / 19.0);
            if (patch > threshold) {
                accentSurface(x, z, surfaceY(x, z), biome, patch - threshold);
                accentSurface(x, z, sky.surfaceY(x, z), biome, patch - threshold);
            }
            int bottom = sky.bottomY(x, z);
            if (bottom == Integer.MIN_VALUE || (biome != 0 && biome != 1 && biome != 3)) continue;
            double roots = DreamTerrain.fractal(seed + 812, x / 8.0, z / 8.0);
            if (roots < threshold || Math.floorMod(hash(x, z, 813), 5) != 0) continue;
            int length = 2 + (int)Math.min(9, (roots - threshold) * 22);
            for (int y = bottom - 1; y >= bottom - length; y--) {
                if (y <= surfaceY(x, z) + 8 || blockAt(x, y, z) != Material.AIR) break;
                put(0, x, y, z, y == bottom - length ? Material.HANGING_ROOTS : Material.MANGROVE_ROOTS);
            }
        }
    }

    private void accentSurface(int x, int z, int top, int biome, double strength) {
        if (top == Integer.MIN_VALUE || top >= HIGH - 12) return;
        if (biome == 2 || biome == 4) {
            // Continuous noise crests become variable mineral ridges, not repeated crystal models.
            int height = (int)Math.min(8, strength * 22);
            for (int dy = 1; dy <= height; dy++) {
                if (blockAt(x, top + dy, z) != Material.AIR) break;
                put(0, x, top + dy, z, biome == 2 ? Material.AMETHYST_BLOCK : Material.BASALT);
            }
        } else if (Math.floorMod(hash(x, z, 814), 100) < accentDensity * .55 && blockAt(x, top + 1, z) == Material.AIR) {
            Material flower = biome == 1 ? Material.RED_MUSHROOM
                    : biome == 3 ? (strength > .18 ? Material.ALLIUM : Material.BLUE_ORCHID)
                    : (strength > .18 ? Material.WHITE_TULIP : Material.AZURE_BLUET);
            put(0, x, top + 1, z, flower);
        }
    }

    /** Trees grow on supported upper surfaces; locations are seed-selected, never authored anchors. */
    private void skyVegetation() {
        if (density == 0) return;
        for (int x = -235; x <= 235; x++) for (int z = -235; z <= 235; z++) {
            int y = sky.surfaceY(x, z);
            if (y == Integer.MIN_VALUE) continue;
            int biome = terrain.biomeAt(x, z);
            if (biome == 2 || biome == 4 || Math.floorMod(hash(x, z, 821), 10000) >= density * .13) continue;
            if (DreamTerrain.fractal(seed + 822, x / 28.0, z / 28.0) < -.1) continue;
            boolean supported = true;
            for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
                int neighbour = sky.surfaceY(x + dx, z + dz);
                if (neighbour == Integer.MIN_VALUE || Math.abs(neighbour - y) > 3) supported = false;
            }
            if (!supported) continue;
            tree(0, x, y + 1, z, biome == 1 ? Material.DARK_OAK_LOG : biome == 3 ? Material.BIRCH_LOG : Material.CHERRY_LOG,
                    biome == 1 ? Material.DARK_OAK_LEAVES : biome == 3 ? Material.AZALEA_LEAVES : Material.CHERRY_LEAVES);
        }
    }

    private void tree(int side, int x, int y, int z, Material trunk, Material leaves) {
        int h = 7 + Math.floorMod(hash(x, z, 17), 5), spread = 4 + Math.floorMod(hash(x, z, 18), 3);
        int leanX = Math.floorMod(hash(x, z, 19), 3) - 1, leanZ = Math.floorMod(hash(x, z, 20), 3) - 1;
        for (int dy = 0; dy < h; dy++) {
            int xx = x + leanX * dy / 4, zz = z + leanZ * dy / 5;
            feature(side, xx, y + dy, zz, trunk);
            if (dy < 3 && h >= 10) feature(side, xx + 1, y + dy, zz, trunk);
        }
        // Short roots keep the leaning trunk attached to uneven natural ground.
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (Math.abs(dx) + Math.abs(dz) > 1) continue;
            int upperGround = sky.surfaceY(x + dx, z + dz);
            int ground = upperGround != Integer.MIN_VALUE && upperGround < y ? upperGround : surfaceY(x + dx, z + dz);
            if (ground == Integer.MIN_VALUE || y - ground > 5) continue;
            for (int yy = ground + 1; yy <= y; yy++) feature(side, x + dx, yy, z + dz, trunk);
        }
        // Four individually curved limbs carry overlapping broad, flattened leaf masses.
        for (int branch = 0; branch < 4; branch++) {
            double angle = branch * Math.PI / 2 + (hash(x, z, 21) & 15) * .12;
            int endX = x + (int)Math.round(Math.cos(angle) * spread), endZ = z + (int)Math.round(Math.sin(angle) * spread);
            int endY = y + h - 1 + (branch % 2);
            for (int step = 0; step <= 7; step++) {
                double t = step / 7.;
                int xx = (int)Math.round(x + (endX - x) * t), zz = (int)Math.round(z + (endZ - z) * t);
                int yy = y + h - 5 + (int)Math.round(t * 4);
                feature(side, xx, yy, zz, trunk);
            }
            canopy(side, endX, endY, endZ, 4 + branch % 2, 2 + branch % 2, leaves);
        }
        canopy(side, x + leanX, y + h + 1, z + leanZ, spread, 3, leaves);
        feature(side, x + 2, y + h - 3, z, Material.SHROOMLIGHT);
    }
    private void canopy(int side, int x, int y, int z, int radius, int vertical, Material leaves) {
        for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) for (int dy = -vertical; dy <= vertical; dy++) {
            double shape = (dx * dx + dz * dz) / (double)(radius * radius) + dy * dy / (double)(vertical * vertical);
            if (shape > 1.05 + .14 * wave(x + dx, z + dz, 4)) continue;
            int xx = x + dx, zz = z + dz, yy = y + dy;
            if (!inside(xx, zz) || yy < LOW || yy > HIGH) continue;
            Material existing = upright(side, index(xx, zz), yy);
            if (existing.name().endsWith("_LOG")) continue;
            feature(side, xx, yy, zz, leaves);
        }
    }
    private void feature(int side, int x, int y, int z, Material material) {
        // Low limbs never obstruct the small arrival clearing.
        if (!inside(x, z) || dungeonReserved[index(x, z)] || y <= surfaceY(x, z) || sky.solid(x, y, z) || (reserved(x, z) && y <= surfaceY(x, z) + 4)) return;
        put(side, x, y, z, material);
    }
    private void crystal(int side, int x, int y, int z, Material material, int height) {
        // Low asymmetric mineral clusters, not repeated obelisks or lantern pedestals.
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            int h = height - (int)Math.ceil(Math.hypot(dx, dz)) + Math.floorMod(hash(x + dx, z + dz, 61), 2);
            int ground = surfaceY(x + dx, z + dz);
            if (ground == Integer.MIN_VALUE || Math.abs(ground + 1 - y) > 3) continue;
            for (int dy = 0; dy < h; dy++) feature(side, x + dx, ground + 1 + dy, z + dz, material);
        }
    }
    private DreamLayout makeLayout() {
        List<BiomeArea> areas = new ArrayList<>();
        for (DreamWorldPlan.BiomeSite area : plan.biomes) {
            DreamWorldPlan.Point p = area.point();
            areas.add(new BiomeArea("biome-" + area.biome(), DreamBiome.values()[area.biome()], 0,
                    new BlockPos(p.x(), surfaceY(p.x(), p.z()) + 1, p.z()), 48));
        }
        return new DreamLayout(new BlockPos(0, spawnFloor + 1, 0), dungeonSites.stream().map(s -> s.plan().dungeon()).toList(), WORLD_BOUNDS,
                "dream-landscape-v" + LAYOUT_VERSION + "-" + Long.toUnsignedString(seed, 16) + "-d" + density + "-l" + (ambientLight ? 1 : 0) + "-s" + skyDensity + "-a" + accentDensity + "-o" + objectDensity + "-u" + (dungeonsEnabled ? 1 : 0), List.of(), areas);
    }
    private void put(int side, int x, int y, int z, Material material) {
        if (!inside(x, z) || y < LOW || y > HIGH) return;
        int index = index(x, z);
        if (detail[side][index] == null) detail[side][index] = new Material[HIGH - LOW + 1];
        detail[side][index][y - LOW] = material;
    }
    private void writeBlock(ChunkData data, int x, int y, int z, Material material, int worldX, int worldZ) {
        data.setBlock(x, y, z, material);
        if (material == Material.OAK_WOOD || material == Material.DARK_OAK_WOOD || material == Material.BIRCH_WOOD) {
            if (data.getBlockData(x, y, z) instanceof org.bukkit.block.data.Orientable wood) {
                int alongX = (blockAt(worldX - 1, y, worldZ) == material ? 1 : 0) + (blockAt(worldX + 1, y, worldZ) == material ? 1 : 0);
                int alongZ = (blockAt(worldX, y, worldZ - 1) == material ? 1 : 0) + (blockAt(worldX, y, worldZ + 1) == material ? 1 : 0);
                wood.setAxis(alongX >= alongZ ? org.bukkit.Axis.X : org.bukkit.Axis.Z);
                data.setBlock(x, y, z, wood);
            }
        }
        if (material == Material.CHERRY_LEAVES || material == Material.DARK_OAK_LEAVES || material == Material.AZALEA_LEAVES) {
            if (data.getBlockData(x, y, z) instanceof Leaves leaves) {
                leaves.setPersistent(true);
                data.setBlock(x, y, z, leaves);
            }
        }
    }
    private static boolean reserved(int x, int z) {
        return reservedExpanded(x, z, 0);
    }
    private static boolean reservedExpanded(int x, int z, int pad) {
        return Math.abs(x) <= 2 + pad && Math.abs(z) <= 2 + pad;
    }
    private double wave(int x, int z, int scale) {
        double phase = (mix(seed + scale) & 65535) / 10430.0;
        return (Math.sin(x / (double)scale + phase) + Math.cos(z / (scale * .83) - phase) + .5 * Math.sin((x + z) / (scale * .6) + phase)) / 2.5;
    }
    private int hash(int x, int z, int salt) {
        return (int)mix(seed ^ ((long)x<<32) ^ (z & 0xffffffffL) ^ salt * 0x9e3779b97f4a7c15L);
    }
    private static int index(int x, int z) {
        return (x + LIMIT) * SIZE + z + LIMIT;
    }
    private static boolean inside(int x, int z) {
        return Math.abs((long)x) <= LIMIT && Math.abs((long)z) <= LIMIT;
    }
    private static long mix(long v) {
        v = (v ^ (v>>>33)) * 0xff51afd7ed558ccdL;
        v = (v ^ (v>>>33)) * 0xc4ceb9fe1a85ec53L;
        return v ^ (v>>>33);
    }
}
