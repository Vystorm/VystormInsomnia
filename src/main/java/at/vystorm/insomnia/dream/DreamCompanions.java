package at.vystorm.insomnia.dream;

import java.util.*;

/**
 * Shared dreams: a dreamer invites other dreamers as companions. Companions see the dreamer's nightmare wardens, may
 * fight them and are fought by them; their kills count for the dreamer's waves. A player is the companion of at most
 * one dreamer and cannot be a dreamer with companions at the same time. Invitations expire. In memory only (dream
 * visits end on restart anyway). Server thread; no Bukkit access.
 */
final class DreamCompanions {
    private final Map<UUID, LinkedHashSet<UUID>> companions = new HashMap<>();
    private final Map<UUID, UUID> hostOf = new HashMap<>();
    /** invited player → (host → expiry millis) */
    private final Map<UUID, Map<UUID, Long>> invites = new HashMap<>();

    /** Result codes: ok, self, already, host-busy, target-busy, full. */
    String invite(UUID host, UUID target, long now, long ttlMillis, int max) {
        if (host.equals(target)) return "self";
        if (hostOf.containsKey(host)) return "host-busy";
        if (hostOf.containsKey(target) || companions.containsKey(target)) return hostOf.get(target) != null && hostOf.get(target).equals(host) ? "already" : "target-busy";
        if (companions.getOrDefault(host, new LinkedHashSet<>()).size() >= Math.max(1, max)) return "full";
        invites.computeIfAbsent(target, k -> new HashMap<>()).put(host, now + ttlMillis);
        return "ok";
    }

    /** Result codes: ok, no-invite, host-busy, target-busy, full. */
    String accept(UUID target, UUID host, long now, int max) {
        Map<UUID, Long> mine = invites.get(target);
        Long until = mine == null ? null : mine.get(host);
        if (until == null || until < now) return "no-invite";
        if (hostOf.containsKey(host)) return "host-busy";
        if (hostOf.containsKey(target) || companions.containsKey(target)) return "target-busy";
        LinkedHashSet<UUID> set = companions.computeIfAbsent(host, k -> new LinkedHashSet<>());
        if (set.size() >= Math.max(1, max)) return "full";
        set.add(target);
        hostOf.put(target, host);
        invites.remove(target);
        return "ok";
    }

    /** Hosts with an open invitation for {@code target}, newest expiry first. */
    List<UUID> invitesFor(UUID target, long now) {
        Map<UUID, Long> mine = invites.getOrDefault(target, Map.of());
        return mine.entrySet().stream().filter(e -> e.getValue() >= now)
                .sorted(Map.Entry.<UUID, Long>comparingByValue().reversed()).map(Map.Entry::getKey).toList();
    }

    /** Whether {@code player} belongs to {@code host}'s dream (the host or one of the companions). */
    boolean shares(UUID host, UUID player) {
        return host != null && player != null && (host.equals(player) || host.equals(hostOf.get(player)));
    }

    Set<UUID> companionsOf(UUID host) { return Collections.unmodifiableSet(companions.getOrDefault(host, new LinkedHashSet<>())); }
    Optional<UUID> hostOf(UUID companion) { return Optional.ofNullable(hostOf.get(companion)); }

    /**
     * A player leaves every shared dream (woke up, quit, left on purpose). Returns the bonds that ended as
     * host → companion pairs, so the caller can hide wardens and tell everyone.
     */
    List<Map.Entry<UUID, UUID>> leave(UUID player) {
        List<Map.Entry<UUID, UUID>> ended = new ArrayList<>();
        UUID host = hostOf.remove(player);
        if (host != null) {
            Set<UUID> set = companions.get(host);
            if (set != null) { set.remove(player); if (set.isEmpty()) companions.remove(host); }
            ended.add(Map.entry(host, player));
        }
        Set<UUID> mine = companions.remove(player);
        if (mine != null) for (UUID companion : mine) { hostOf.remove(companion); ended.add(Map.entry(player, companion)); }
        invites.remove(player);
        for (Map<UUID, Long> m : invites.values()) m.remove(player);
        invites.values().removeIf(Map::isEmpty);
        return ended;
    }

    void clear() { companions.clear(); hostOf.clear(); invites.clear(); }
}
