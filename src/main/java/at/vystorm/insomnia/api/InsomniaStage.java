package at.vystorm.insomnia.api;

import java.util.List;

public enum InsomniaStage {
    RESTED,
    TIRED,
    EXHAUSTED,
    OVERMUEDET,
    SLEEP_DEPRIVATION;

    public static InsomniaStage fromFatigue(double fatigue, List<Double> thresholds) {
        if (thresholds == null || thresholds.size() < 4) {
            return fromFatigue(fatigue, List.of(20.0, 40.0, 60.0, 80.0));
        }
        if (fatigue < thresholds.get(0)) return RESTED;
        if (fatigue < thresholds.get(1)) return TIRED;
        if (fatigue < thresholds.get(2)) return EXHAUSTED;
        if (fatigue < thresholds.get(3)) return OVERMUEDET;
        return SLEEP_DEPRIVATION;
    }
}
