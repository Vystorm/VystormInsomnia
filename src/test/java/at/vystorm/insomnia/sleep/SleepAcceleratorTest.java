package at.vystorm.insomnia.sleep;

import Vystorm.vystorm_core.schedule.TaskGroup;
import Vystorm.vystorm_core.worlds.ManagedWorlds;
import at.vystorm.insomnia.config.InsomniaConfig;
import at.vystorm.insomnia.testutil.TestServer;
import at.vystorm.insomnia.ui.Messages;
import org.bukkit.GameMode;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SleepAcceleratorTest {
    @Test
    void ignoredSleeperDoesNotAccelerateTimeButStillWakesAtTheChosenTime() {
        Server server = TestServer.fresh();
        World world = mock(World.class);
        UUID worldId = UUID.randomUUID();
        when(world.getUID()).thenReturn(worldId);
        when(world.getEnvironment()).thenReturn(World.Environment.NORMAL);
        when(world.getFullTime()).thenReturn(13_000L);
        when(server.getWorld(worldId)).thenReturn(world);
        Player player = mock(Player.class);
        UUID playerId = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getWorld()).thenReturn(world);
        when(player.isOnline()).thenReturn(true);
        when(player.isSleeping()).thenReturn(true);
        when(player.isSleepingIgnored()).thenReturn(true);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(server.getPlayer(playerId)).thenReturn(player);
        when(world.getPlayers()).thenReturn(List.of(player));
        InsomniaConfig config = mock(InsomniaConfig.class);
        when(config.accelerationEnabled()).thenReturn(true);
        when(config.accelerationMinSpeed()).thenReturn(2.0);
        when(config.accelerationMaxSpeed()).thenReturn(10.0);
        when(config.accelerationCurve()).thenReturn(1.0);
        Plugin plugin = mock(Plugin.class);
        TaskGroup group = mock(TaskGroup.class);
        BukkitTask task = mock(BukkitTask.class);
        when(group.timer(anyLong(), anyLong(), any(Runnable.class))).thenReturn(task);
        try (var groups = mockStatic(TaskGroup.class); var worlds = mockStatic(ManagedWorlds.class);
             var compatibility = mockStatic(Vystorm.vystorm_core.compat.GameplayCompat.class)) {
            groups.when(() -> TaskGroup.of(plugin, "sleep-acceleration")).thenReturn(group);
            SleepAccelerator service = spy(new SleepAccelerator(plugin, at.vystorm.insomnia.testutil.TestCore.api(), config, mock(Messages.class)));
            doReturn(true).when(service).active(world); // registry-backed gamerules are outside this vote-count test
            long target = service.sleepUntil(player, 0).orElseThrow();
            ArgumentCaptor<Runnable> tick = ArgumentCaptor.forClass(Runnable.class);
            verify(group).timer(eq(1L), eq(1L), tick.capture());
            tick.getValue().run();
            verify(world, never()).setFullTime(anyLong());
            verify(task, never()).cancel();
            when(world.getFullTime()).thenReturn(target);
            tick.getValue().run();
            verify(player).wakeup(false);
            assertTrue(service.target(playerId).isEmpty());
            verify(task).cancel();
        }
    }
}
