package at.vystorm.insomnia.service;

import at.vystorm.insomnia.config.InsomniaConfig;
import at.vystorm.insomnia.testutil.TestServer;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class HallucinationVisibilityTest {
    @Test
    void leavingClearsTheHallucinationCheckEvenWithoutActiveSightLoss() {
        Server server = TestServer.fresh();
        when(server.getScheduler()).thenReturn(mock(BukkitScheduler.class));
        Player player = player(server);
        when(server.getOnlinePlayers()).thenAnswer(ignored -> List.of(player));
        InsomniaConfig config = mock(InsomniaConfig.class);
        when(config.hallucinationsEnabled()).thenReturn(true);
        when(config.hallucinationCheckMillis()).thenReturn(60_000L);
        FatigueService fatigue = mock(FatigueService.class);
        when(fatigue.stage(player.getUniqueId())).thenReturn(at.vystorm.insomnia.api.InsomniaStage.RESTED);
        JavaPlugin plugin = mock(JavaPlugin.class);
        HallucinationService service = new HallucinationService(plugin, config, at.vystorm.insomnia.testutil.TestCore.bridge(), fatigue,
                mock(SleepService.class), new VisibilityLocks(plugin));
        service.tick(1_000L);
        service.restoreVisibility(player.getUniqueId()); // GameplayListener's logout cleanup
        service.tick(2_000L); // same player reconnected
        verify(fatigue, times(2)).stage(player.getUniqueId());
    }

    @Test
    void overlappingSightLossKeepsOthersHiddenUntilTheLastOneEnds() throws Exception {
        Server server = TestServer.fresh();
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(server.getScheduler()).thenReturn(scheduler);
        JavaPlugin plugin = mock(JavaPlugin.class);
        Player viewer = player(server);
        Player other = player(server);
        when(server.getOnlinePlayers()).thenAnswer(ignored -> List.of(viewer, other));
        InsomniaConfig config = mock(InsomniaConfig.class);
        when(config.sightLossTicks()).thenReturn(80L);
        HallucinationService service = new HallucinationService(plugin, config, at.vystorm.insomnia.testutil.TestCore.bridge(), null, null, new VisibilityLocks(plugin));

        Method sightLoss = HallucinationService.class.getDeclaredMethod("hideOthersTemporarily", Player.class);
        sightLoss.setAccessible(true);
        sightLoss.invoke(service, viewer);
        sightLoss.invoke(service, viewer);
        ArgumentCaptor<Runnable> restores = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler, times(2)).runTaskLater(eq(plugin), restores.capture(), anyLong());
        verify(viewer, times(1)).hidePlayer(plugin, other);

        restores.getAllValues().get(0).run();
        verify(viewer, never()).showPlayer(any(JavaPlugin.class), any(Player.class));
        restores.getAllValues().get(1).run();
        verify(viewer).showPlayer(plugin, other);
    }

    private static Player player(Server server) {
        Player player = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        when(server.getPlayer(id)).thenReturn(player);
        return player;
    }
}
