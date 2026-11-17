package at.vystorm.insomnia.storage;

import at.vystorm.insomnia.api.InsomniaStage;
import at.vystorm.insomnia.api.SleepStage;
import at.vystorm.insomnia.model.PlayerFatigueState;

import java.util.UUID;

/** The persisted part of a {@link PlayerFatigueState}, immutable so it can be handed to the storage thread. */
public record PlayerRecord(UUID uuid, double fatigue, double sleepDebt, double stimulantSuppression,
                           double stimulantCrashPending, int stimulantUses, long stimulantUntil, long awakeSince,
                           long lastSleep, long lastMicrosleep, long restedUntil) {

    public static PlayerRecord of(PlayerFatigueState state) {
        return new PlayerRecord(state.playerUuid(), state.rawFatigue(), state.sleepDebt(), state.stimulantSuppression(),
                state.stimulantCrashPending(), state.stimulantUses(), state.stimulantUntilEpochMillis(),
                state.awakeSinceEpochMillis(), state.lastSleepEpochMillis(), state.lastMicrosleepEpochMillis(),
                state.restedUntilEpochMillis());
    }

    /** A clean state for {@code uuid}; used when nothing is stored yet. */
    public static PlayerFatigueState fresh(UUID uuid, long now) {
        PlayerFatigueState state = new PlayerFatigueState(uuid, now);
        state.clearDirty();
        return state;
    }

    /** Rebuilds the runtime state. Offline time never creates fatigue, so the update clock starts at {@code now}. */
    public PlayerFatigueState toState(long now) {
        PlayerFatigueState state = new PlayerFatigueState(uuid, now);
        state.fatigue(fatigue);
        state.sleepDebt(sleepDebt);
        state.stimulantSuppression(stimulantSuppression);
        state.stimulantCrashPending(stimulantCrashPending);
        state.stimulantUses(stimulantUses);
        state.stimulantUntilEpochMillis(stimulantUntil);
        state.awakeSinceEpochMillis(awakeSince);
        state.lastUpdateEpochMillis(now);
        state.lastSleepEpochMillis(lastSleep);
        state.lastMicrosleepEpochMillis(lastMicrosleep);
        state.restedUntilEpochMillis(restedUntil);
        state.sleepStage(SleepStage.AWAKE);
        state.insomniaStage(InsomniaStage.RESTED);
        state.clearDirty();
        return state;
    }
}
