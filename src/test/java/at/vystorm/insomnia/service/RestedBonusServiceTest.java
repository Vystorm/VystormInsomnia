package at.vystorm.insomnia.service;

import at.vystorm.insomnia.config.InsomniaConfig;
import at.vystorm.insomnia.model.PlayerFatigueState;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RestedBonusServiceTest {
    @Test
    void tenPercentHungerReductionPreservesNinetyOfOneHundredSinglePointLosses() throws Exception {
        UUID id = UUID.randomUUID();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getFoodLevel()).thenReturn(20);
        InsomniaConfig config = mock(InsomniaConfig.class);
        when(config.restedEnabled()).thenReturn(true);
        when(config.restedHungerReduction()).thenReturn(0.1);
        RestedBonusService service = new RestedBonusService(config, fatigueWith(id, 20_000L), mock(ActivityService.class));
        var random = mock(java.util.concurrent.ThreadLocalRandom.class);
        try (var randomness = org.mockito.Mockito.mockStatic(java.util.concurrent.ThreadLocalRandom.class)) {
            randomness.when(java.util.concurrent.ThreadLocalRandom::current).thenReturn(random);
            int totalLoss = 0;
            for (int i = 0; i < 100; i++) {
                when(random.nextDouble()).thenReturn((i + 0.5) / 100.0);
                var event = new org.bukkit.event.entity.FoodLevelChangeEvent(player, 19);
                service.applyHunger(event, 10_000L);
                totalLoss += 20 - event.getFoodLevel();
            }
            assertEquals(90, totalLoss, "a small bonus must not prevent every normal hunger loss");
        }
    }

    @Test
    void activeRestedBonusIncreasesXpUsingConfiguredMultiplier() throws Exception {
        UUID playerId = UUID.randomUUID();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        InsomniaConfig config = mock(InsomniaConfig.class);
        when(config.restedEnabled()).thenReturn(true);
        when(config.restedXpMultiplier()).thenReturn(1.5);
        FatigueService fatigue = fatigueWith(playerId, 10_001L);
        RestedBonusService service = new RestedBonusService(config, fatigue, mock(ActivityService.class));
        PlayerExpChangeEvent event = new PlayerExpChangeEvent(player, 8);

        service.applyXp(event, 10_000L);

        assertEquals(12, event.getAmount());
    }

    @Test
    void expiredRestedBonusDoesNotChangeXp() throws Exception {
        UUID playerId = UUID.randomUUID();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        InsomniaConfig config = mock(InsomniaConfig.class);
        when(config.restedEnabled()).thenReturn(true);
        when(config.restedXpMultiplier()).thenReturn(1.5);
        FatigueService fatigue = fatigueWith(playerId, 10_000L);
        RestedBonusService service = new RestedBonusService(config, fatigue, mock(ActivityService.class));
        PlayerExpChangeEvent event = new PlayerExpChangeEvent(player, 8);

        service.applyXp(event, 10_000L);

        assertEquals(8, event.getAmount());
    }

    @SuppressWarnings("unchecked")
    private static FatigueService fatigueWith(UUID playerId, long restedUntil) throws Exception {
        FatigueService fatigue = new FatigueService(mock(InsomniaConfig.class), null, mock(ActivityService.class));
        Field states = FatigueService.class.getDeclaredField("states");
        states.setAccessible(true);
        PlayerFatigueState state = new PlayerFatigueState(playerId, 0L);
        state.restedUntilEpochMillis(restedUntil);
        ((Map<UUID, PlayerFatigueState>) states.get(fatigue)).put(playerId, state);
        return fatigue;
    }
}
