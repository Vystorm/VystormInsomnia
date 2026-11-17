package at.vystorm.insomnia.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Bounded, memory-only personal fatigue timeline. Entries are discarded on logout/restart. */
public final class PlayerInsightHistory {
    public static final int MAX_ENTRIES = 24;
    public record Entry(long atMillis, double fatigue, double debt, String marker) {}
    private final Map<UUID, Deque<Entry>> entries = new HashMap<>();

    public void sample(UUID player, long atMillis, double fatigue, double debt) {
        Deque<Entry> history = entries.computeIfAbsent(player, ignored -> new ArrayDeque<>());
        Entry last = history.peekLast();
        if (last != null && atMillis - last.atMillis() < 60_000L) return;
        add(player, new Entry(atMillis, fatigue, debt, "sample"));
    }

    public void mark(UUID player, long atMillis, double fatigue, double debt, String marker) {
        add(player, new Entry(atMillis, fatigue, debt, marker));
    }

    private void add(UUID player, Entry entry) {
        Deque<Entry> history = entries.computeIfAbsent(player, ignored -> new ArrayDeque<>());
        history.addLast(entry);
        while (history.size() > MAX_ENTRIES) history.removeFirst();
    }

    public List<Entry> recent(UUID player) {
        Deque<Entry> history = entries.get(player);
        return history == null ? List.of() : List.copyOf(new ArrayList<>(history));
    }

    public void forget(UUID player) { entries.remove(player); }
}
