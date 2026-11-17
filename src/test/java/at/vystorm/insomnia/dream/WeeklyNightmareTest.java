package at.vystorm.insomnia.dream;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class WeeklyNightmareTest {
    @Test
    void givenMondayMidnight_whenWeekComputed_thenNewWeekStarts() {
        long monday = Instant.parse("2026-11-09T00:00:00Z").toEpochMilli();
        assertEquals(WeeklyNightmare.week(monday - 1) + 1, WeeklyNightmare.week(monday));
        assertEquals(WeeklyNightmare.week(monday), WeeklyNightmare.week(Instant.parse("2026-11-15T23:59:59Z").toEpochMilli()));
        assertEquals(Instant.parse("2026-11-16T00:00:00Z").toEpochMilli(), WeeklyNightmare.weekEnd(WeeklyNightmare.week(monday)));
    }

    @Test
    void givenWeekAndSeed_whenModifiersPicked_thenStableAndDistinct() {
        var a = WeeklyNightmare.modifiers(2900, 510231, 2);
        assertEquals(a, WeeklyNightmare.modifiers(2900, 510231, 2));
        assertEquals(2, new HashSet<>(a).size());
        assertEquals(5, WeeklyNightmare.modifiers(2900, 1, 9).size());
        assertEquals(1, WeeklyNightmare.modifiers(2900, 1, 0).size());
    }

    @Test
    void givenClears_whenRecorded_thenBestTimesRankAndSurviveEncoding() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        WeeklyNightmare.Board board = new WeeklyNightmare.Board(7);
        assertTrue(board.record(a, "Alex", 90_000));
        assertTrue(board.record(b, "Bo", 80_000));
        assertFalse(board.record(a, "Alex", 95_000), "slower run keeps the best");
        assertEquals(2, board.rank(a));
        WeeklyNightmare.Board copy = WeeklyNightmare.Board.decode(7, board.encode());
        assertEquals(board.top(5), copy.top(5));
        assertTrue(copy.roll(8));
        assertEquals(0, copy.size());
        assertEquals("1:30.0", WeeklyNightmare.format(90_000));
    }
}
