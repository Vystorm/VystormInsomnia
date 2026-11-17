package at.vystorm.insomnia.core;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Runs each step of the master runtime tick on its own, so one failing service (or a Core API mismatch showing up
 * as a LinkageError) does not stop the others. Repeated failures of the same step are logged at most once per
 * quiet period.
 */
public final class TickGuard {
    private final Consumer<String> log;
    private final long quietMillis;
    private final Map<String, Long> lastLogged = new HashMap<>();

    public TickGuard(Consumer<String> log, long quietMillis) {
        this.log = log;
        this.quietMillis = quietMillis;
    }

    public boolean run(String step, long now, Runnable action) {
        try {
            action.run();
            return true;
        } catch (RuntimeException | LinkageError failure) {
            Long last = lastLogged.get(step);
            if (last == null || now - last >= quietMillis) {
                lastLogged.put(step, now);
                log.accept("Runtime tick step '" + step + "' failed: " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
            }
            return false;
        }
    }
}
