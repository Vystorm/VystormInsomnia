package at.vystorm.insomnia.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RestTrackerTest {
    private static final UUID PLAYER = UUID.randomUUID();
    private static final UUID WORLD = UUID.randomUUID();
    private static final long SETTLE = 5_000L;

    private static RestTracker.Step at(RestTracker tracker, double x, long now, boolean eligible, boolean campfire) {
        return tracker.update(PLAYER, WORLD, x, 64.0, 0.0, now, eligible, SETTLE, () -> campfire);
    }

    @Test
    void restStartsOnlyAfterStandingStillNextToACampfire() {
        RestTracker tracker = new RestTracker();
        assertEquals(RestTracker.Step.IDLE, at(tracker, 0.0, 0L, true, true));
        assertEquals(RestTracker.Step.IDLE, at(tracker, 0.0, 4_000L, true, true));
        assertEquals(RestTracker.Step.STARTED, at(tracker, 0.0, 5_000L, true, true));
        assertTrue(tracker.resting(PLAYER));
        assertEquals(RestTracker.Step.RESTING, at(tracker, 0.05, 6_000L, true, true), "tiny shifts are not movement");
    }

    @Test
    void noCampfireMeansNoRestAndTheScanWaitsForTheSettleTime() {
        RestTracker tracker = new RestTracker();
        AtomicInteger scans = new AtomicInteger();
        tracker.update(PLAYER, WORLD, 0, 64, 0, 0L, true, SETTLE, () -> { scans.incrementAndGet(); return false; });
        tracker.update(PLAYER, WORLD, 0, 64, 0, 3_000L, true, SETTLE, () -> { scans.incrementAndGet(); return false; });
        assertEquals(0, scans.get(), "no block scan while the player is still settling");
        assertEquals(RestTracker.Step.IDLE,
                tracker.update(PLAYER, WORLD, 0, 64, 0, 6_000L, true, SETTLE, () -> { scans.incrementAndGet(); return false; }));
        assertEquals(1, scans.get());
    }

    @Test
    void movingCombatOrAnExtinguishedFireStopTheRest() {
        RestTracker tracker = new RestTracker();
        at(tracker, 0.0, 0L, true, true);
        at(tracker, 0.0, 5_000L, true, true);
        assertEquals(RestTracker.Step.STOPPED, at(tracker, 1.0, 6_000L, true, true), "walking away");
        assertEquals(RestTracker.Step.IDLE, at(tracker, 1.0, 7_000L, true, true), "settle time starts again");
        assertEquals(RestTracker.Step.STARTED, at(tracker, 1.0, 11_000L, true, true));
        assertEquals(RestTracker.Step.STOPPED, at(tracker, 1.0, 12_000L, false, true), "combat or sleep");
        assertEquals(RestTracker.Step.STARTED, at(tracker, 1.0, 13_000L, true, true));
        assertEquals(RestTracker.Step.STOPPED, at(tracker, 1.0, 14_000L, true, false), "fire went out");
        assertFalse(tracker.resting(PLAYER));
    }

    @Test
    void changingWorldsCountsAsMovement() {
        RestTracker tracker = new RestTracker();
        at(tracker, 0.0, 0L, true, true);
        at(tracker, 0.0, 5_000L, true, true);
        assertEquals(RestTracker.Step.STOPPED, tracker.update(PLAYER, UUID.randomUUID(), 0, 64, 0, 6_000L, true, SETTLE, () -> true));
    }

    @Test
    void reachingTheFloorEndsQuietlyUntilThePlayerMoves() {
        RestTracker tracker = new RestTracker();
        at(tracker, 0.0, 0L, true, true);
        at(tracker, 0.0, 5_000L, true, true);
        tracker.complete(PLAYER);
        assertFalse(tracker.resting(PLAYER));
        assertEquals(RestTracker.Step.IDLE, at(tracker, 0.0, 6_000L, true, true));
        assertEquals(RestTracker.Step.IDLE, at(tracker, 0.0, 60_000L, true, true));
        at(tracker, 2.0, 61_000L, true, true);
        assertEquals(RestTracker.Step.STARTED, at(tracker, 2.0, 66_000L, true, true));
    }

    @Test
    void elapsedTimeIsCappedSoLagNeverPaysALumpSum() {
        RestTracker tracker = new RestTracker();
        assertEquals(0.0, tracker.elapsedSeconds(PLAYER, 0L));
        at(tracker, 0.0, 0L, true, true);
        at(tracker, 0.0, 5_000L, true, true);
        assertEquals(1.0, tracker.elapsedSeconds(PLAYER, 6_000L), 1e-9);
        assertEquals(5.0, tracker.elapsedSeconds(PLAYER, 60_000L), 1e-9);
    }

    @Test
    void offlinePlayersAreForgotten() {
        RestTracker tracker = new RestTracker();
        at(tracker, 0.0, 0L, true, true);
        at(tracker, 0.0, 5_000L, true, true);
        tracker.retain(List.of());
        assertFalse(tracker.resting(PLAYER));
        assertEquals(RestTracker.Step.IDLE, at(tracker, 0.0, 6_000L, true, true), "starts over after a relog");
    }

    @Test
    void recoveryNeverGoesBelowTheFloorOrRaisesFatigue() {
        assertEquals(49.8, RestTracker.recovered(50.0, 30.0, 0.2, 1.0), 1e-9);
        assertEquals(30.0, RestTracker.recovered(30.1, 30.0, 0.2, 5.0), 1e-9);
        assertEquals(25.0, RestTracker.recovered(25.0, 30.0, 0.2, 1.0), 1e-9, "already below the floor");
        assertEquals(50.0, RestTracker.recovered(50.0, 30.0, 0.0, 1.0), 1e-9);
        assertEquals(50.0, RestTracker.recovered(50.0, 30.0, Double.NaN, 1.0), 1e-9);
    }
}
