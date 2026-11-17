package at.vystorm.insomnia.compat.paper26;

import java.util.Iterator;

/**
 * Bukkit's bed-leave event exposes no cause. Vanilla's
 * daylight wake originates directly in Player.tick; client button, damage, teleport and
 * plugin wakeups have other immediate callers. No server classes are linked or invoked.
 * Unknown call paths fail open (allow waking), never trap a player in bed.
 * 1.20.6 and 26.2/26.3 were checked against the server jars: PlayerBedLeaveEvent is fired in
 * ServerPlayer#stopSleepInBed, which Player#tick calls directly for the daylight wake.
 */
public final class BedLeaveOrigin {
    private static final StackWalker WALKER = StackWalker.getInstance();
    private BedLeaveOrigin() {}
    public static boolean automaticDaylightWake() {
        return WALKER.walk(frames -> automatic(frames.map(f -> f.getClassName() + "#" + f.getMethodName()).iterator()));
    }
    static boolean automatic(Iterator<String> frames) {
        while (frames.hasNext()) {
            if (frames.next().equals("net.minecraft.server.level.ServerPlayer#stopSleepInBed")) {
                return frames.hasNext() && frames.next().equals("net.minecraft.world.entity.player.Player#tick");
            }
        }
        return false;
    }
}
