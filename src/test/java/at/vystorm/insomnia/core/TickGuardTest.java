package at.vystorm.insomnia.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TickGuardTest {
    private final List<String> logged = new ArrayList<>();
    private final TickGuard guard = new TickGuard(logged::add, 60_000L);

    @Test
    void failingStepDoesNotStopTheFollowingOnes() {
        List<String> ran = new ArrayList<>();
        assertFalse(guard.run("sleep", 0L, () -> { throw new IllegalStateException("region lookup"); }));
        assertTrue(guard.run("fatigue", 0L, () -> ran.add("fatigue")));
        assertFalse(guard.run("mobs", 0L, () -> { throw new NoSuchMethodError("Core API changed"); }));
        assertTrue(guard.run("microsleep", 0L, () -> ran.add("microsleep")));

        assertEquals(List.of("fatigue", "microsleep"), ran);
        assertEquals(2, logged.size());
        assertTrue(logged.get(0).contains("sleep") && logged.get(0).contains("region lookup"));
        assertTrue(logged.get(1).contains("NoSuchMethodError"));
    }

    @Test
    void repeatedFailuresAreLoggedOncePerQuietPeriod() {
        Runnable broken = () -> { throw new IllegalStateException("x"); };
        guard.run("sleep", 0L, broken);
        guard.run("sleep", 1_000L, broken);
        guard.run("sleep", 59_999L, broken);
        assertEquals(1, logged.size());
        guard.run("sleep", 60_000L, broken);
        assertEquals(2, logged.size());
    }
}
