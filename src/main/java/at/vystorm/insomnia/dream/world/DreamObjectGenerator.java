package at.vystorm.insomnia.dream.world;

import org.bukkit.Material;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/** Pure, bounded voxel synthesis; the world generator owns collision checks and placement. */
final class DreamObjectGenerator {
    interface Surface {
        int height(int x, int z, boolean upper);
        int biome(int x, int z);
    }

    record Voxel(int x, int y, int z, Material material) {}
    record Feature(String kind, int x, int y, int z, boolean upper, List<Voxel> voxels) {
        Feature { voxels = List.copyOf(voxels); }
    }
    private record Point(int x, int y, int z) {}
    private record Column(int x, int z) {}

    private DreamObjectGenerator() {}

    static List<Feature> generate(long seed, int density, Surface surface) {
        if (density <= 0) return List.of();
        Random random = new Random(seed ^ 0x672f4eab922cb6d1L);
        List<Feature> features = new ArrayList<>();
        // Random samples of a coherent patch field, never a grid of object anchors.
        int attempts = Math.min(100, density) * 6;
        for (int i = 0; i < attempts; i++) {
            int x = random.nextInt(461) - 230, z = random.nextInt(461) - 230;
            boolean upper = random.nextBoolean();
            double patch = DreamTerrain.fractal(seed + 713, x / 49.0, z / 49.0);
            if (random.nextDouble() > .57 + patch * .45) continue;
            if (surface.height(x, z, upper) == Integer.MIN_VALUE) continue;
            int biome = surface.biome(x, z);
            Map<Point, Material> shape = new LinkedHashMap<>();
            String kind;
            if (biome == 2 || biome == 4) {
                kind = "mineral-cluster";
                minerals(shape, random, biome);
            } else {
                double choice = random.nextDouble();
                if ((biome == 1 || biome == 3) && choice < .38) {
                    kind = "giant-mushroom";
                    mushroom(shape, random);
                } else if (choice < .62) {
                    kind = "fallen-tree";
                    fallenTree(shape, random, biome);
                } else {
                    kind = "boulder-cluster";
                    boulders(shape, random);
                }
            }
            Feature feature = ground(kind, x, z, upper, shape, surface);
            if (feature != null) features.add(feature);
        }
        return List.copyOf(features);
    }

    private static void fallenTree(Map<Point, Material> shape, Random random, int biome) {
        Material wood = biome == 1 ? Material.DARK_OAK_WOOD
                : biome == 3 ? Material.BIRCH_WOOD : Material.OAK_WOOD;
        double angle = random.nextDouble() * Math.PI * 2;
        double bend = (random.nextDouble() - .5) * .15;
        int length = 5 + random.nextInt(8);
        int px = 0, pz = 0;
        for (int step = 0; step <= length; step++) {
            int x = (int) Math.round(Math.cos(angle + bend * step) * step);
            int z = (int) Math.round(Math.sin(angle + bend * step) * step);
            // Rasterize successive curve points with face-connected wood.
            while (px != x || pz != z) {
                if (px != x) px += Integer.signum(x - px);
                else pz += Integer.signum(z - pz);
                put(shape, px, 0, pz, wood);
                put(shape, px, 1, pz, wood);
            }
            put(shape, x, 0, z, wood);
            if (step < length - 1) put(shape, x, 1, z, wood);
            if (step > 1 && step < length - 1 && random.nextDouble() < .35)
                put(shape, x, 2, z, Material.MOSS_BLOCK);
        }
    }

    private static void boulders(Map<Point, Material> shape, Random random) {
        int radius = 2 + random.nextInt(3);
        double stretch = .7 + random.nextDouble() * .7;
        long roughSeed = random.nextLong();
        for (int z = -radius; z <= radius; z++) for (int x = -radius; x <= radius; x++) {
            double distance = x * x / (double) (radius * radius)
                    + z * z / (radius * radius * stretch);
            int height = (int) Math.floor((1 - distance) * (2 + random.nextInt(2))
                    + DreamTerrain.noise(roughSeed, x * .7, 0, z * .7) * .7);
            for (int y = 0; y <= height; y++)
                put(shape, x, y, z, y == height && random.nextDouble() < .45
                        ? Material.MOSS_BLOCK : random.nextBoolean() ? Material.TUFF : Material.MOSSY_COBBLESTONE);
        }
        // A connected central base joins all lobes despite roughness at their edges.
        for (int z = -1; z <= 1; z++) for (int x = -1; x <= 1; x++)
            put(shape, x, 0, z, Material.TUFF);
        // Noise may leave a single outer cell beyond a missing rim column.
        // Keep only rock joined to the central base, without inventing bridges.
        Set<Point> connected = new HashSet<>();
        ArrayDeque<Point> pending = new ArrayDeque<>();
        Point root = new Point(0, 0, 0);
        connected.add(root);
        pending.add(root);
        while (!pending.isEmpty()) {
            Point p = pending.removeFirst();
            for (Point adjacent : List.of(new Point(p.x - 1, p.y, p.z), new Point(p.x + 1, p.y, p.z),
                    new Point(p.x, p.y - 1, p.z), new Point(p.x, p.y + 1, p.z),
                    new Point(p.x, p.y, p.z - 1), new Point(p.x, p.y, p.z + 1))) {
                if (shape.containsKey(adjacent) && connected.add(adjacent)) pending.add(adjacent);
            }
        }
        shape.keySet().retainAll(connected);
    }

