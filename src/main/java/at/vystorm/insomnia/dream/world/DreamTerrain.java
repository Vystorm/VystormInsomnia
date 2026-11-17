package at.vystorm.insomnia.dream.world;

/**
 * Immutable, Bukkit-independent density terrain. Land boundaries are contours of a
 * domain-warped noise field, never a collection of predefined island shapes.
 * Occupancy retains the complete vertical density result, including caves and
 * overhanging shelves; surface/bottom are only lookup accelerators.
 */
final class DreamTerrain {
    static final int LIMIT = 256;
    static final int MIN_Y = 64;
    static final int MAX_Y = 191;
    private static final int NATURAL_MAX_Y = 164;
    private static final int WIDTH = LIMIT * 2 + 1;
    private static final int STEP = 4;
    private static final int GRID = LIMIT * 2 / STEP + 1;
    // Include the upper interpolation neighbour even at the last sampled level.
    private static final int Y_GRID = (NATURAL_MAX_Y - MIN_Y) / STEP + 2;

    private final long[] lower = new long[WIDTH * WIDTH];
    private final long[] upper = new long[WIDTH * WIDTH];
    private final byte[] tops = new byte[WIDTH * WIDTH];
    private final byte[] bottoms = new byte[WIDTH * WIDTH];
    private final byte[] biomes = new byte[WIDTH * WIDTH];

    DreamTerrain(long seed) {
        int[] origin = chooseNoiseOrigin(seed);
        // Coherent four-block lattice avoids evaluating expensive fractal noise
        // for every voxel. All columns interpolate the same shared lattice.
        float[] detail = new float[GRID * GRID * Y_GRID];
        float[] caves = new float[detail.length];
        for (int gz = 0; gz < GRID; gz++) for (int gx = 0; gx < GRID; gx++) {
            int x = gx * STEP - LIMIT + origin[0], z = gz * STEP - LIMIT + origin[1];
            for (int gy = 0; gy < Y_GRID; gy++) {
                int y = MIN_Y + gy * STEP;
                int index = (gz * GRID + gx) * Y_GRID + gy;
                detail[index] = (float) (noise(seed + 101, x / 27.0, y / 13.0, z / 27.0) * .72
                        + noise(seed + 102, x / 12.0, y / 7.0, z / 12.0) * .28);
                caves[index] = (float) noise(seed + 103, x / 23.0, y / 9.0, z / 23.0);
            }
        }
        for (int z = -LIMIT; z <= LIMIT; z++) for (int x = -LIMIT; x <= LIMIT; x++) {
            int index = index(x, z);
            int domainX = x + origin[0], domainZ = z + origin[1];
            double warpX = fractal(seed + 1, domainX / 105.0, domainZ / 105.0) * 32;
            double warpZ = fractal(seed + 2, domainX / 105.0, domainZ / 105.0) * 32;
            double wx = domainX + warpX, wz = domainZ + warpZ;
            double edge = Math.max(0, (Math.max(Math.abs(x), Math.abs(z)) - 224) / 29.0);
            double land = fractal(seed + 3, wx / 100.0, wz / 100.0) + .09 - edge;
            double floor = naturalHeight(seed, domainX, domainZ, wx, wz);
            double bottom = floor - 21 - fractal(seed + 6, wx / 83.0, wz / 83.0) * 10;
            biomes[index] = (byte) biome(seed, domainX, domainZ);
            int gx = Math.min((x + LIMIT) / STEP, GRID - 2);
            int gz = Math.min((z + LIMIT) / STEP, GRID - 2);
            double tx = (x + LIMIT - gx * STEP) / (double) STEP;
            double tz = (z + LIMIT - gz * STEP) / (double) STEP;
            for (int y = MIN_Y; y <= NATURAL_MAX_Y; y++) {
                int gy = (y - MIN_Y) / STEP;
                double ty = (y - MIN_Y - gy * STEP) / (double) STEP;
                double roughness = sample(detail, gx, gy, gz, tx, ty, tz);
                double density = Math.min(land * 35,
                        Math.min((floor - y) * 3, (y - bottom) * .72)) + roughness * 8;
                // Carve a continuous 3-D field through the interior. Roofs stay
                // substantial, while lower galleries can break through cliffs.
                if (y < floor - 5 && y > bottom + 3) {
                    double cave = sample(caves, gx, gy, gz, tx, ty, tz);
                    density -= Math.max(0, cave - .32) * 95;
                }
                if (density <= 0 || x == -LIMIT || x == LIMIT || z == -LIMIT || z == LIMIT) continue;
                int bit = y - MIN_Y;
                if (bit < 64) lower[index] |= 1L << bit;
                else upper[index] |= 1L << (bit - 64);
                if (bottoms[index] == 0) bottoms[index] = (byte) y;
                tops[index] = (byte) y;
            }
        }
    }

