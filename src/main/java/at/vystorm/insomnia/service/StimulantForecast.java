package at.vystorm.insomnia.service;

import at.vystorm.insomnia.model.PlayerFatigueState;

/** A snapshot, not a prediction of future activity or sleep. Uses the same crash cap as StimulantService. */
public record StimulantForecast(long seconds, int uses, double fatigueAfterCrash) {
    public static StimulantForecast of(PlayerFatigueState state, long now) {
        if (state.stimulantUntilEpochMillis() <= 0L
                || (state.stimulantSuppression() <= 0.0 && state.stimulantCrashPending() <= 0.0)) return null;
        long remaining = Math.max(0L, state.stimulantUntilEpochMillis() - now);
        long seconds = remaining / 1000 + (remaining % 1000 == 0 ? 0 : 1);
        return new StimulantForecast(seconds, state.stimulantUses(),
                Math.min(100.0, state.rawFatigue() + state.stimulantCrashPending()));
    }
}
