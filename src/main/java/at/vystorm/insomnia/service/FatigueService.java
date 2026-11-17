package at.vystorm.insomnia.service;

import at.vystorm.insomnia.api.InsomniaApi;
import at.vystorm.insomnia.api.InsomniaStage;
import at.vystorm.insomnia.config.InsomniaConfig;
import at.vystorm.insomnia.event.InsomniaStageChangeEvent;
import at.vystorm.insomnia.model.PlayerFatigueState;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.GameMode;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class FatigueService implements InsomniaApi {
    private final InsomniaConfig config;
    private final PersistenceService persistence;
    private final ActivityService activity;
    private final Map<UUID, PlayerFatigueState> states = new HashMap<>();
    private final PlayerInsightHistory history = new PlayerInsightHistory();
    private final Map<UUID, Long> nextHistorySample = new HashMap<>();
    private SleepService sleepService;
    private long nextTickAt;

    public FatigueService(InsomniaConfig config, PersistenceService persistence, ActivityService activity) {
        this.config = config;
        this.persistence = persistence;
        this.activity = activity;
    }

    public void bindSleepService(SleepService sleepService) { this.sleepService = sleepService; }

    /**
     * Loads the player's state. YAML: right away. MySQL: in the background; until it arrives the player has no
     * state and every system skips them (no fatigue is gained or lost in that time).
     */
    public void load(Player player, long now) {
        UUID uuid = player.getUniqueId();
        if (persistence.synchronous()) {
            install(persistence.load(uuid, now));
            return;
        }
        persistence.loadAsync(uuid, loaded -> {
            Player current = Bukkit.getPlayer(uuid);
            if (current != player) {
                // A reconnect has its own load; never install the old snapshot or release its new claim.
                if (current == null) persistence.release(uuid);
                return;
            }
            if (!states.containsKey(uuid)) install(loaded);
        });
    }

    private PlayerFatigueState install(PlayerFatigueState state) {
        InsomniaStage computed = InsomniaStage.fromFatigue(state.fatigue(), config.stageThresholds());
        state.insomniaStage(computed);
        state.clearDirty();
        states.put(state.playerUuid(), state);
        return state;
    }

    /** @throws IllegalStateException when the player is offline or (MySQL) their data is still loading */
    public PlayerFatigueState require(UUID uuid) {
        PlayerFatigueState state = states.get(uuid);
        if (state != null) return state;
        Player player = Bukkit.getPlayer(uuid);
        if (player == null) throw new IllegalStateException("Player is not loaded: " + uuid);
        if (!persistence.synchronous()) throw new IllegalStateException("Player data is still loading: " + uuid);
        return install(persistence.load(uuid, System.currentTimeMillis()));
    }

    /** false while a MySQL load for this player is still running; YAML loads on demand, so always true. */
    public boolean ready(UUID uuid) { return states.containsKey(uuid) || persistence.synchronous(); }

    public Optional<PlayerFatigueState> state(UUID uuid) { return Optional.ofNullable(states.get(uuid)); }
    public Collection<PlayerFatigueState> states() { return states.values(); }

    public void unload(Player player) {
        PlayerFatigueState state = states.remove(player.getUniqueId());
        if (state != null) persistence.saveAndRelease(state);
        history.forget(player.getUniqueId());
        nextHistorySample.remove(player.getUniqueId());
    }

    public void tick(long now) {
        if (now < nextTickAt) return;
        nextTickAt = now + Math.max(1000L, config.fatigueUpdateIntervalTicks() * 50L);
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerFatigueState state = states.get(player.getUniqueId());
            if (state == null) {
                if (!persistence.synchronous()) continue; // MySQL load still running
                state = install(persistence.load(player.getUniqueId(), now));
            }
            if (now >= nextHistorySample.getOrDefault(player.getUniqueId(), 0L)) {
                history.sample(player.getUniqueId(), now, state.fatigue(), state.sleepDebt());
                nextHistorySample.put(player.getUniqueId(), now + 60_000L);
            }
            long elapsed = Math.max(0L, now - state.lastUpdateEpochMillis());
            state.lastUpdateEpochMillis(now);
            if (sleepService != null && sleepService.isSleeping(player.getUniqueId())) continue;
            if (pausedReason(player, now) != null) continue;
            if (elapsed == 0L || !config.fatigueEnabled()) continue;

            double minutes = elapsed / 60_000.0;
            double activityMultiplier = activity.multiplier(player.getUniqueId(), now);
            InsomniaStage before = state.insomniaStage();
            state.addFatigue(config.baseFatiguePerMinute() * minutes * activityMultiplier);
            state.addSleepDebt(config.debtPerMinute() * minutes * activityMultiplier);
            InsomniaStage after = state.recomputeStage(config.stageThresholds());
            if (before != after) Bukkit.getPluginManager().callEvent(new InsomniaStageChangeEvent(player, before, after));
        }
    }

    public List<PlayerInsightHistory.Entry> recentHistory(UUID uuid) { return history.recent(uuid); }
    public void markHistory(UUID uuid, long now, String marker) {
        PlayerFatigueState state = states.get(uuid);
        if (state != null) history.mark(uuid, now, state.fatigue(), state.sleepDebt(), marker);
    }

    /** A clear reason why passive fatigue growth is paused, or null when it is active. */
    public String pausedReason(Player player) {
        return pausedReason(player, System.currentTimeMillis());
    }

    public String pausedReason(Player player, long now) {
        if (player.getGameMode() == GameMode.CREATIVE && config.excludeCreative()) return "creative";
        if (player.getGameMode() == GameMode.SPECTATOR && config.excludeSpectator()) return "spectator";
        if (config.localAfkPauseEnabled() && activity.locallyAfk(player.getUniqueId(), now, config.localAfkThresholdMillis())) return "local_afk";
        return null;
    }

    public void setFatigue(UUID uuid, double amount) {
        PlayerFatigueState state = require(uuid);
        InsomniaStage before = state.insomniaStage();
        state.fatigue(amount);
        InsomniaStage after = state.recomputeStage(config.stageThresholds());
        Player player = Bukkit.getPlayer(uuid);
        if (player != null && before != after) Bukkit.getPluginManager().callEvent(new InsomniaStageChangeEvent(player, before, after));
    }

    public void setDebt(UUID uuid, double amount) { require(uuid).sleepDebt(amount); }

    public InsomniaStage refreshStage(UUID uuid) {
        PlayerFatigueState state = require(uuid);
        InsomniaStage before = state.insomniaStage();
        InsomniaStage after = state.recomputeStage(config.stageThresholds());
        Player player = Bukkit.getPlayer(uuid);
        if (player != null && before != after) Bukkit.getPluginManager().callEvent(new InsomniaStageChangeEvent(player, before, after));
        return after;
    }

    @Override public double fatigue(UUID player) { return state(player).map(PlayerFatigueState::fatigue).orElse(0.0); }
    @Override public double sleepDebt(UUID player) { return state(player).map(PlayerFatigueState::sleepDebt).orElse(0.0); }
    @Override public InsomniaStage stage(UUID player) { return state(player).map(PlayerFatigueState::insomniaStage).orElse(InsomniaStage.RESTED); }
    @Override public boolean isSleeping(UUID player) { return sleepService != null && sleepService.isSleeping(player); }
    @Override public void addFatigue(UUID player, double amount) { setFatigue(player, require(player).rawFatigue() + Math.max(0.0, amount)); }
    @Override public void reduceFatigue(UUID player, double amount) { setFatigue(player, require(player).rawFatigue() - Math.max(0.0, amount)); }
}
