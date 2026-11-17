package at.vystorm.insomnia.dream;

import at.vystorm.insomnia.dream.world.DreamLayout;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.Vector;

import java.util.concurrent.CompletableFuture;

/** One visit's tower state. All methods run on the server thread; chunk futures only signal readiness. */
final class TowerTransfer {
    enum Result { IDLE, WAITING, ARRIVED, REFUSED, FAILED, MISDIRECTED }
    private final Vystorm.vystorm_core.compat.PlatformCompatInterface platform;
    private final Vystorm.vystorm_core.compat.GameplayCompatInterface gameplay;
    private int side;
    private long cooldownUntil;
    private Pending pending;

    /** @param platform Core's {@code api.compat().platform()} @param gameplay Core's {@code api.compat().gameplay()} */
    TowerTransfer(Vystorm.vystorm_core.compat.PlatformCompatInterface platform, Vystorm.vystorm_core.compat.GameplayCompatInterface gameplay) {
        this.platform = platform;
        this.gameplay = gameplay;
    }

    int side() { return side; }
    boolean pending() { return pending != null; }

    Result request(Player player, DreamLayout.Tower tower, long now, boolean allowed, long delayMillis) {
        if (!allowed || pending != null || now < cooldownUntil || tower.side() != side || !player.isOnline() || player.isDead()) return Result.REFUSED;
        Location button = at(player.getWorld(), tower.button());
        if (player.getLocation().distanceSquared(button) > 30.25) return Result.REFUSED;
        Location target = at(player.getWorld(), tower.destination());
        target.setYaw(player.getLocation().getYaw());
        target.setPitch(0);
        try {
            CompletableFuture<Chunk> ready = platform.getChunkAtAsync(target.getWorld(), target.getBlockX() >> 4, target.getBlockZ() >> 4, true);
            pending = new Pending(player.getLocation().clone(), target, ready, now + delayMillis, now + 10_000L);
            return Result.WAITING;
        } catch (RuntimeException failure) { return Result.FAILED; }
    }

    Result tick(Player player, long now, boolean allowed, long cooldownMillis) {
        Pending transfer = pending;
        if (transfer == null) return Result.IDLE;
        if (!allowed || !player.isOnline() || player.isDead() || now >= transfer.expires
                || !transfer.origin.getWorld().equals(player.getWorld())
                || transfer.origin.distanceSquared(player.getLocation()) > 16) {
            pending = null; return Result.REFUSED;
        }
        if (!transfer.ready.isDone() || now < transfer.notBefore) return Result.WAITING;
        pending = null;
        if (transfer.ready.isCancelled() || transfer.ready.isCompletedExceptionally()) return Result.FAILED;
        if (!safe(transfer.target)) return Result.FAILED;
        // The destination chunk is already loaded. A synchronous teleport keeps the commit atomic on this thread.
        boolean moved;
        try { moved = player.teleport(transfer.target, PlayerTeleportEvent.TeleportCause.PLUGIN); }
        catch (RuntimeException failure) { return Result.FAILED; }
        if (!moved) return Result.FAILED;
        if (!transfer.target.getWorld().equals(player.getWorld()) || player.getLocation().distanceSquared(transfer.target) > 4) return Result.MISDIRECTED;
        side = 1 - side;
        cooldownUntil = now + cooldownMillis;
        player.setFallDistance(0);
        player.setVelocity(new Vector());
        return Result.ARRIVED;
    }

    void cancel() { pending = null; }

    private static Location at(World world, DreamLayout.BlockPos pos) { return new Location(world, pos.x() + .5, pos.y(), pos.z() + .5); }
    private boolean safe(Location feet) {
        World world = feet.getWorld();
        if (!world.isChunkLoaded(feet.getBlockX() >> 4, feet.getBlockZ() >> 4)) return false;
        Block block = feet.getBlock();
        return block.isPassable() && block.getRelative(0, 1, 0).isPassable() && gameplay.isSolid(block.getRelative(0, -1, 0));
    }
    private record Pending(Location origin, Location target, CompletableFuture<Chunk> ready, long notBefore, long expires) {}
}
