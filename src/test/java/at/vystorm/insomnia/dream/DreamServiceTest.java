package at.vystorm.insomnia.dream;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DreamServiceTest {
    @Test void durationIsBoundedAndScalesFromFatigueAtEntry() {
        assertEquals(300_000L, DreamService.durationMillis(40, 40, 80, 300_000, 1_200_000));
        assertEquals(750_000L, DreamService.durationMillis(60, 40, 80, 300_000, 1_200_000));
        assertEquals(1_200_000L, DreamService.durationMillis(100, 40, 80, 300_000, 1_200_000));
        assertEquals(300_000L, DreamService.durationMillis(0, 40, 80, 300_000, 1_200_000));
    }

    @Test void claimIdentityIsStablePerPlayerVisitAndDungeon() {
        UUID player = UUID.fromString("4a69c6a5-7d9a-4b54-a454-bb1e097db837");
        String first = DreamService.stableSessionId(player, 1_761_211_200_000L, "insomnia:dreamweite");
        assertEquals(first, DreamService.stableSessionId(player, 1_761_211_200_000L, "insomnia:dreamweite"));
        assertNotEquals(first, DreamService.stableSessionId(player, 1_761_211_201_000L, "insomnia:dreamweite"));
        assertNotEquals(first, DreamService.stableSessionId(UUID.randomUUID(), 1_761_211_200_000L, "insomnia:dreamweite"));
        assertNotEquals(DreamService.stableClaimId(first, "cloud-ruins"), DreamService.stableClaimId(first, "crystal-vault"));
    }
}
