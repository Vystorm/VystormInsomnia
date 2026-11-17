package at.vystorm.insomnia.service;

import at.vystorm.insomnia.config.InsomniaConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.Location;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ActivityServiceTest {
    @Test
    void localAfkUsesExactThresholdResetsOnInputAndEvictsOnQuit() {
        InsomniaConfig config = mock(InsomniaConfig.class);
        ActivityService activity = new ActivityService(config);
        UUID playerId = UUID.randomUUID();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.isSprinting()).thenReturn(false);
        when(player.isSwimming()).thenReturn(false);
        long joinedAt = 100_000L;
        activity.join(player, joinedAt);

        assertFalse(activity.locallyAfk(playerId, joinedAt + 299_999L, 300_000L));
        assertTrue(activity.locallyAfk(playerId, joinedAt + 300_000L, 300_000L));
        activity.input(playerId, joinedAt + 300_000L);
        assertFalse(activity.locallyAfk(playerId, joinedAt + 599_999L, 300_000L));
        assertTrue(activity.locallyAfk(playerId, joinedAt + 600_000L, 300_000L));
        activity.quit(playerId);
        assertFalse(activity.locallyAfk(playerId, joinedAt + 900_000L, 300_000L));
        assertFalse(activity.tracksInput(playerId));
    }

    @Test
    void meaningfulMoveRequiresBlockChangeOrFiveDegreeLookChange() {
        Location origin = new Location(null, 10.2, 64.0, 10.3, 0.0f, 0.0f);
        assertFalse(at.vystorm.insomnia.listener.GameplayListener.meaningfulMove(origin,
                new Location(null, 10.8, 64.0, 10.3, 4.9f, 0.0f)));
        assertTrue(at.vystorm.insomnia.listener.GameplayListener.meaningfulMove(origin,
                new Location(null, 10.8, 64.0, 10.3, 5.0f, 0.0f)));
        assertTrue(at.vystorm.insomnia.listener.GameplayListener.meaningfulMove(origin,
                new Location(null, 11.0, 64.0, 10.3, 0.0f, 0.0f)));
        assertFalse(at.vystorm.insomnia.listener.GameplayListener.meaningfulMove(
                new Location(null, 0, 64, 0, 359.0f, 0), new Location(null, 0, 64, 0, 1.0f, 0)));
    }

    @Test
    void usesOnlyTheHighestActiveActivityMultiplierAndExpiresWindows() {
        InsomniaConfig config = mock(InsomniaConfig.class);
        Map<String, Double> multipliers = Map.of(
                "normal", 1.0, "sprint", 1.2, "swimming", 1.3,
                "mining", 1.1, "combat", 1.5, "boss", 2.0);
        when(config.activity(anyString(), anyDouble())).thenAnswer(invocation ->
                multipliers.getOrDefault(invocation.getArgument(0, String.class), invocation.getArgument(1, Double.class)));
        when(config.miningWindowMillis()).thenReturn(8_000L);
        when(config.combatWindowMillis()).thenReturn(20_000L);
        when(config.bossWindowMillis()).thenReturn(30_000L);

        ActivityService activity = new ActivityService(config);
        UUID player = UUID.randomUUID();
        long now = 100_000L;
        activity.sprint(player, true);
        activity.swimming(player, true);
        activity.mining(player, now);
        activity.combat(player, now, true);

        assertEquals(2.0, activity.multiplier(player, now));
        assertEquals(2.0, activity.multiplier(player, now + 7_999L));
        assertEquals(2.0, activity.multiplier(player, now + 20_000L));
        assertEquals(1.3, activity.multiplier(player, now + 30_000L));
    }
}
