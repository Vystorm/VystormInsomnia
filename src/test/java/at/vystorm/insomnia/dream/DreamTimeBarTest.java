package at.vystorm.insomnia.dream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DreamTimeBarTest {
    @Test void progressTracksRemainingFractionAndClampsToValidBossBarRange() {
        assertEquals(0.75, DreamTimeBar.progress(90_000, 120_000));
        assertEquals(1.0, DreamTimeBar.progress(120_000, 120_000));
        assertEquals(1.0, DreamTimeBar.progress(Long.MAX_VALUE, 120_000));
        assertEquals(0.0, DreamTimeBar.progress(0, 120_000));
        assertEquals(0.0, DreamTimeBar.progress(Long.MIN_VALUE, 120_000));
    }

    @Test void progressTreatsNonpositiveDurationsAsOneMillisecond() {
        assertEquals(0.0, DreamTimeBar.progress(0, 0));
        assertEquals(1.0, DreamTimeBar.progress(1, 0));
        assertEquals(1.0, DreamTimeBar.progress(1, Long.MIN_VALUE));
        assertEquals(1.0, DreamTimeBar.progress(Long.MAX_VALUE, Long.MAX_VALUE));
    }

    @Test void timeTextRoundsUpWithoutShowingNegativeTime() {
        assertEquals("0:00", DreamTimeBar.timeText(Long.MIN_VALUE));
        assertEquals("0:00", DreamTimeBar.timeText(0));
        assertEquals("0:01", DreamTimeBar.timeText(1));
        assertEquals("0:01", DreamTimeBar.timeText(999));
        assertEquals("0:01", DreamTimeBar.timeText(1_000));
        assertEquals("0:02", DreamTimeBar.timeText(1_001));
    }

    @Test void timeTextHandlesMinuteBoundariesAndUnlimitedMinutes() {
        assertEquals("0:59", DreamTimeBar.timeText(59_000));
        assertEquals("1:00", DreamTimeBar.timeText(59_001));
        assertEquals("1:00", DreamTimeBar.timeText(60_000));
        assertEquals("1:01", DreamTimeBar.timeText(60_001));
        assertEquals("125:09", DreamTimeBar.timeText(7_509_000));
        assertEquals("153722867280912:56", DreamTimeBar.timeText(Long.MAX_VALUE));
    }
}
