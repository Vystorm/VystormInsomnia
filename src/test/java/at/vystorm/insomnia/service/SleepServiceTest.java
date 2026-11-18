package at.vystorm.insomnia.service;

import at.vystorm.insomnia.api.SleepStage;
import at.vystorm.insomnia.config.InsomniaConfig;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SleepServiceTest {
    @Test void dreamUsesBedQualityAndElapsedTimeWithoutFullRestReset() {
        var config = at.vystorm.insomnia.SwitchDefaults.on(mock(InsomniaConfig.class));
        when(config.sleepRecoveryPerSecond()).thenReturn(.1);
        when(config.debtRecoveryPerSecond()).thenReturn(.05);
        when(config.fallingAsleepMillis()).thenReturn(10_000L);
        when(config.lightSleepMillis()).thenReturn(20_000L);
        when(config.deepSleepMillis()).thenReturn(30_000L);
        var fatigue = mock(FatigueService.class);
        var persistence = mock(PersistenceService.class);
        var quality = mock(SleepQualityService.class);
        var rested = mock(RestedBonusService.class);
        var stimulants = mock(StimulantService.class);
        var mobs = mock(InsomniaMobService.class);
        var player = mock(org.bukkit.entity.Player.class);
        var id = java.util.UUID.randomUUID();
        var state = new at.vystorm.insomnia.model.PlayerFatigueState(id, 1000);
        state.fatigue(80); state.sleepDebt(40);
        when(player.getUniqueId()).thenReturn(id); when(player.isOnline()).thenReturn(true);
        when(fatigue.ready(id)).thenReturn(true); when(fatigue.require(id)).thenReturn(state);
        var manager = mock(org.bukkit.plugin.PluginManager.class);
        try (var bukkit = org.mockito.Mockito.mockStatic(org.bukkit.Bukkit.class)) {
            bukkit.when(() -> org.bukkit.Bukkit.getPlayer(id)).thenReturn(player);
            bukkit.when(org.bukkit.Bukkit::getPluginManager).thenReturn(manager);
            var sleep = new SleepService(config, fatigue, persistence, quality, rested, stimulants, mobs);
            org.junit.jupiter.api.Assertions.assertTrue(sleep.startDream(player, 1000, .5));
            sleep.tick(61_000);
            assertEquals(77, state.fatigue(), .0001);
            assertEquals(38.5, state.sleepDebt(), .0001);
            org.mockito.Mockito.verifyNoInteractions(quality, rested, stimulants);
            org.junit.jupiter.api.Assertions.assertTrue(sleep.isDreamSleeping(id));
        }
    }

    @Test
    void sleepPhaseBoundariesReachRestedOnlyAfterFullDeepSleepDuration() throws Exception {
        InsomniaConfig config = at.vystorm.insomnia.SwitchDefaults.on(mock(InsomniaConfig.class));
        when(config.fallingAsleepMillis()).thenReturn(10_000L);
        when(config.lightSleepMillis()).thenReturn(20_000L);
        when(config.deepSleepMillis()).thenReturn(30_000L);
        SleepService service = new SleepService(config, null, null, null, null, null, null);
        Method stageAt = SleepService.class.getDeclaredMethod("stageAt", long.class);
        stageAt.setAccessible(true);

        assertEquals(SleepStage.FALLING_ASLEEP, stageAt(stageAt, service, 9_999L));
        assertEquals(SleepStage.LIGHT_SLEEP, stageAt(stageAt, service, 10_000L));
        assertEquals(SleepStage.DEEP_SLEEP, stageAt(stageAt, service, 30_000L));
        assertEquals(SleepStage.RESTED, stageAt(stageAt, service, 60_000L));
    }

    private static SleepStage stageAt(Method method, SleepService service, long elapsed) throws Exception {
        return (SleepStage) method.invoke(service, elapsed);
    }
}
