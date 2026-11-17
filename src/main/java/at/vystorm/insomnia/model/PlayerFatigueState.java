package at.vystorm.insomnia.model;

import at.vystorm.insomnia.api.InsomniaStage;
import at.vystorm.insomnia.api.SleepStage;

import java.util.List;
import java.util.UUID;

public final class PlayerFatigueState {
    private final UUID playerUuid;
    private double fatigue;
    private double sleepDebt;
    private double stimulantSuppression;
    private double stimulantCrashPending;
    private int stimulantUses;
    private long stimulantUntilEpochMillis;
    private long awakeSinceEpochMillis;
    private long lastUpdateEpochMillis;
    private long lastSleepEpochMillis;
    private long lastMicrosleepEpochMillis;
    private long restedUntilEpochMillis;
    private SleepStage sleepStage = SleepStage.AWAKE;
    private InsomniaStage insomniaStage = InsomniaStage.RESTED;
    private boolean dirty;

    public PlayerFatigueState(UUID playerUuid, long now) {
        this.playerUuid = playerUuid;
        this.awakeSinceEpochMillis = now;
        this.lastUpdateEpochMillis = now;
    }

    public UUID playerUuid() { return playerUuid; }
    public double rawFatigue() { return fatigue; }
    public double fatigue() { return clamp(fatigue - stimulantSuppression); }
    public void fatigue(double value) { fatigue = clamp(value); dirty = true; }
    public void addFatigue(double value) { fatigue(clamp(fatigue + value)); }
    public void reduceFatigue(double value) { fatigue(clamp(fatigue - Math.max(0.0, value))); }
    public double sleepDebt() { return sleepDebt; }
    public void sleepDebt(double value) { sleepDebt = clamp(value); dirty = true; }
    public void addSleepDebt(double value) { sleepDebt(clamp(sleepDebt + value)); }
    public void reduceSleepDebt(double value) { sleepDebt(clamp(sleepDebt - Math.max(0.0, value))); }
    public double stimulantSuppression() { return stimulantSuppression; }
    public void stimulantSuppression(double value) { stimulantSuppression = clamp(value); dirty = true; }
    public double stimulantCrashPending() { return stimulantCrashPending; }
    public void stimulantCrashPending(double value) { stimulantCrashPending = Math.max(0.0, value); dirty = true; }
    public int stimulantUses() { return stimulantUses; }
    public void stimulantUses(int value) { stimulantUses = Math.max(0, value); dirty = true; }
    public long stimulantUntilEpochMillis() { return stimulantUntilEpochMillis; }
    public void stimulantUntilEpochMillis(long value) { stimulantUntilEpochMillis = Math.max(0L, value); dirty = true; }
    public long awakeSinceEpochMillis() { return awakeSinceEpochMillis; }
    public void awakeSinceEpochMillis(long value) { awakeSinceEpochMillis = value; dirty = true; }
    public long lastUpdateEpochMillis() { return lastUpdateEpochMillis; }
    public void lastUpdateEpochMillis(long value) { lastUpdateEpochMillis = value; }
    public long lastSleepEpochMillis() { return lastSleepEpochMillis; }
    public void lastSleepEpochMillis(long value) { lastSleepEpochMillis = value; dirty = true; }
    public long lastMicrosleepEpochMillis() { return lastMicrosleepEpochMillis; }
    public void lastMicrosleepEpochMillis(long value) { lastMicrosleepEpochMillis = value; dirty = true; }
    public long restedUntilEpochMillis() { return restedUntilEpochMillis; }
    public void restedUntilEpochMillis(long value) { restedUntilEpochMillis = value; dirty = true; }
    public SleepStage sleepStage() { return sleepStage; }
    public void sleepStage(SleepStage value) { sleepStage = value == null ? SleepStage.AWAKE : value; dirty = true; }
    public InsomniaStage insomniaStage() { return insomniaStage; }
    public void insomniaStage(InsomniaStage value) { insomniaStage = value == null ? InsomniaStage.RESTED : value; }
    public boolean dirty() { return dirty; }
    public void clearDirty() { dirty = false; }
    public void markDirty() { dirty = true; }

    public InsomniaStage recomputeStage(List<Double> thresholds) {
        InsomniaStage stage = InsomniaStage.fromFatigue(fatigue(), thresholds);
        insomniaStage = stage;
        return stage;
    }

    public void resetStimulants() {
        stimulantUses = 0;
        stimulantSuppression = 0.0;
        stimulantCrashPending = 0.0;
        stimulantUntilEpochMillis = 0L;
        dirty = true;
    }

    private static double clamp(double value) {
        if (Double.isNaN(value)) return 0.0;
        return Math.max(0.0, Math.min(100.0, value));
    }
}
