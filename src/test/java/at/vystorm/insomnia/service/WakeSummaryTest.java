package at.vystorm.insomnia.service;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class WakeSummaryTest {
    @Test
    void anInterruptedSleepReportsBeforeAndAfter() {
        WakeSummary summary = WakeSummary.of(78.4, 51.6, 30.2, 21.9, 14_900);
        assertEquals(new WakeSummary(78, 52, 30, 22, 14), summary);
    }

    @Test
    void lessThanOnePercentIsNotWorthAMessage() {
        assertNull(WakeSummary.of(40.0, 39.6, 10.0, 9.8, 2_000));
        assertNotNull(WakeSummary.of(40.0, 40.0, 10.0, 8.0, 2_000), "sleep debt alone counts");
    }

    @Test
    void negativeDurationsAreClamped() {
        assertEquals(0, WakeSummary.of(50, 20, 0, 0, -5).seconds());
    }
}
