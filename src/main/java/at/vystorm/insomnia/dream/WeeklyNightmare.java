package at.vystorm.insomnia.dream;

import java.util.*;

/**
 * Weekly nightmare challenge: every week (Monday 00:00 UTC) the Nightmare Keep gets a rotating set of modifiers, the
 * fastest clears of the week form a board and the first clear of the week earns an extra reward. Modifiers are derived
 * from the week number and a server seed, so every server restart picks the same set. Pure logic, no Bukkit access.
 */
public final class WeeklyNightmare {
    static final long DAY = 86_400_000L, WEEK = 7 * DAY;
    /** 1970-01-01 was a Thursday; weeks start four days later on Monday. */
    private static final long MONDAY_OFFSET = 4 * DAY;

    public enum Modifier {
        /** Wardens move faster (Speed I). */ SWIFT,
        /** Wardens take less damage (Resistance I). */ ARMORED,
        /** Wardens hit harder (Strength I). */ FEROCIOUS,
        /** Two more wardens per wave. */ HORDE,
        /** Darkness pulses over the dreamers each wave. */ DARKNESS;

        public String key() { return name().toLowerCase(Locale.ROOT); }
    }

    private WeeklyNightmare() {}

    public static long week(long millis) { return Math.floorDiv(millis - MONDAY_OFFSET, WEEK); }

    /** Start of the next week in epoch millis. */
    public static long weekEnd(long week) { return (week + 1) * WEEK + MONDAY_OFFSET; }

    /** {@code count} distinct modifiers (1 to all) for {@code week}, the same on every call. */
    public static List<Modifier> modifiers(long week, long seed, int count) {
        List<Modifier> all = new ArrayList<>(List.of(Modifier.values()));
        Collections.shuffle(all, new Random(seed * 31 + week * 0x9E3779B97F4A7C15L));
        return List.copyOf(all.subList(0, Math.clamp(count, 1, all.size())));
    }

    /** Best clear times of one week; a new week empties the board. */
    public static final class Board {
        private long week;
        private final Map<UUID, Long> best = new HashMap<>();
        private final Map<UUID, String> names = new HashMap<>();

        public Board(long week) { this.week = week; }

        public long week() { return week; }

        /** Starts a new week when {@code now} is past this board's; true if it was reset. */
        public boolean roll(long currentWeek) {
            if (currentWeek == week) return false;
            week = currentWeek; best.clear(); names.clear();
            return true;
        }

        public boolean cleared(UUID player) { return best.containsKey(player); }

        public OptionalLong best(UUID player) { Long time = best.get(player); return time == null ? OptionalLong.empty() : OptionalLong.of(time); }

        /** Stores a clear; true if it is the player's best this week. */
        public boolean record(UUID player, String name, long millis) {
            if (millis <= 0) return false;
            Long old = best.get(player);
            names.put(player, name == null ? "?" : name);
            if (old != null && old <= millis) return false;
            best.put(player, millis);
            return true;
        }

        /** 1-based place of the player, or 0. */
        public int rank(UUID player) {
            List<Entry> all = top(Integer.MAX_VALUE);
            for (int i = 0; i < all.size(); i++) if (all.get(i).player().equals(player)) return i + 1;
            return 0;
        }

        public List<Entry> top(int limit) {
            return best.entrySet().stream()
                    .map(e -> new Entry(e.getKey(), names.getOrDefault(e.getKey(), "?"), e.getValue()))
                    .sorted(Comparator.comparingLong(Entry::millis).thenComparing(Entry::name))
                    .limit(Math.max(0, limit)).toList();
        }

        public int size() { return best.size(); }

        /** Flat form for storage: uuid → "millis name". */
        public Map<String, String> encode() {
            Map<String, String> out = new TreeMap<>();
            best.forEach((id, time) -> out.put(id.toString(), time + " " + names.getOrDefault(id, "?")));
            return out;
        }

        public static Board decode(long week, Map<String, String> stored) {
            Board board = new Board(week);
            if (stored == null) return board;
            stored.forEach((id, value) -> {
                try {
                    int space = value.indexOf(' ');
                    long time = Long.parseLong(space < 0 ? value : value.substring(0, space));
                    board.record(UUID.fromString(id), space < 0 ? "?" : value.substring(space + 1), time);
                } catch (RuntimeException ignored) { /* skip a damaged row */ }
            });
            return board;
        }
    }

    public record Entry(UUID player, String name, long millis) {}

    /** m:ss.t for board lines. */
    public static String format(long millis) {
        long tenths = Math.max(0, millis) / 100;
        return (tenths / 600) + ":" + String.format(Locale.ROOT, "%02d", tenths / 10 % 60) + "." + (tenths % 10);
    }
}
