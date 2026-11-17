package at.vystorm.insomnia.model;

import at.vystorm.insomnia.api.SleepStage;

import java.util.UUID;

public final class SleepSession {
    private final UUID playerUuid;
    private final long startedAt;
    private long lastRecoveryAt;
    private long lastQualityCheckAt;
    private double qualityMultiplier;
    private SleepStage stage;
    private boolean completed;
    private double fatigueAtStart, debtAtStart;

    public SleepSession(UUID playerUuid, long now, double qualityMultiplier) {
        this.playerUuid = playerUuid;
        this.startedAt = now;
        this.lastRecoveryAt = now;
        this.lastQualityCheckAt = now;
        this.qualityMultiplier = qualityMultiplier;
        this.stage = SleepStage.FALLING_ASLEEP;
    }

    public UUID playerUuid() { return playerUuid; }
    public long startedAt() { return startedAt; }
    public long lastRecoveryAt() { return lastRecoveryAt; }
    public void lastRecoveryAt(long value) { lastRecoveryAt = value; }
    public long lastQualityCheckAt() { return lastQualityCheckAt; }
    public void lastQualityCheckAt(long value) { lastQualityCheckAt = value; }
    public double qualityMultiplier() { return qualityMultiplier; }
    public void qualityMultiplier(double value) { qualityMultiplier = value; }
    public SleepStage stage() { return stage; }
    public void stage(SleepStage value) { stage = value; }
    public boolean completed() { return completed; }
    public void completed(boolean value) { completed = value; }
    public double fatigueAtStart() { return fatigueAtStart; }
    public double debtAtStart() { return debtAtStart; }
    public void startValues(double fatigue, double debt) { fatigueAtStart = fatigue; debtAtStart = debt; }
}
