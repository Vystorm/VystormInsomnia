package at.vystorm.insomnia.dream.world;

/**
 * Immutable secondary terrain, sampled entirely from continuous seeded fields.
 * Threshold contours create the disconnected footprints; asymmetric vertical
 * density gives them rounded hills and longer, tapered hanging undersides.
 */
final class DreamSkyTerrain {
    static final int MIN_Y = 150;
    static final int MAX_Y = 222;
    private static final int LIMIT = DreamTerrain.LIMIT;
    private static final int WIDTH = LIMIT * 2 + 1;
    private static final int STEP = 4;
    private static final int GRID = LIMIT * 2 / STEP + 1;
    private static final int Y_GRID = (MAX_Y - MIN_Y) / STEP + 2;

    private final long[] lower = new long[WIDTH * WIDTH];
    private final long[] upper = new long[WIDTH * WIDTH];
    private final byte[] tops = new byte[WIDTH * WIDTH];
    private final byte[] bottoms = new byte[WIDTH * WIDTH];

    DreamSkyTerrain(long seed, DreamTerrain base, int densityPercent) {
        if (densityPercent <= 0) return;
        double threshold = .33 + .48 * (1 - Math.min(100, densityPercent) / 100.0);
        float[] detail = new float[GRID * GRID * Y_GRID];
        float[] openings = new float[detail.length];
        for (int gz = 0; gz < GRID; gz++) for (int gx = 0; gx < GRID; gx++) {
            int x = gx * STEP - LIMIT, z = gz * STEP - LIMIT;
            for (int gy = 0; gy < Y_GRID; gy++) {
                int y = MIN_Y + gy * STEP, index = (gz * GRID + gx) * Y_GRID + gy;
                detail[index] = (float) DreamTerrain.noise(seed + 1201, x / 19.0, y / 11.0, z / 19.0);
                openings[index] = (float) DreamTerrain.noise(seed + 1202, x / 17.0, y / 8.0, z / 17.0);
            }
        }
        for (int z = -LIMIT; z <= LIMIT; z++) for (int x = -LIMIT; x <= LIMIT; x++) {
            int extent = Math.max(Math.abs(x), Math.abs(z));
            if (extent < 24 || extent >= 248) continue;
            double wx = x + DreamTerrain.fractal(seed + 1203, x / 91.0, z / 91.0) * 23;
            double wz = z + DreamTerrain.fractal(seed + 1204, x / 91.0, z / 91.0) * 23;
            double edge = smooth(Math.max(0, (extent - 220) / 28.0));
            // A continuous clearance fade avoids a squared vertical wall around arrival.
            double spawn = 1 - smooth(Math.min(1, Math.max(0, (extent - 24) / 16.0)));
            double land = DreamTerrain.fractal(seed + 1205, wx / 79.0, wz / 79.0)
                    - threshold - edge - spawn;
            if (land < -.09) continue;
            double mass = 24 * Math.tanh(land * 3);
            double center = 193 + 8 * Math.tanh(DreamTerrain.fractal(seed + 1206, wx / 57.0, wz / 57.0) * 2);
            int baseTop = base.surfaceY(x, z);
            int minimum = baseTop == Integer.MIN_VALUE ? MIN_Y : Math.max(MIN_Y, baseTop + 25);
            int index = index(x, z);
            int gx = (x + LIMIT) / STEP, gz = (z + LIMIT) / STEP;
            double tx = (x + LIMIT - gx * STEP) / (double) STEP;
            double tz = (z + LIMIT - gz * STEP) / (double) STEP;
            for (int y = minimum; y <= MAX_Y; y++) {
                int gy = (y - MIN_Y) / STEP;
                double ty = (y - MIN_Y - gy * STEP) / (double) STEP;
                double vertical = y > center ? (y - center) * 1.55 : (center - y) * .75;
                double density = mass - vertical + sample(detail, gx, gy, gz, tx, ty, tz) * 3.2;
                // Roofs and narrow tips stay intact while a third dimension can
                // open galleries through thicker interiors and overhanging sides.
                double cave = sample(openings, gx, gy, gz, tx, ty, tz);
                if (y < center + mass / 1.55 - 4 && y > center - mass / .75 + 3)
                    density -= Math.max(0, cave - .42) * 65;
                if (density <= 0) continue;
                int bit = y - MIN_Y;
                if (bit < 64) lower[index] |= 1L << bit;
                else upper[index] |= 1L << (bit - 64);
                if (bottoms[index] == 0) bottoms[index] = (byte) y;
                tops[index] = (byte) y;
            }
        }
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

    private static double smooth(double t) { return t * t * t * (t * (t * 6 - 15) + 10); }
    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }
}
