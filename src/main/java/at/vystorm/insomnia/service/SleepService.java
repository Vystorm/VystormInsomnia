package at.vystorm.insomnia.service;

import at.vystorm.insomnia.api.SleepStage;
import at.vystorm.insomnia.config.InsomniaConfig;
import at.vystorm.insomnia.event.PlayerSleepStageChangeEvent;
import at.vystorm.insomnia.model.PlayerFatigueState;
import at.vystorm.insomnia.model.SleepSession;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class SleepService {
    private final InsomniaConfig config;
    private final FatigueService fatigue;
    private final PersistenceService persistence;
    private final SleepQualityService quality;
    private final RestedBonusService rested;
    private final StimulantService stimulants;
    private final InsomniaMobService mobs;
    private final Map<UUID, SleepSession> sessions = new HashMap<>();
    private final java.util.Set<UUID> dreamSessions = new java.util.HashSet<>();

    public SleepService(InsomniaConfig config, FatigueService fatigue, PersistenceService persistence,
                        SleepQualityService quality, RestedBonusService rested,
                        StimulantService stimulants, InsomniaMobService mobs) {
        this.config = config;
        this.fatigue = fatigue;
        this.persistence = persistence;
        this.quality = quality;
        this.rested = rested;
        this.stimulants = stimulants;
        this.mobs = mobs;
    }

    public boolean isSleeping(UUID player) { return sessions.containsKey(player); }

    public BedroomComfort.Result comfortAt(org.bukkit.Location location) { return quality.comfort(location); }

    /** "Gemütlichkeit 3/6: …, noch möglich: …" in the viewer's language. */
    public String cozyLine(org.bukkit.command.CommandSender viewer, String key, BedroomComfort.Result comfort) {
        var texts = config.messages();
        java.util.function.Function<java.util.Set<BedroomComfort.Category>, String> names = set -> set.isEmpty()
                ? (texts == null ? "-" : texts.text(viewer, "cozy.none"))
                : String.join(", ", set.stream().map(c -> texts == null ? c.name() : texts.text(viewer, "cozy." + c.name().toLowerCase(java.util.Locale.ROOT))).toList());
        return config.message(viewer, key, "count", comfort.count(), "max", BedroomComfort.Category.values().length,
                "found", names.apply(comfort.found()), "missing", names.apply(comfort.missing()),
                "bonus", Math.round((comfort.multiplier(config.cozyPerFurnishing()) - 1) * 100));
    }

    /** Starts actual-time sleep recovery for a Core-managed dream visit, without requiring vanilla isSleeping(). */
    public double qualityAt(Player player) { return quality.calculate(player); }

    public boolean startDream(Player player, long now, double bedQuality) {
        if (!fatigue.ready(player.getUniqueId()) || sessions.containsKey(player.getUniqueId())) return false;
        PlayerFatigueState state = fatigue.require(player.getUniqueId());
        SleepSession session = new SleepSession(player.getUniqueId(), now, bedQuality);
        sessions.put(player.getUniqueId(), session);
        dreamSessions.add(player.getUniqueId());
        fatigue.markHistory(player.getUniqueId(), now, "dream_sleep_started");
        SleepStage previous = state.sleepStage();
        state.sleepStage(SleepStage.FALLING_ASLEEP);
        state.lastUpdateEpochMillis(now);
        mobs.clearOwner(player.getUniqueId());
        Bukkit.getPluginManager().callEvent(new PlayerSleepStageChangeEvent(player, previous, SleepStage.FALLING_ASLEEP));
        return true;
    }

    public void start(Player player, long now) {
        if (!config.sleepEnabled() || !fatigue.ready(player.getUniqueId())) return;
        PlayerFatigueState state = fatigue.require(player.getUniqueId());
        if (sessions.containsKey(player.getUniqueId())) return;
        SleepSession session = new SleepSession(player.getUniqueId(), now, quality.calculate(player));
        session.startValues(state.fatigue(), state.sleepDebt());
        sessions.put(player.getUniqueId(), session);
        fatigue.markHistory(player.getUniqueId(), now, "sleep_started");
        SleepStage previous = state.sleepStage();
        state.sleepStage(SleepStage.FALLING_ASLEEP);
        state.lastUpdateEpochMillis(now);
        mobs.clearOwner(player.getUniqueId());
        Bukkit.getPluginManager().callEvent(new PlayerSleepStageChangeEvent(player, previous, SleepStage.FALLING_ASLEEP));
        double preview = session.qualityMultiplier();
        player.sendMessage(config.message(player, "sleep_preview", "quality", String.format(java.util.Locale.ROOT, "%.2f", preview),
                "rested_minutes", config.restedEnabled() ? config.restedDurationMillis() / 60_000L : 0L,
                "xp_multiplier", String.format(java.util.Locale.ROOT, "%.2f", config.restedEnabled() ? config.restedXpMultiplier() : 1.0)));
        if (config.cozyEnabled()) player.sendMessage(cozyLine(player, "sleep_cozy", quality.comfort(player.getLocation())));
        player.sendMessage(config.message(player, "sleep_start"));
    }

    public void stop(Player player, boolean flush) {
        SleepSession session = sessions.remove(player.getUniqueId());
        boolean dream = dreamSessions.remove(player.getUniqueId());
        PlayerFatigueState state = fatigue.state(player.getUniqueId()).orElse(null);
        if (state == null) return;
        if (session != null && dream) {
            long now = System.currentTimeMillis();
            long elapsed = Math.max(0L, now - session.lastRecoveryAt());
            if (elapsed > 0) {
                double seconds = elapsed / 1000.0;
                state.reduceFatigue(config.sleepRecoveryPerSecond() * seconds * session.qualityMultiplier());
                state.reduceSleepDebt(config.debtRecoveryPerSecond() * seconds * session.qualityMultiplier());
                state.lastUpdateEpochMillis(now);
                fatigue.refreshStage(player.getUniqueId());
            }
        }
        if (session != null && !dream && !session.completed() && player.isOnline() && config.wakeSummary()) {
            WakeSummary summary = WakeSummary.of(session.fatigueAtStart(), state.fatigue(), session.debtAtStart(), state.sleepDebt(),
                    System.currentTimeMillis() - session.startedAt());
            if (summary != null) player.sendMessage(config.message(player, "sleep_summary",
                    "seconds", summary.seconds(), "fatigue_before", summary.fatigueBefore(), "fatigue_after", summary.fatigueAfter(),
                    "debt_before", summary.debtBefore(), "debt_after", summary.debtAfter()));
        }
        SleepStage previous = state.sleepStage();
        state.sleepStage(SleepStage.AWAKE);
        state.lastUpdateEpochMillis(System.currentTimeMillis());
        if (session != null && previous != SleepStage.AWAKE) {
            Bukkit.getPluginManager().callEvent(new PlayerSleepStageChangeEvent(player, previous, SleepStage.AWAKE));
        }
        if (flush) persistence.save(state, true);
    }

    public void tick(long now) {
        for (SleepSession session : sessions.values().toArray(SleepSession[]::new)) {
            Player player = Bukkit.getPlayer(session.playerUuid());
            boolean bedSession = !dreamSessions.contains(session.playerUuid());
            // sleep.enabled switched off at runtime: bed sessions end at once (no recovery, no rested bonus); dream visits go on.
            if (player == null || !player.isOnline() || (bedSession && (!player.isSleeping() || !config.sleepEnabled()))) {
                if (player != null) stop(player, false);
                else sessions.remove(session.playerUuid());
                continue;
            }
            PlayerFatigueState state = fatigue.require(player.getUniqueId());
            if (!dreamSessions.contains(session.playerUuid()) && now - session.lastQualityCheckAt() >= config.qualityRecheckMillis()) {
                session.qualityMultiplier(quality.calculate(player));
                session.lastQualityCheckAt(now);
            }

            long elapsedRecovery = Math.max(0L, now - session.lastRecoveryAt());
            if (elapsedRecovery > 0L) {
                double seconds = elapsedRecovery / 1000.0;
                state.reduceFatigue(config.sleepRecoveryPerSecond() * seconds * session.qualityMultiplier());
                state.reduceSleepDebt(config.debtRecoveryPerSecond() * seconds * session.qualityMultiplier());
                state.lastUpdateEpochMillis(now);
                session.lastRecoveryAt(now);
                fatigue.refreshStage(player.getUniqueId());
            }

            SleepStage next = stageAt(now - session.startedAt());
            if (next != session.stage()) {
                SleepStage previous = session.stage();
                session.stage(next);
                state.sleepStage(next);
                Bukkit.getPluginManager().callEvent(new PlayerSleepStageChangeEvent(player, previous, next));
                if (next == SleepStage.DEEP_SLEEP) player.sendMessage(config.message(player, "sleep_deep"));
            }

            if (next == SleepStage.RESTED && !session.completed()) {
                session.completed(true);
                boolean dream = dreamSessions.contains(player.getUniqueId());
                if (!dream) {
                    state.fatigue(0.0);
                    state.sleepDebt(0.0);
                    state.lastSleepEpochMillis(now);
                    state.awakeSinceEpochMillis(now);
                    stimulants.resetAfterDeepSleep(state);
                    fatigue.refreshStage(player.getUniqueId());
                    rested.grant(player, now);
                }
                fatigue.markHistory(player.getUniqueId(), now, "sleep_completed");
                persistence.save(state, true);
                if (!dream) player.sendMessage(config.message(player, "sleep_rested"));
            }
        }
    }

    public void forceRest(Player player, long now) {
        PlayerFatigueState state = fatigue.require(player.getUniqueId());
        state.fatigue(0.0);
        state.sleepDebt(0.0);
        state.lastSleepEpochMillis(now);
        state.awakeSinceEpochMillis(now);
        state.lastUpdateEpochMillis(now);
        stimulants.resetAfterDeepSleep(state);
        fatigue.refreshStage(player.getUniqueId());
        rested.grant(player, now);
        persistence.save(state, true);
    }

    public boolean isDreamSleeping(UUID player) { return dreamSessions.contains(player); }

    private SleepStage stageAt(long elapsed) {
        long falling = config.fallingAsleepMillis();
        long light = config.lightSleepMillis();
        long deep = config.deepSleepMillis();
        if (elapsed < falling) return SleepStage.FALLING_ASLEEP;
        if (elapsed < falling + light) return SleepStage.LIGHT_SLEEP;
        if (elapsed < falling + light + deep) return SleepStage.DEEP_SLEEP;
        return SleepStage.RESTED;
    }

    public void shutdown() {
        for (UUID uuid : sessions.keySet().toArray(UUID[]::new)) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) stop(player, false);
        }
        sessions.clear();
        dreamSessions.clear();
    }
}
