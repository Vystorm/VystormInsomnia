package at.vystorm.insomnia.sleep;

import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Whether the server and player's client can show native dialogs. Bedrock players joining through
 * Geyser/Floodgate cannot and get clickable chat instead. Floodgate is looked up by reflection, so it stays optional.
 */
public final class DialogSupport {
    private static Object FLOODGATE;
    private static Method IS_FLOODGATE_PLAYER;
    private static boolean resolved;

    /** Looked up on first use, after every plugin is enabled. */
    private static synchronized void resolve() {
        if (resolved) return;
        resolved = true;
        Object api = null;
        Method check = null;
        try {
            Class<?> type = Class.forName("org.geysermc.floodgate.api.FloodgateApi", false, DialogSupport.class.getClassLoader());
            api = type.getMethod("getInstance").invoke(null);
            check = type.getMethod("isFloodgatePlayer", UUID.class);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException absent) {
            api = null;
            check = null;
        }
        FLOODGATE = api;
        IS_FLOODGATE_PLAYER = check;
    }

    private DialogSupport() {}

    /** @param server Core's server part, {@code api.compat().server()} */
    public static boolean supported(Vystorm.vystorm_core.compat.ServerCompatInterface server, Player player) {
        if (!server.hasDialogs()) return false;
        resolve();
        if (FLOODGATE == null || IS_FLOODGATE_PLAYER == null) return true;
        try {
            return !Boolean.TRUE.equals(IS_FLOODGATE_PLAYER.invoke(FLOODGATE, player.getUniqueId()));
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return true;
        }
    }
}
