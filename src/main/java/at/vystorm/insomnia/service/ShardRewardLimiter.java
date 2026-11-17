package at.vystorm.insomnia.service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Rolling, owner-specific reward budget for ECHO_SHARD drops only. Call at the existing drop roll. */
final class ShardRewardLimiter {
    private final Map<UUID, Deque<Long>> awarded = new HashMap<>();

    double chance(UUID owner, long now, long windowMillis, int cap, int diminishAfter) {
        Deque<Long> times = awarded.computeIfAbsent(owner, ignored -> new ArrayDeque<>());
        while (!times.isEmpty() && now - times.peekFirst() >= windowMillis) times.removeFirst();
        int count = times.size();
        if (cap <= count) return 0.0;
        if (count < diminishAfter) return 1.0;
        return Math.pow(0.5, count - diminishAfter + 1);
    }

    void awarded(UUID owner, long now) { awarded.computeIfAbsent(owner, ignored -> new ArrayDeque<>()).addLast(now); }
    void prune(long now, long windowMillis) {
        awarded.entrySet().removeIf(entry -> {
            Deque<Long> times = entry.getValue();
            while (!times.isEmpty() && now - times.peekFirst() >= windowMillis) times.removeFirst();
            return times.isEmpty();
        });
    }

    int count(UUID owner, long now, long windowMillis) {
        Deque<Long> times = awarded.get(owner);
        if (times == null) return 0;
        while (!times.isEmpty() && now - times.peekFirst() >= windowMillis) times.removeFirst();
        if (times.isEmpty()) awarded.remove(owner);
        return times.size();
    }
}
