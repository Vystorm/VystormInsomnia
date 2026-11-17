package at.vystorm.insomnia.sleep;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class SleepMathTest {
    @Test
    void nobodySleepingKeepsNormalSpeed() {
        assertEquals(1.0, SleepSpeed.speed(0, 4, 2, 10, 1), 1e-9);
        assertEquals(1.0, SleepSpeed.speed(0, 0, 2, 10, 1), 1e-9);
    }

    @Test
    void speedGrowsWithTheShareOfSleepers() {
        assertEquals(4.0, SleepSpeed.speed(1, 4, 2, 10, 1), 1e-9);
        assertEquals(6.0, SleepSpeed.speed(2, 4, 2, 10, 1), 1e-9);
        assertEquals(10.0, SleepSpeed.speed(4, 4, 2, 10, 1), 1e-9);
        assertEquals(10.0, SleepSpeed.speed(1, 1, 2, 10, 1), 1e-9, "a player alone gets full speed");
        assertEquals(10.0, SleepSpeed.speed(5, 4, 2, 10, 1), 1e-9, "never above max");
    }

    @Test
    void curveShapesTheSpeed() {
        assertEquals(2.0 + 8 * 0.0625, SleepSpeed.speed(1, 4, 2, 10, 2), 1e-9);
        assertEquals(2.0 + 8 * 0.5, SleepSpeed.speed(1, 4, 2, 10, 0.5), 1e-9);
        assertEquals(4.0, SleepSpeed.speed(1, 4, 2, 10, Double.NaN), 1e-9, "broken curve falls back to linear");
    }

    @Test
    void speedLimitsAreClamped() {
        assertEquals(SleepSpeed.LIMIT, SleepSpeed.speed(1, 1, 500, 900, 1), 1e-9);
        assertEquals(1.0, SleepSpeed.speed(1, 2, 0.1, 0.5, 1), 1e-9);
        assertEquals(3.0, SleepSpeed.speed(1, 2, 3, 2, 1), 1e-9, "max below min is lifted to min");
    }

    @Test
    void carryTurnsFractionalSpeedIntoWholeTicks() {
        SleepSpeed.Carry carry = new SleepSpeed.Carry();
        long total = 0;
        for (int i = 0; i < 20; i++) total += carry.extra(2.5, Long.MAX_VALUE);
        assertEquals(30, total, "1.5 extra ticks per tick over 20 ticks");
        assertEquals(0, carry.extra(1.0, 100));
    }

    @Test
    void carryNeverOvershootsTheTarget() {
        SleepSpeed.Carry carry = new SleepSpeed.Carry();
        assertEquals(3, carry.extra(10.0, 3));
        assertEquals(0, carry.extra(10.0, 0));
    }

    @Test
    void speedLabelFollowsTheLanguage() {
        assertEquals("4,5", SleepSpeed.label(4.5, Locale.GERMAN));
        assertEquals("4.5", SleepSpeed.label(4.5, Locale.ENGLISH));
        assertEquals("6", SleepSpeed.label(6.0, Locale.GERMAN));
        assertEquals("12", SleepSpeed.label(12.4, Locale.ENGLISH));
    }

    @Test
    void clockTimesRoundTrip() {
        assertEquals(0, WakeTimes.parseClock("06:00"));
        assertEquals(6000, WakeTimes.parseClock("12:00"));
        assertEquals(18000, WakeTimes.parseClock("00:00"));
        assertEquals(16000, WakeTimes.parseClock("22:00"));
        assertEquals(500, WakeTimes.parseClock("6:30"));
        assertEquals(-1, WakeTimes.parseClock("24:00"));
        assertEquals(-1, WakeTimes.parseClock("12:60"));
        assertEquals(-1, WakeTimes.parseClock(""));
        assertEquals(-1, WakeTimes.parseClock("noon"));
        assertEquals(-1, WakeTimes.parseClock(null));
        assertEquals("06:00", WakeTimes.clock(0));
        assertEquals("18:32", WakeTimes.clock(12542));
        assertEquals("00:00", WakeTimes.clock(18000));
        assertEquals("05:59", WakeTimes.clock(23999));
        assertEquals("12:00", WakeTimes.clock(5 * WakeTimes.DAY + 6000));
    }

    @Test
    void wakeTimesCountForTheComingDay() {
        long evening = 7 * WakeTimes.DAY + 13000; // 19:00 on day 7
        long lateNight = 7 * WakeTimes.DAY + 20000; // 02:00, still the night of day 7
        long morning8 = 8 * WakeTimes.DAY;
        assertEquals(morning8, WakeTimes.target(evening, WakeTimes.parseClock("06:00")));
        assertEquals(morning8, WakeTimes.target(lateNight, WakeTimes.parseClock("06:00")));
        assertEquals(morning8 + 6000, WakeTimes.target(evening, WakeTimes.parseClock("12:00")));
        assertEquals(morning8 + 12000, WakeTimes.target(evening, WakeTimes.parseClock("18:00")));
        assertEquals(morning8 + 16000, WakeTimes.target(evening, WakeTimes.parseClock("22:00")), "the next night, not tonight");
        assertEquals(morning8 + 21000, WakeTimes.target(evening, WakeTimes.parseClock("03:00")));
        long storm = 7 * WakeTimes.DAY + 8000; // 14:00 thunderstorm sleep
        assertEquals(morning8, WakeTimes.target(storm, 0));
        assertEquals(WakeTimes.DAY, WakeTimes.nextMorning(0), "exactly 06:00 means the next morning");
    }

    @Test
    void severalSleepersRunTowardsTheEarliestTime() {
        assertEquals(24000, WakeTimes.earliest(List.of(30000L, 24000L, 40000L)).getAsLong());
        assertTrue(WakeTimes.earliest(List.of()).isEmpty());
    }
}
