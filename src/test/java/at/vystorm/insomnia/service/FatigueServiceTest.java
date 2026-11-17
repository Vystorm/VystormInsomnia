package at.vystorm.insomnia.service;

import at.vystorm.insomnia.api.InsomniaStage;
import at.vystorm.insomnia.config.InsomniaConfig;
import at.vystorm.insomnia.event.InsomniaStageChangeEvent;
import at.vystorm.insomnia.model.PlayerFatigueState;
import at.vystorm.insomnia.testutil.TestServer;
import org.bukkit.Server;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FatigueServiceTest {
    @Test
    @SuppressWarnings("unchecked")
    void staleDatabaseCallbackCannotInstallStateIntoAReconnectedSession() {
        Server server = TestServer.fresh();
        PersistenceService database = mock(PersistenceService.class);
        UUID id = UUID.randomUUID();
        Player first = mock(Player.class), reconnected = mock(Player.class);
        when(first.getUniqueId()).thenReturn(id);
        when(reconnected.getUniqueId()).thenReturn(id);
        when(server.getPlayer(id)).thenReturn(first);
        FatigueService service = new FatigueService(config, database, activity);
        service.load(first, 0L);
        service.unload(first);
        when(server.getPlayer(id)).thenReturn(reconnected);
        service.load(reconnected, 1_000L);
        ArgumentCaptor<java.util.function.Consumer<PlayerFatigueState>> callbacks = ArgumentCaptor.forClass(java.util.function.Consumer.class);
        verify(database, times(2)).loadAsync(eq(id), callbacks.capture());
        PlayerFatigueState old = new PlayerFatigueState(id, 0L);
        old.fatigue(85);
        callbacks.getAllValues().get(0).accept(old);
        assertFalse(service.ready(id), "a callback from the previous connection must not make the new one ready");
        PlayerFatigueState current = new PlayerFatigueState(id, 1_000L);
        current.fatigue(40);
        callbacks.getAllValues().get(1).accept(current);
        assertSame(current, service.require(id));
        verify(database, never()).release(id);
    }

    private InsomniaConfig config;
    private ActivityService activity;
    private SleepService sleep;
    private PluginManager plugins;
    private FatigueService service;
    private Player player;
    private PlayerFatigueState state;

    @BeforeEach
    void setUp() {
        Server server = TestServer.fresh();
        plugins = mock(PluginManager.class);
        when(server.getPluginManager()).thenReturn(plugins);
        config = mock(InsomniaConfig.class);
        when(config.fatigueEnabled()).thenReturn(true);
        when(config.fatigueUpdateIntervalTicks()).thenReturn(100L);
        when(config.baseFatiguePerMinute()).thenReturn(1.0);
        when(config.debtPerMinute()).thenReturn(0.5);
        when(config.stageThresholds()).thenReturn(List.of(20.0, 40.0, 60.0, 80.0));
        activity = mock(ActivityService.class);
        sleep = mock(SleepService.class);
        PersistenceService persistence = mock(PersistenceService.class);
        when(persistence.synchronous()).thenReturn(true);

        UUID id = UUID.randomUUID();
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        state = new PlayerFatigueState(id, 0L);
        state.fatigue(19.0);
        when(persistence.load(any(UUID.class), anyLong())).thenReturn(state);
        when(server.getOnlinePlayers()).thenAnswer(ignored -> List.of(player));
        when(activity.multiplier(id, 60_000L)).thenReturn(2.0);

        service = new FatigueService(config, persistence, activity);
        service.bindSleepService(sleep);
    }

    @Test
    void fatigueAndDebtGrowWithTimeAndActivityAndAnnounceTheNewStage() {
        state.lastUpdateEpochMillis(0L);
        service.tick(60_000L);                         // one minute at double activity

        assertEquals(21.0, state.fatigue(), 1e-9);
        assertEquals(1.0, state.sleepDebt(), 1e-9);
        assertEquals(60_000L, state.lastUpdateEpochMillis());
        ArgumentCaptor<InsomniaStageChangeEvent> event = ArgumentCaptor.forClass(InsomniaStageChangeEvent.class);
        verify(plugins).callEvent(event.capture());
        assertEquals(InsomniaStage.RESTED, event.getValue().previous());
        assertEquals(InsomniaStage.TIRED, event.getValue().current());
        assertSame(player, event.getValue().player());
    }

    @Test
    void ticksInsideTheUpdateIntervalAreSkipped() {
        state.lastUpdateEpochMillis(0L);
        service.tick(60_000L);
        double after = state.fatigue();
        service.tick(64_999L);                         // interval is 100 ticks = 5 s
        assertEquals(after, state.fatigue(), 1e-9);
    }

    @Test
    void sleepingPausesFatigueWithoutBankingTheTime() {
        state.lastUpdateEpochMillis(0L);
        when(sleep.isSleeping(player.getUniqueId())).thenReturn(true);
        service.tick(60_000L);
        assertEquals(19.0, state.fatigue(), 1e-9);
        assertEquals(60_000L, state.lastUpdateEpochMillis(), "slept time must not be charged later");

        when(sleep.isSleeping(player.getUniqueId())).thenReturn(false);
        when(activity.multiplier(player.getUniqueId(), 65_000L)).thenReturn(1.0);
        service.tick(65_000L);
        assertEquals(19.0 + 5.0 / 60.0, state.fatigue(), 1e-9);
        verify(plugins, never()).callEvent(any());
    }

    @Test
    void disabledFatigueDoesNotGrow() {
        when(config.fatigueEnabled()).thenReturn(false);
        state.lastUpdateEpochMillis(0L);
        service.tick(60_000L);
        assertEquals(19.0, state.fatigue(), 1e-9);
    }

    @Test
    void creativeModeCanPauseGrowthAndReportsWhy() {
        when(config.excludeCreative()).thenReturn(true);
        when(player.getGameMode()).thenReturn(GameMode.CREATIVE);
        state.lastUpdateEpochMillis(0L);
        service.tick(60_000L);
        assertEquals(19.0, state.fatigue(), 1e-9);
        assertEquals("creative", service.pausedReason(player));
    }

    @Test
    void spectatorModeCanPauseGrowthAndReportsWhy() {
        when(config.excludeSpectator()).thenReturn(true);
        when(player.getGameMode()).thenReturn(GameMode.SPECTATOR);
        assertEquals("spectator", service.pausedReason(player));
    }

    @Test
    void locallyInactivePlayerReportsSpecificPauseAfterConfiguredThreshold() {
        long now = 300_000L;
        when(config.localAfkPauseEnabled()).thenReturn(true);
        when(config.localAfkThresholdMillis()).thenReturn(300_000L);
        when(activity.locallyAfk(player.getUniqueId(), now, 300_000L)).thenReturn(true);
        assertEquals("local_afk", service.pausedReason(player, now));

        when(config.excludeCreative()).thenReturn(true);
        when(player.getGameMode()).thenReturn(GameMode.CREATIVE);
        assertEquals("creative", service.pausedReason(player, now), "game mode pause has clear priority");
    }
}