    private static void mushroom(Map<Point, Material> shape, Random random) {
        int height = 4 + random.nextInt(4), radius = 2 + random.nextInt(2);
        int leanX = random.nextInt(3) - 1, leanZ = random.nextInt(3) - 1;
        for (int y = 0; y < height; y++) {
            int x = y >= height / 2 ? leanX : 0, z = y >= height / 2 ? leanZ : 0;
            // Broad stem keeps diagonal lean transitions face-connected.
            for (int dz = 0; dz <= 1; dz++) for (int dx = 0; dx <= 1; dx++)
                put(shape, x + dx, y, z + dz, Material.MUSHROOM_STEM);
        }
        Material cap = random.nextBoolean() ? Material.RED_MUSHROOM_BLOCK : Material.BROWN_MUSHROOM_BLOCK;
        double aspect = .8 + random.nextDouble() * .45;
        for (int dz = -radius; dz <= radius + 1; dz++) for (int dx = -radius; dx <= radius + 1; dx++) {
            double d = (dx - .5) * (dx - .5) / (radius * radius * aspect)
                    + (dz - .5) * (dz - .5) / (radius * radius);
            if (d > 1) continue;
            int top = height + (int) Math.round((1 - d) * 2);
            for (int y = height - 1; y <= top; y++)
                put(shape, leanX + dx, y, leanZ + dz,
                        y == height - 1 && d < .45 && random.nextDouble() < .16 ? Material.SHROOMLIGHT : cap);
        }
    }

    private static void minerals(Map<Point, Material> shape, Random random, int biome) {
        Material rock = biome == 4 ? Material.BLACKSTONE : Material.BASALT;
        Material mineral = biome == 4 ? Material.QUARTZ_BLOCK : Material.AMETHYST_BLOCK;
        for (int dz = -2; dz <= 2; dz++) for (int dx = -2; dx <= 2; dx++)
            if (dx * dx + dz * dz <= 5) put(shape, dx, 0, dz, rock);
        int count = 3 + random.nextInt(4);
        for (int i = 0; i < count; i++) {
            int bx = random.nextInt(3) - 1, bz = random.nextInt(3) - 1;
            int height = 4 + random.nextInt(8);
            double angle = random.nextDouble() * Math.PI * 2;
            double lean = .12 + random.nextDouble() * .22;
            for (int y = 0; y < height; y++) {
                int cx = bx + (int) Math.round(Math.cos(angle) * y * lean);
                int cz = bz + (int) Math.round(Math.sin(angle) * y * lean);
                int radius = y < height - 2 ? 1 : 0;
                for (int dz = -radius; dz <= radius; dz++) for (int dx = -radius; dx <= radius; dx++)
                    put(shape, cx + dx, y, cz + dz, y == 0 ? rock : mineral);
                // A last narrow tip must connect even if the leaning center advances.
                if (y > 0) {
                    int previousX = bx + (int) Math.round(Math.cos(angle) * (y - 1) * lean);
                    int previousZ = bz + (int) Math.round(Math.sin(angle) * (y - 1) * lean);
                    put(shape, previousX, y, previousZ, mineral);
                    put(shape, cx, y, previousZ, mineral);
                }
            }
        }
    }

    private static void put(Map<Point, Material> shape, int x, int y, int z, Material material) {
        shape.put(new Point(x, y, z), material);
    }

    private static Feature ground(String kind, int x, int z, boolean upper,
                                  Map<Point, Material> shape, Surface surface) {
        Map<Column, Integer> heights = new LinkedHashMap<>();
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (Point point : shape.keySet()) {
            int wx = x + point.x, wz = z + point.z;
            if (Math.abs(wx) > 244 || Math.abs(wz) > 244 || Math.max(Math.abs(wx), Math.abs(wz)) < 14)
                return null;
            Column column = new Column(point.x, point.z);
            if (heights.containsKey(column)) continue;
            int height = surface.height(wx, wz, upper);
            if (height == Integer.MIN_VALUE) return null;
            heights.put(column, height);
            min = Math.min(min, height);
            max = Math.max(max, height);
        }
        if (shape.isEmpty() || max - min > 3) return null;
        int base = max + 1;
        List<Voxel> voxels = new ArrayList<>();
        for (Map.Entry<Point, Material> entry : shape.entrySet()) {
            Point point = entry.getKey();
            int y = base + point.y;
            if (y > 250) return null;
            voxels.add(new Voxel(x + point.x, y, z + point.z, entry.getValue()));
            if (point.y == 0) {
                // Ground-contact columns extend down the small accepted slope.
                int floor = heights.get(new Column(point.x, point.z));
                for (int support = floor + 1; support < base; support++)
                    voxels.add(new Voxel(x + point.x, support, z + point.z, entry.getValue()));
            }
        }
        return new Feature(kind, x, base, z, upper, voxels);
    }
}