    /**
     * Find a spawn location in the existing infinite noise field, then translate
     * that field as a whole. This selects natural CLOUD land rather than adding
     * a shaped island or changing density around the spawn. Search is bounded;
     * the best supported candidate remains a deterministic fallback.
     */
    private static int[] chooseNoiseOrigin(long seed) {
        int[] best = {0, 0};
        double bestScore = -Double.MAX_VALUE;
        boolean hasFootprint = false;
        for (int candidate = 0; candidate < 16_384; candidate++) {
            // Multiples of the shared lattice step preserve coherent sampling
            // when the chosen noise domain is translated into local coordinates.
            int x = (int) Math.floor(hash(seed + 211, candidate, 0, 0) * 2048) * STEP;
            int z = (int) Math.floor(hash(seed + 212, candidate, 0, 0) * 2048) * STEP;
            double land = naturalLand(seed, x, z), cloudMargin = cloudMargin(seed, x, z);
            double centerScore = Math.min(land - .24, cloudMargin - .08);
            if (!hasFootprint && centerScore > bestScore) {
                bestScore = centerScore;
                best = new int[]{x, z};
            }
            if (land < .24 || cloudMargin < .08) continue;
            double minimumLand = land, minimumCloudMargin = cloudMargin;
            // Survey a broad natural arrival neighbourhood; no shaped spawn island is added.
            for (int dz = -32; dz <= 32; dz += 8) for (int dx = -32; dx <= 48; dx += 8) {
                minimumLand = Math.min(minimumLand, naturalLand(seed, x + dx, z + dz));
                if (Math.abs(dx) <= 24 && Math.abs(dz) <= 24)
                    minimumCloudMargin = Math.min(minimumCloudMargin, cloudMargin(seed, x + dx, z + dz));
            }
            double wx = x + fractal(seed + 1, x / 105.0, z / 105.0) * 32;
            double wz = z + fractal(seed + 2, x / 105.0, z / 105.0) * 32;
            double floor = naturalHeight(seed, x, z, wx, wz);
            double score = minimumLand - .18 - Math.abs(floor - 112) * .002
                    - Math.max(0, -minimumCloudMargin) * .05;
            // Once footprint candidates exist, compare footprints rather than
            // accidentally replacing them with a strong but narrow center.
            if (!hasFootprint || score > bestScore) {
                bestScore = score;
                best = new int[]{x, z};
                hasFootprint = true;
            }
            if (minimumLand >= .18 && floor >= 109 && floor <= 115)
                return new int[]{x, z};
        }
        return best;
    }

    /**
     * Broad hills share one continuous height field. Climate-space weights blend
     * meadow valleys, forest ridges, rocky peaks and garden rolls without using
     * the discrete material biome ID, so climate boundaries cannot form cliffs.
     * The tanh envelope approaches its bounds smoothly instead of clipping peaks
     * into plateaus. Density detail above this floor retains overhanging shelves.
     */
    private static double naturalHeight(long seed, double x, double z, double wx, double wz) {
        double temperature = temperature(seed, x, z), moisture = moisture(seed, x, z);
        double broad = fractal(seed + 4, wx / 150.0, wz / 150.0);
        double rolling = fractal(seed + 5, wx / 63.0, wz / 63.0);
        double ridgeNoise = noise(seed + 41, wx / 95.0, 0, wz / 95.0);
        // Rounded continuous crests, with no absolute-value cusp at the ridge.
        double ridge = 1 - 2 * Math.sqrt(ridgeNoise * ridgeNoise + .035);
        double meadow = broad * 66 + rolling * 13 - 6;
        double forest = broad * 71 + rolling * 16 + ridge * 15;
        double crystal = broad * 78 + rolling * 19 + ridge * 25 + 7;
        double garden = broad * 65 + rolling * 21 - 2;
        double night = broad * 77 + rolling * 18 + ridge * 23 + 5;
        double sum = 0, weightSum = 0;
        for (int biome = 0; biome < 5; biome++) {
            double centerX = biome == 0 ? 0 : biome == 1 || biome == 4 ? -.44 : .44;
            double centerZ = biome == 0 ? 0 : biome == 1 || biome == 2 ? -.44 : .44;
            double dt = temperature - centerX, dm = moisture - centerZ;
            double weight = Math.exp(-(dt * dt + dm * dm) / .10);
            double relief = switch (biome) {
                case 0 -> meadow;
                case 1 -> forest;
                case 2 -> crystal;
                case 3 -> garden;
                default -> night;
            };
            sum += relief * weight;
            weightSum += weight;
        }
        // Shallow winding depressions add local contour between the broad hills.
        // A smooth noise contour has no hand-placed channel path or biome seam.
        double channel = noise(seed + 47, wx / 48.0, 0, wz / 48.0);
        double swale = Math.exp(-channel * channel / .012)
                * (3.5 + 1.5 * noise(seed + 48, wx / 110.0, 0, wz / 110.0));
        return 118 + 39 * Math.tanh(sum / (weightSum * 39)) - swale;
    }

    private static double naturalLand(long seed, double x, double z) {
        double wx = x + fractal(seed + 1, x / 105.0, z / 105.0) * 32;
        double wz = z + fractal(seed + 2, x / 105.0, z / 105.0) * 32;
        return fractal(seed + 3, wx / 100.0, wz / 100.0) + .09;
    }

