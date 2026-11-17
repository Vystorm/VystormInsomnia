package at.vystorm.insomnia.model;

import at.vystorm.insomnia.api.InsomniaStage;
import at.vystorm.insomnia.api.SleepStage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerFatigueStateTest {
    private static final List<Double> THRESHOLDS = List.of(20.0, 40.0, 60.0, 80.0);

    @Test
    void nonFiniteValuesNeverReachStoredFatigueOrDebt() {
        PlayerFatigueState state = new PlayerFatigueState(UUID.randomUUID(), 1_000L);
        state.fatigue(Double.NaN);
        state.sleepDebt(Double.NaN);
        assertEquals(0.0, state.rawFatigue());
        assertEquals(0.0, state.sleepDebt());
        state.fatigue(Double.POSITIVE_INFINITY);
        assertEquals(100.0, state.rawFatigue());
        assertEquals(100.0, state.fatigue());
    }
    @Test
    void stimulantSuppressionOnlyChangesVisibleFatigueAndNeverClearsDebt() {
        PlayerFatigueState state = new PlayerFatigueState(UUID.randomUUID(), 1_000L);
        state.fatigue(85.0);
        state.sleepDebt(70.0);
        state.stimulantSuppression(30.0);

        assertEquals(85.0, state.rawFatigue());
        assertEquals(55.0, state.fatigue());
        assertEquals(70.0, state.sleepDebt());
        assertEquals(InsomniaStage.EXHAUSTED, state.recomputeStage(THRESHOLDS));
    }

    @Test
    void stageBoundariesUseTheNextStageAtEachConfiguredThreshold() {
        PlayerFatigueState state = new PlayerFatigueState(UUID.randomUUID(), 1_000L);

        state.fatigue(19.999);
        assertEquals(InsomniaStage.RESTED, state.recomputeStage(THRESHOLDS));
        state.fatigue(20.0);
        assertEquals(InsomniaStage.TIRED, state.recomputeStage(THRESHOLDS));
        state.fatigue(40.0);
        assertEquals(InsomniaStage.EXHAUSTED, state.recomputeStage(THRESHOLDS));
        state.fatigue(60.0);
        assertEquals(InsomniaStage.OVERMUEDET, state.recomputeStage(THRESHOLDS));
        state.fatigue(80.0);
        assertEquals(InsomniaStage.SLEEP_DEPRIVATION, state.recomputeStage(THRESHOLDS));
    }

    @Test
    void resetStimulantsClearsToleranceAndPendingCrashWithoutChangingSleepDebt() {
        PlayerFatigueState state = new PlayerFatigueState(UUID.randomUUID(), 1_000L);
        state.sleepDebt(64.0);
        state.stimulantUses(3);
        state.stimulantSuppression(18.0);
        state.stimulantCrashPending(9.0);
        state.stimulantUntilEpochMillis(42_000L);
        state.clearDirty();

        state.resetStimulants();

        assertEquals(64.0, state.sleepDebt());
        assertEquals(0, state.stimulantUses());
        assertEquals(0.0, state.stimulantSuppression());
        assertEquals(0.0, state.stimulantCrashPending());
        assertEquals(0L, state.stimulantUntilEpochMillis());
        assertTrue(state.dirty());
        state.sleepStage(null);
        assertEquals(SleepStage.AWAKE, state.sleepStage());
        state.clearDirty();
        assertFalse(state.dirty());
    }
}
