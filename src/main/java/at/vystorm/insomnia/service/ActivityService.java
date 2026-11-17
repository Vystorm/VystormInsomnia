package at.vystorm.insomnia.service;

import at.vystorm.insomnia.config.InsomniaConfig;
import at.vystorm.insomnia.model.ActivityState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class ActivityService {
    private final InsomniaConfig config;
    private final Map<UUID, ActivityState> states = new HashMap<>();
    /** Main-thread, online-player-only local input timestamps. */
    private final Map<UUID, Long> lastInputAt = new HashMap<>();

    public ActivityService(InsomniaConfig config) {
        this.config = config;
    }

    private ActivityState state(UUID uuid) { return states.computeIfAbsent(uuid, ignored -> new ActivityState()); }

    public void join(Player player) {
        join(player, System.currentTimeMillis());
    }

    public void join(Player player, long now) {
        ActivityState state = state(player.getUniqueId());
        state.sprinting(player.isSprinting());
        state.swimming(player.isSwimming());
        lastInputAt.put(player.getUniqueId(), now);
    }

    public void quit(UUID uuid) { states.remove(uuid); lastInputAt.remove(uuid); }
    /** Records meaningful player input. Called only from the main thread. */
    public void input(UUID uuid, long now) {
        if (lastInputAt.containsKey(uuid)) lastInputAt.put(uuid, now);
    }
    public boolean locallyAfk(UUID uuid, long now, long thresholdMillis) {
        Long last = lastInputAt.get(uuid);
        return last != null && now >= last && now - last >= thresholdMillis;
    }
    public boolean tracksInput(UUID uuid) { return lastInputAt.containsKey(uuid); }
    public long lastInputAt(UUID uuid) { return lastInputAt.getOrDefault(uuid, 0L); }
    public void sprint(UUID uuid, boolean value) { state(uuid).sprinting(value); }
    public void swimming(UUID uuid, boolean value) { state(uuid).swimming(value); }
    public void mining(UUID uuid, long now) { input(uuid, now); state(uuid).miningUntil(now + config.miningWindowMillis()); }

    public void combat(UUID uuid, long now, boolean boss) {
        input(uuid, now);
        ActivityState state = state(uuid);
        state.combatUntil(Math.max(state.combatUntil(), now + config.combatWindowMillis()));
        if (boss) state.bossCombatUntil(Math.max(state.bossCombatUntil(), now + config.bossWindowMillis()));
    }

    public boolean inCombat(UUID uuid, long now) {
        ActivityState state = states.get(uuid);
        return state != null && state.combatUntil() > now;
    }

    public boolean isBoss(Entity entity) {
        if (entity == null) return false;
        EntityType type = entity.getType();
        for (String configured : config.bossTypes()) {
            if (type.name().equalsIgnoreCase(configured)) return true;
        }
        return false;
    }

    public double multiplier(UUID uuid, long now) {
        ActivityState state = state(uuid);
        double multiplier = config.activity("normal", 1.0);
        if (state.sprinting()) multiplier = Math.max(multiplier, config.activity("sprint", 1.2));
        if (state.swimming()) multiplier = Math.max(multiplier, config.activity("swimming", 1.2));
        if (state.miningUntil() > now) multiplier = Math.max(multiplier, config.activity("mining", 1.1));
        if (state.combatUntil() > now) multiplier = Math.max(multiplier, config.activity("combat", 1.5));
        if (state.bossCombatUntil() > now) multiplier = Math.max(multiplier, config.activity("boss", 2.0));
        return multiplier;
    }
}
