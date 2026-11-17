package at.vystorm.insomnia.sleep;

import java.util.Collection;
import java.util.Locale;
import java.util.OptionalLong;

/**
 * Minecraft clock maths. A day has 24000 ticks; tick 0 of a day is 06:00. A wake-up time is a clock time of the
 * <em>coming day</em>: the day that starts at the next 06:00 and ends at 05:59 the night after. "06:00" is the next
 * morning, "12:00" the noon after it and "22:00" the following night.
 */
public final class WakeTimes {
    public static final long DAY = 24_000L;
    /** One in-game hour. */
    public static final long HOUR = 1_000L;

    private WakeTimes() {}

    /** {@code hh:mm} as ticks after 06:00 (0..23999), or -1 when the text is not a valid clock time. */
    public static int parseClock(String text) {
        if (text == null) return -1;
        String trimmed = text.trim();
        int colon = trimmed.indexOf(':');
        if (colon < 1 || colon > 2 || trimmed.length() != colon + 3) return -1;
        int hours;
        int minutes;
        try {
            hours = Integer.parseInt(trimmed.substring(0, colon));
            minutes = Integer.parseInt(trimmed.substring(colon + 1));
        } catch (NumberFormatException invalid) {
            return -1;
        }
        if (hours < 0 || hours > 23 || minutes < 0 || minutes > 59) return -1;
        return clockTicks(hours, minutes);
    }

    /** Ticks after 06:00 for a wall-clock time. */
    public static int clockTicks(int hours, int minutes) {
        int sinceMidnight = hours * 1000 + Math.round(minutes * 1000f / 60f);
        return Math.floorMod(sinceMidnight - 6000, (int) DAY);
    }

    /** The wall-clock time {@code hh:mm} of a world time (full or day time). */
    public static String clock(long time) {
        long ticks = Math.floorMod(time + 6000L, DAY);
        long hours = ticks / 1000L;
        long minutes = (ticks % 1000L) * 60L / 1000L;
        return String.format(Locale.ROOT, "%02d:%02d", hours, minutes);
    }

    /** Full time of the next 06:00 strictly after {@code fullTime}. */
    public static long nextMorning(long fullTime) {
        return Math.floorDiv(fullTime, DAY) * DAY + DAY;
    }

    /** Absolute wake-up time: {@code clockTicks} on the coming day. */
    public static long target(long fullTime, int clockTicks) {
        return nextMorning(fullTime) + Math.floorMod(clockTicks, (int) DAY);
    }

    /**
     * Shared rule for several sleepers: the clock runs fast towards the earliest pending wake-up time; each sleeper
     * still wakes at their own time, and the next earliest one takes over.
     */
    public static OptionalLong earliest(Collection<Long> targets) {
        return targets.stream().mapToLong(Long::longValue).min();
    }
}
