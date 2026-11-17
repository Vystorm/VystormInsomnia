package at.vystorm.insomnia.dream.world;

import java.util.*;
import static at.vystorm.insomnia.dream.world.DreamLayout.*;

/** Bounded survey of naturally walkable lower land; never creates islands or bridges. */
final class DreamDungeonPlacement {
    interface Surface {
        int height(int x, int z);
        int biome(int x, int z);
    }
    record Site(DreamDungeonGenerator.Plan plan, List<BlockPos> trail) {
        Site {
            trail = List.copyOf(trail);
        }
    }
    private static final int LIMIT = 244, SIZE = 489;
    private record Candidate(int x, int z, double score) {}
    private DreamDungeonPlacement() {}

    static List<Site> generate(long seed, Surface surface) {
        List<Site> sites = new ArrayList<>();
        List<Bounds> occupied = new ArrayList<>();
        Set<Integer> existingTrails = new HashSet<>();
        for (DungeonType type : DungeonType.values()) {
            var template = DreamDungeonGenerator.generate(seed, type, new BlockPos(0, 1, 0));
            var floors = template.voxels().stream().filter(v -> v.y() == 0).toList();
            int[] reachable = survey(surface, occupied);
            List<Candidate> candidates = new ArrayList<>();
            int desired = type == DungeonType.CLOUD_RUINS ? 0 : type == DungeonType.CRYSTAL_VAULT ? 2 : 4;
            for (int x = -208; x <= 208; x += 4) for (int z = -208; z <= 208; z += 4) {
                if (Math.max(Math.abs(x), Math.abs(z)) < 40) continue;
                int ax = x + template.approach().x(), az = z + template.approach().z();
                if (!inside(ax, az) || reachable[index(ax, az)] == -1) continue;
                double score = (surface.biome(x, z) == desired ? 1000 : 0)
                        + DreamTerrain.noise(seed + 1011 + type.ordinal(), x * .71, 0, z * .71) * 100;
                candidates.add(new Candidate(x, z, score));
            }
            candidates.sort(Comparator.comparingDouble(Candidate::score).reversed());
            Site selected = null;
            for (int grade : new int[]{6, 10}) {
                for (var c : candidates) {
                    var b = template.dungeon().bounds();
                    Bounds bounds = new Bounds(b.minX() + c.x, b.maxX() + c.x, b.minZ() + c.z, b.maxZ() + c.z);
                    if (bounds.minX() < -238 || bounds.maxX() > 238 || bounds.minZ() < -238 || bounds.maxZ() > 238
                            || overlap(bounds, new Bounds(-20, 20, -20, 20), 3)
                            || occupied.stream().anyMatch(other -> overlap(bounds, other, 5))) continue;
                    int ax = c.x + template.approach().x(), az = c.z + template.approach().z();
                    int floor = surface.height(ax, az);
                    boolean valid = true;
                    for (var v : floors) {
                        int x = c.x + v.x(), z = c.z + v.z(), top = surface.height(x, z);
                        if (top == Integer.MIN_VALUE || Math.abs(top - floor) > grade || existingTrails.contains(index(x, z))) {
                            valid = false;
                            break;
                        }
                    }
                    if (!valid) continue;
                    // Approach from outside the wall perimeter, never through a room wall.
                    List<Bounds> blocked = new ArrayList<>(occupied);
                    blocked.add(bounds);
                    int[] parents = survey(surface, blocked);
                    if (parents[index(ax, az)] == -1) continue;
                    List<BlockPos> trail = new ArrayList<>();
                    int p = index(ax, az);
                    while (p != index(0, 0)) {
                        int x = p / SIZE - LIMIT, z = p % SIZE - LIMIT;
                        trail.add(new BlockPos(x, surface.height(x, z) + 1, z));
                        p = parents[p];
                    }
                    Collections.reverse(trail);
                    var plan = DreamDungeonGenerator.generate(seed, type, new BlockPos(c.x, floor + 1, c.z));
                    selected = new Site(plan, trail);
                    break;
                }
                if (selected != null) break;
            }
            if (selected == null) throw new IllegalStateException("No supported reachable dungeon site for " + type + " seed " + seed);
            sites.add(selected);
            occupied.add(selected.plan.dungeon().bounds());
            for (var p : selected.trail) existingTrails.add(index(p.x(), p.z()));
        }
        return List.copyOf(sites);
    }

    private static int[] survey(Surface surface, List<Bounds> blocked) {
        int[] parent = new int[SIZE * SIZE];
        Arrays.fill(parent, -1);
        int[] queue = new int[parent.length];
        int read = 0, write = 0;
        int origin = index(0, 0);
        parent[origin] = origin;
        queue[write++] = origin;
        while (read < write) {
            int current = queue[read++], x = current / SIZE - LIMIT, z = current % SIZE - LIMIT, top = surface.height(x, z);
            for (int direction = 0; direction < 4; direction++) {
                int nx = x + (direction == 0 ? -1 : direction == 1 ? 1 : 0), nz = z + (direction == 2 ? -1 : direction == 3 ? 1 : 0);
                if (!inside(nx, nz) || parent[index(nx, nz)] != -1) continue;
                int next = surface.height(nx, nz);
                if (next == Integer.MIN_VALUE || Math.abs(next - top) > 1) continue;
                boolean denied = false;
                for (var bounds : blocked) if (bounds.contains(nx, nz)) {
                    denied = true;
                    break;
                }
                if (denied) continue;
                parent[index(nx, nz)] = current;
                queue[write++] = index(nx, nz);
            }
        }
        return parent;
    }
    private static boolean overlap(Bounds a, Bounds b, int margin) {
        return a.minX() <= b.maxX() + margin && a.maxX() >= b.minX() - margin
                && a.minZ() <= b.maxZ() + margin && a.maxZ() >= b.minZ() - margin;
    }
    private static boolean inside(int x, int z) {
        return Math.abs(x) <= LIMIT && Math.abs(z) <= LIMIT;
    }
    private static int index(int x, int z) {
        return (x + LIMIT) * SIZE + z + LIMIT;
    }
}
