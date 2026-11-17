package at.vystorm.insomnia.service;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ShardRewardLimiterTest {
    @Test
    void diminishingReturnsAreOwnerSpecificAndHardCappedInRollingWindow() {
        ShardRewardLimiter limiter = new ShardRewardLimiter();
        UUID owner = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        assertEquals(1.0, limiter.chance(owner, 0, 10_000, 3, 1));
        limiter.awarded(owner, 0);
        assertEquals(0.5, limiter.chance(owner, 1, 10_000, 3, 1));
        limiter.awarded(owner, 1);
        assertEquals(0.25, limiter.chance(owner, 2, 10_000, 3, 1));
        limiter.awarded(owner, 2);
        assertEquals(0.0, limiter.chance(owner, 3, 10_000, 3, 1));
        assertEquals(1.0, limiter.chance(other, 3, 10_000, 3, 1));
        assertEquals(1.0, limiter.chance(owner, 10_002, 10_000, 3, 1));
        limiter.prune(10_002, 10_000);
        assertEquals(0, limiter.count(owner, 10_002, 10_000));
    }
}
