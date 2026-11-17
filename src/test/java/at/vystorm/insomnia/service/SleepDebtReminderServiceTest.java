package at.vystorm.insomnia.service;

import at.vystorm.insomnia.config.InsomniaConfig;
import at.vystorm.insomnia.model.PlayerFatigueState;
import at.vystorm.insomnia.testutil.TestServer;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SleepDebtReminderServiceTest {
    private Server server;
    private InsomniaConfig config;
    private FatigueService fatigue;
    private Player player;
    private PlayerFatigueState state;
    private SleepDebtReminderService reminders;
    private boolean optedIn;
    private boolean busy;

    @BeforeEach void setUp() {
        server = TestServer.fresh();
        config = mock(InsomniaConfig.class);
        fatigue = mock(FatigueService.class);
        player = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        when(player.hasPermission("insomnia.use")).thenReturn(true);
        doReturn(List.of(player)).when(server).getOnlinePlayers();
        state = new PlayerFatigueState(id, 0L);
        state.sleepDebt(50);
        when(fatigue.state(id)).thenReturn(Optional.of(state));
        when(config.fatigueEnabled()).thenReturn(true);
        when(config.debtReminderEnabled()).thenReturn(true);
        when(config.debtReminderThreshold()).thenReturn(50.0);
        when(config.debtReminderIntervalMillis()).thenReturn(900_000L);
        when(config.message(eq(player), eq("debt_reminder.message"), any(Object[].class))).thenReturn("reminder");
        optedIn = true;
        reminders = new SleepDebtReminderService(config, fatigue, ignored -> optedIn, ignored -> busy);
    }

    @Test void warnsAtThresholdEvenWhenFatigueIsLowAndRepeatsOnlyWhenDue() {
        state.fatigue(0);
        reminders.tick(0);
        reminders.tick(899_999);
        verify(player).sendMessage("reminder");
        reminders.tick(900_000);
        verify(player, times(2)).sendMessage("reminder");
        verify(config, times(2)).message(player, "debt_reminder.message", "debt", 50L);
    }

    @Test void recoveryAndThresholdFluctuationDoNotResetCooldown() {
        reminders.tick(0);
        state.sleepDebt(49);
        reminders.tick(1_000);
        state.sleepDebt(60);
        reminders.tick(2_000);
        verify(player).sendMessage("reminder");
        state.sleepDebt(0);
        reminders.tick(900_000);
        verify(player).sendMessage("reminder");
    }

    @Test void preferenceSuppressesAndDoesNotConsumeReminder() {
        optedIn = false;
        reminders.tick(0);
        verify(player, never()).sendMessage(anyString());
        optedIn = true;
        reminders.tick(1_000);
        verify(player).sendMessage("reminder");
        optedIn = false;
        reminders.tick(2_000);
        optedIn = true;
        reminders.tick(3_000);
        verify(player).sendMessage("reminder");
    }

    @Test void sleepDreamMicrosleepPauseAndDeathDeferReminder() {
        when(player.isSleeping()).thenReturn(true);
        reminders.tick(0);
        when(player.isSleeping()).thenReturn(false);
        when(fatigue.isSleeping(player.getUniqueId())).thenReturn(true);
        reminders.tick(1_000);
        when(fatigue.isSleeping(player.getUniqueId())).thenReturn(false);
        busy = true;
        reminders.tick(2_000);
        busy = false;
        when(fatigue.pausedReason(eq(player), anyLong())).thenReturn("creative");
        reminders.tick(3_000);
        when(fatigue.pausedReason(eq(player), anyLong())).thenReturn(null);
        when(player.isDead()).thenReturn(true);
        reminders.tick(4_000);
        verify(player, never()).sendMessage(anyString());
        when(player.isDead()).thenReturn(false);
        reminders.tick(5_000);
        verify(player).sendMessage("reminder");
    }

    @Test void unloadedStateAndMissingPermissionAreSkipped() {
        when(fatigue.state(player.getUniqueId())).thenReturn(Optional.empty());
        reminders.tick(0);
        when(fatigue.state(player.getUniqueId())).thenReturn(Optional.of(state));
        when(player.hasPermission("insomnia.use")).thenReturn(false);
        reminders.tick(1_000);
        verify(player, never()).sendMessage(anyString());
    }

    @Test void disabledSystemsSuppressAndReloadUsesNewInterval() {
        when(config.debtReminderEnabled()).thenReturn(false);
        reminders.tick(0);
        when(config.debtReminderEnabled()).thenReturn(true);
        when(config.fatigueEnabled()).thenReturn(false);
        reminders.tick(1_000);
        verify(player, never()).sendMessage(anyString());
        when(config.fatigueEnabled()).thenReturn(true);
        reminders.tick(2_000);
        when(config.debtReminderIntervalMillis()).thenReturn(60_000L);
        reminders.tick(62_000);
        verify(player, times(2)).sendMessage("reminder");
    }

    @Test void offlinePlayersAreForgottenAndOtherPlayersHaveIndependentCooldowns() {
        reminders.tick(0);
        Player second = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(second.getUniqueId()).thenReturn(id);
        when(second.hasPermission("insomnia.use")).thenReturn(true);
        when(fatigue.state(id)).thenReturn(Optional.of(state));
        when(config.message(eq(second), anyString(), any(Object[].class))).thenReturn("second");
        doReturn(List.of(player, second)).when(server).getOnlinePlayers();
        reminders.tick(1_000);
        verify(player).sendMessage("reminder");
        verify(second).sendMessage("second");
        doReturn(List.of()).when(server).getOnlinePlayers();
        reminders.tick(2_000);
        doReturn(List.of(player)).when(server).getOnlinePlayers();
        reminders.tick(3_000);
        verify(player, times(2)).sendMessage("reminder");
    }
}
