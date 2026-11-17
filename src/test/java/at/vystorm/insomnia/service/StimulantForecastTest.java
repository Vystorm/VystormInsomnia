package at.vystorm.insomnia.service;

import at.vystorm.insomnia.model.PlayerFatigueState;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StimulantForecastTest {
    private PlayerFatigueState active() {
        PlayerFatigueState state = new PlayerFatigueState(UUID.randomUUID(), 0);
        state.fatigue(70);
        state.sleepDebt(25);
        state.stimulantSuppression(35);
        state.stimulantCrashPending(17.5);
        state.stimulantUses(2);
        state.stimulantUntilEpochMillis(60_000);
        state.clearDirty();
        return state;
    }

    @Test
    void includesSuppressedFatigueAndAccumulatedCrashWithoutChangingState() {
        PlayerFatigueState state = active();
        assertEquals(new StimulantForecast(60, 2, 87.5), StimulantForecast.of(state, 0));
        assertEquals(35, state.fatigue());
        assertEquals(25, state.sleepDebt());
        assertEquals(17.5, state.stimulantCrashPending());
        assertFalse(state.dirty());
    }

    @Test
    void roundsUpSecondsAndShowsDueCrashUntilRuntimeProcessesIt() {
        PlayerFatigueState state = active();
        assertEquals(60, StimulantForecast.of(state, 1).seconds());
        assertEquals(1, StimulantForecast.of(state, 59_999).seconds());
        assertEquals(0, StimulantForecast.of(state, 60_000).seconds());
        assertEquals(0, StimulantForecast.of(state, 65_000).seconds());
    }

    @Test
    void capsCrashAndReflectsChangesFromSleepOrActivity() {
        PlayerFatigueState state = active();
        state.fatigue(95);
        assertEquals(100, StimulantForecast.of(state, 0).fatigueAfterCrash());
        state.reduceFatigue(75);
        assertEquals(37.5, StimulantForecast.of(state, 0).fatigueAfterCrash());
    }

    @Test
    void handlesCrashOnlyAndSuppressionOnlyAndHidesEmptyEffects() {
        PlayerFatigueState state = active();
        state.stimulantSuppression(0);
        assertNotNull(StimulantForecast.of(state, 0));
        state.stimulantCrashPending(0);
        assertNull(StimulantForecast.of(state, 0));
        state.stimulantSuppression(20);
        assertEquals(70, StimulantForecast.of(state, 0).fatigueAfterCrash());
        state.stimulantUntilEpochMillis(0);
        assertNull(StimulantForecast.of(state, 0));
        state.resetStimulants();
        assertNull(StimulantForecast.of(state, 0));
    }
}
