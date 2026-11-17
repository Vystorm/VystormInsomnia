package at.vystorm.insomnia.service;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PlayerInsightHistoryTest {
    @Test
    void keepsBoundedSamplesAndActualEventMarkersPerPlayer() {
        PlayerInsightHistory history = new PlayerInsightHistory();
        UUID player = UUID.randomUUID();
        history.sample(player, 1_000L, 10, 5);
        history.mark(player, 2_000L, 10, 5, "stimulant:coffee");
        for (int i = 0; i < PlayerInsightHistory.MAX_ENTRIES + 4; i++) {
            history.mark(player, 3_000L + i, i, i, "sleep_started");
        }
        assertEquals(PlayerInsightHistory.MAX_ENTRIES, history.recent(player).size());
        assertEquals("sleep_started", history.recent(player).getFirst().marker());
        assertTrue(history.recent(UUID.randomUUID()).isEmpty());
        history.forget(player);
        assertTrue(history.recent(player).isEmpty());
    }
}
