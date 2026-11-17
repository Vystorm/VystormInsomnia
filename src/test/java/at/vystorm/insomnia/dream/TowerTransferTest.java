package at.vystorm.insomnia.dream;

import at.vystorm.insomnia.dream.world.DreamLayout;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TowerTransferTest {
    private final World world = mock(World.class);
    private final Player player = mock(Player.class);
    private final Block feet = mock(Block.class), head = mock(Block.class), floor = mock(Block.class);
    private final AtomicReference<Location> location = new AtomicReference<>();
    private final CompletableFuture<Chunk> loaded = new CompletableFuture<>();
    private final TowerTransfer transfer = new TowerTransfer(Vystorm.vystorm_core.compat.PlatformCompatInterface.DEFAULT, Vystorm.vystorm_core.compat.GameplayCompatInterface.DEFAULT);
    private final DreamLayout.Tower tower = new DreamLayout.Tower("dawn", 0,
            new DreamLayout.BlockPos(26,98,0), new DreamLayout.BlockPos(26,97,2), new DreamLayout.BlockPos(1050,97,2));

    @BeforeEach void fixture() {
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(player.isOnline()).thenReturn(true);
        when(player.getWorld()).thenReturn(world);
        location.set(new Location(world,26.5,97,2.5,90,0));
        when(player.getLocation()).thenAnswer(call -> location.get().clone());
        when(world.getChunkAtAsync(anyInt(),anyInt(),eq(true))).thenReturn(loaded);
        when(world.isChunkLoaded(anyInt(),anyInt())).thenReturn(true);
        when(world.getBlockAt(anyInt(),anyInt(),anyInt())).thenReturn(feet);
        when(world.getBlockAt(any(Location.class))).thenReturn(feet);
        when(feet.isPassable()).thenReturn(true);
        when(feet.getRelative(0,1,0)).thenReturn(head);
        when(head.isPassable()).thenReturn(true);
        when(feet.getRelative(0,-1,0)).thenReturn(floor);
        when(floor.isSolid()).thenReturn(true);
        when(player.teleport(any(Location.class),eq(PlayerTeleportEvent.TeleportCause.PLUGIN))).thenAnswer(call -> {
            location.set(((Location)call.getArgument(0)).clone()); return true;
        });
    }
    @Test void waitsForReadyChunkThenCommitsOnceAndHonorsCooldown() {
        assertEquals(TowerTransfer.Result.WAITING,transfer.request(player,tower,1000,true,750));
        assertEquals(TowerTransfer.Result.REFUSED,transfer.request(player,tower,1001,true,750));
        assertEquals(TowerTransfer.Result.WAITING,transfer.tick(player,1800,true,3000));
        loaded.complete(mock(Chunk.class));
        assertEquals(TowerTransfer.Result.ARRIVED,transfer.tick(player,1900,true,3000));
        assertEquals(1,transfer.side());
        assertEquals(1050.5,location.get().getX());
        assertEquals(TowerTransfer.Result.IDLE,transfer.tick(player,1901,true,3000));
        DreamLayout.Tower reverse = new DreamLayout.Tower("dawn",1,new DreamLayout.BlockPos(1050,98,0),tower.destination(),tower.arrival());
        assertEquals(TowerTransfer.Result.REFUSED,transfer.request(player,reverse,2000,true,0));
        assertEquals(TowerTransfer.Result.WAITING,transfer.request(player,reverse,5000,true,0));
        assertEquals(TowerTransfer.Result.ARRIVED,transfer.tick(player,5001,true,3000));
        assertEquals(0,transfer.side());
        verify(player,times(2)).teleport(any(Location.class),eq(PlayerTeleportEvent.TeleportCause.PLUGIN));
    }
    @Test void deadlineOrCombatCancellationCannotTeleportWhenChunkCompletesLater() {
        transfer.request(player,tower,1000,true,0);
        assertEquals(TowerTransfer.Result.REFUSED,transfer.tick(player,1100,false,3000));
        loaded.complete(mock(Chunk.class));
        assertEquals(TowerTransfer.Result.IDLE,transfer.tick(player,1200,true,3000));
        assertEquals(0,transfer.side());
        verify(player,never()).teleport(any(Location.class),any(PlayerTeleportEvent.TeleportCause.class));
    }
    @Test void blockedDestinationOrCancelledTeleportDoesNotChangeSide() {
        loaded.complete(mock(Chunk.class));
        when(head.isPassable()).thenReturn(false);
        transfer.request(player,tower,1000,true,0);
        assertEquals(TowerTransfer.Result.FAILED,transfer.tick(player,1100,true,3000));
        assertEquals(0,transfer.side());
        when(head.isPassable()).thenReturn(true);
        when(player.teleport(any(Location.class),any(PlayerTeleportEvent.TeleportCause.class))).thenReturn(false);
        transfer.request(player,tower,1200,true,0);
        assertEquals(TowerTransfer.Result.FAILED,transfer.tick(player,1300,true,3000));
        assertEquals(0,transfer.side());
    }
    @Test void movingAwayOrDisconnectCancelsPendingTransfer() {
        transfer.request(player,tower,1000,true,0);
        location.set(new Location(world,40,97,2));
        assertEquals(TowerTransfer.Result.REFUSED,transfer.tick(player,1100,true,3000));
        assertFalse(transfer.pending());
        location.set(new Location(world,26.5,97,2.5));
        transfer.request(player,tower,1200,true,0);
        when(player.isOnline()).thenReturn(false);
        assertEquals(TowerTransfer.Result.REFUSED,transfer.tick(player,1300,true,3000));
        assertEquals(0,transfer.side());
    }
    @Test void failedChunkAndTimeoutDoNotBlockFutureRequests() {
        transfer.request(player,tower,1000,true,0);
        assertEquals(TowerTransfer.Result.REFUSED,transfer.tick(player,11000,true,3000));
        transfer.request(player,tower,12000,true,0);
        loaded.completeExceptionally(new IllegalStateException("test failure"));
        assertEquals(TowerTransfer.Result.FAILED,transfer.tick(player,12100,true,3000));
        assertFalse(transfer.pending());
    }
    @Test void alteredTeleportDestinationFailsClosed() {
        loaded.complete(mock(Chunk.class));
        when(player.teleport(any(Location.class),any(PlayerTeleportEvent.TeleportCause.class))).thenReturn(true);
        transfer.request(player,tower,1000,true,0);
        assertEquals(TowerTransfer.Result.MISDIRECTED,transfer.tick(player,1100,true,3000));
        assertEquals(0,transfer.side());
    }
}