    /**
     * Two independent climate axes select the nearest of five climate-space
     * cells: temperate center and four temperature/moisture corners. These are
     * not world-space island centers. Warping makes the broad region boundaries
     * irregular, while the two axes avoid successive thin scalar contour bands.
     */
    private static int biome(long seed, double x, double z) {
        double temperature = temperature(seed, x, z), moisture = moisture(seed, x, z);
        // Nearest-center comparison for (0,0) versus (+/-.44,+/-.44).
        if (Math.abs(temperature) + Math.abs(moisture) <= .44) return 0;
        return temperature < 0 ? (moisture < 0 ? 1 : 4) : (moisture < 0 ? 2 : 3);
    }

    private static double cloudMargin(long seed, double x, double z) {
        return .44 - Math.abs(temperature(seed, x, z)) - Math.abs(moisture(seed, x, z));
    }

    private static double temperature(long seed, double x, double z) {
        double wx = x + noise(seed + 71, x / 130, 0, z / 130) * 27;
        double wz = z + noise(seed + 72, x / 130, 0, z / 130) * 27;
        return noise(seed + 7, wx / 78, 0, wz / 78) * .87
                + noise(seed + 8, wx / 39, 0, wz / 39) * .13;
    }

    private static double moisture(long seed, double x, double z) {
        double wx = x + noise(seed + 73, x / 137, 0, z / 137) * 29;
        double wz = z + noise(seed + 74, x / 137, 0, z / 137) * 29;
        return noise(seed + 9, wx / 86, 0, wz / 86) * .87
                + noise(seed + 10, wx / 43, 0, wz / 43) * .13;
    }

    int surfaceY(int x, int z) {
        if (!inside(x, z)) return Integer.MIN_VALUE;
        int y = Byte.toUnsignedInt(tops[index(x, z)]);
        return y == 0 ? Integer.MIN_VALUE : y;
    }

    int bottomY(int x, int z) {
        if (!inside(x, z)) return Integer.MIN_VALUE;
        int y = Byte.toUnsignedInt(bottoms[index(x, z)]);
        return y == 0 ? Integer.MIN_VALUE : y;
    }

    boolean solid(int x, int y, int z) {
        if (!inside(x, z) || y < MIN_Y || y > MAX_Y) return false;
        int bit = y - MIN_Y, index = index(x, z);
        return bit < 64 ? (lower[index] & (1L << bit)) != 0
                : (upper[index] & (1L << (bit - 64))) != 0;
    }

    int biomeAt(int x, int z) {
        return inside(x, z) ? biomes[index(x, z)] : 0;
    }

    private static boolean inside(int x, int z) {
        return x >= -LIMIT && x <= LIMIT && z >= -LIMIT && z <= LIMIT;
    }

    private static int index(int x, int z) { return (z + LIMIT) * WIDTH + x + LIMIT; }

    private static double sample(float[] grid, int x, int y, int z, double tx, double ty, double tz) {
        int a = (z * GRID + x) * Y_GRID + y, b = a + GRID * Y_GRID;
        return lerp(lerp(lerp(grid[a], grid[a + 1], ty),
                        lerp(grid[a + Y_GRID], grid[a + Y_GRID + 1], ty), tx),
                lerp(lerp(grid[b], grid[b + 1], ty),
                        lerp(grid[b + Y_GRID], grid[b + Y_GRID + 1], ty), tx), tz);
    }

    static double fractal(long seed, double x, double z) {
        return noise(seed, x, 0, z) * .64 + noise(seed + 17, x * 2.03, 0, z * 2.03) * .25
                + noise(seed + 31, x * 4.11, 0, z * 4.11) * .11;
    }

    static double noise(long seed, double x, double y, double z) {
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y), iz = (int) Math.floor(z);
        double tx = smooth(x - ix), ty = smooth(y - iy), tz = smooth(z - iz);
        return lerp(lerp(lerp(hash(seed, ix, iy, iz), hash(seed, ix + 1, iy, iz), tx),
                        lerp(hash(seed, ix, iy + 1, iz), hash(seed, ix + 1, iy + 1, iz), tx), ty),
                lerp(lerp(hash(seed, ix, iy, iz + 1), hash(seed, ix + 1, iy, iz + 1), tx),
                        lerp(hash(seed, ix, iy + 1, iz + 1), hash(seed, ix + 1, iy + 1, iz + 1), tx), ty), tz);
    }

    private static double hash(long seed, int x, int y, int z) {
        long n = seed ^ x * 0x632be59bd9b4e019L ^ y * 0x9e3779b97f4a7c15L ^ z * 0x85157af5d66d13e3L;
        n = (n ^ (n >>> 30)) * 0xbf58476d1ce4e5b9L;
        n = (n ^ (n >>> 27)) * 0x94d049bb133111ebL;
        return ((n ^ (n >>> 31)) >>> 11) * 0x1.0p-52 - 1;
    }

    private static double smooth(double t) { return t * t * t * (t * (t * 6 - 15) + 10); }
    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }
}
