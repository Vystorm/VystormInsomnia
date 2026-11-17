package at.vystorm.insomnia.sleep;

import java.lang.reflect.Method;
import org.bukkit.event.world.TimeSkipEvent;

/**
 * Reads {@link TimeSkipEvent#getSkipReason()} by name. Its return type changed between Paper 1.20.6
 * ({@code TimeSkipEvent.SkipReason}) and 26.x ({@code ClockTimeSkipEvent.SkipReason}), so a direct call
 * links against one of them only.
 */
public final class SkipReasons {
    private static volatile Method getter;

    private SkipReasons() {}

    /** Whether the event skips the night because players sleep. */
    public static boolean isNightSkip(TimeSkipEvent event) {
        return "NIGHT_SKIP".equals(name(event));
    }

    static String name(TimeSkipEvent event) {
        try {
            Method m = getter;
            if (m == null) getter = m = event.getClass().getMethod("getSkipReason");
            Object reason = m.invoke(event);
            return reason instanceof Enum<?> e ? e.name() : String.valueOf(reason);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "";
        }
    }
}
