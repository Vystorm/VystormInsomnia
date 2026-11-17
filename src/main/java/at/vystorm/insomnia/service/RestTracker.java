package at.vystorm.insomnia.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Bukkit-free state machine behind {@link CampfireRestService}: who stands still where since when, and who is resting.
 * Head turns do not count as movement; a move of more than {@link #MOVE_TOLERANCE} blocks restarts the settle time.
 */
final class RestTracker {
    /** What {@link #update} decided for this second. */
    enum Step {
        /** Not resting (moving, settling, no campfire, not eligible or already done until the next move). */
        IDLE,
        /** Rest begins now. */
        STARTED,
        /** Rest continues. */
        RESTING,
        /** Was resting and no longer is. */
        STOPPED
    }

    static final double MOVE_TOLERANCE = 0.2;

    private static final class Spot {
        UUID world;
        double x, y, z;
        long stillSince;
        long lastRestAt;
        boolean resting;
        /** Reached the floor; stays idle until the player moves. */
        boolean done;
    }

    private final Map<UUID, Spot> spots = new HashMap<>();

    /**
     * @param eligible       false ends a rest at once (combat, sleep, mode, not tired enough)
     * @param campfireNearby only asked when the player has stood still long enough, so the block scan stays rare
     */
    Step update(UUID player, UUID world, double x, double y, double z, long now, boolean eligible,
                long settleMillis, BooleanSupplier campfireNearby) {
        Spot spot = spots.get(player);
        if (spot == null || moved(spot, world, x, y, z)) {
            boolean wasResting = spot != null && spot.resting;
            spot = new Spot();
            spot.world = world;
            spot.x = x;
            spot.y = y;
            spot.z = z;
            spot.stillSince = now;
            spots.put(player, spot);
            return wasResting ? Step.STOPPED : Step.IDLE;
        }
        if (!eligible || spot.done) return stop(spot);
        if (now - spot.stillSince < settleMillis) return Step.IDLE;
        if (!campfireNearby.getAsBoolean()) return stop(spot);
        if (spot.resting) return Step.RESTING;
        spot.resting = true;
        spot.lastRestAt = now;
        return Step.STARTED;
    }

    /** Seconds since the last recovery step (at most 5, so a lag spike never pays out a lump sum). */
    double elapsedSeconds(UUID player, long now) {
        Spot spot = spots.get(player);
        if (spot == null || !spot.resting) return 0.0;
        double seconds = Math.max(0L, now - spot.lastRestAt) / 1000.0;
        spot.lastRestAt = now;
        return Math.min(5.0, seconds);
    }

    /** The floor is reached: ends the rest and stays quiet until the player moves away and back. */
    void complete(UUID player) {
        Spot spot = spots.get(player);
        if (spot == null) return;
        spot.resting = false;
        spot.done = true;
    }

    boolean resting(UUID player) {
        Spot spot = spots.get(player);
        return spot != null && spot.resting;
    }

    void forget(UUID player) { spots.remove(player); }

    /** Drops players that are no longer online. */
    void retain(Collection<UUID> online) { spots.keySet().retainAll(online); }

    void clear() { spots.clear(); }

    private static Step stop(Spot spot) {
        if (!spot.resting) return Step.IDLE;
        spot.resting = false;
        return Step.STOPPED;
    }

    private static boolean moved(Spot spot, UUID world, double x, double y, double z) {
        if (!spot.world.equals(world)) return true;
        double dx = spot.x - x, dy = spot.y - y, dz = spot.z - z;
        return dx * dx + dy * dy + dz * dz > MOVE_TOLERANCE * MOVE_TOLERANCE;
    }

    /**
     * Effective fatigue after resting {@code seconds} at {@code perSecond}, never below {@code floor} and never
     * higher than before (a player already under the floor keeps their value).
     */
    static double recovered(double fatigue, double floor, double perSecond, double seconds) {
        if (fatigue <= floor || !(perSecond > 0) || !(seconds > 0)) return fatigue;
        return Math.max(floor, fatigue - perSecond * seconds);
    }
}
