package at.vystorm.insomnia.service;

/**
 * What an interrupted sleep did: fatigue and sleep debt before and after (whole percent) and how long it lasted.
 * Shown when a player leaves the bed before they are fully rested ({@code sleep.wake_summary}).
 */
public record WakeSummary(int fatigueBefore, int fatigueAfter, int debtBefore, int debtAfter, long seconds) {
    /** The summary, or null when the sleep changed neither value by at least one whole percent. */
    public static WakeSummary of(double fatigueBefore, double fatigueAfter, double debtBefore, double debtAfter, long millis) {
        int fb = (int) Math.round(fatigueBefore), fa = (int) Math.round(fatigueAfter);
        int db = (int) Math.round(debtBefore), da = (int) Math.round(debtAfter);
        if (fb - fa < 1 && db - da < 1) return null;
        return new WakeSummary(fb, fa, db, da, Math.max(0L, millis) / 1000L);
    }
}
