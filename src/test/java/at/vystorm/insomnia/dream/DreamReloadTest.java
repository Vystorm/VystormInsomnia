package at.vystorm.insomnia.dream;

import Vystorm.vystorm_core.item.RewardPouch;
import Vystorm.vystorm_core.worlds.ManagedWorlds;
import at.vystorm.insomnia.config.InsomniaConfig;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DreamReloadTest {
    @Test
    void disablingDreamEntryOnReloadStillAllowsExistingRewardsToBeClaimed() throws Exception {
        RewardPouch pouch = mock(RewardPouch.class);
        DreamService service = service(pouch);
        Player player = mock(Player.class);
        when(pouch.claim(player)).thenReturn(RewardPouch.ClaimResult.DELIVERED);
        try (var worlds = mockStatic(ManagedWorlds.class)) {
            service.reload();
            service.reload();
            assertFalse(service.enabled());
            assertEquals(RewardPouch.ClaimResult.DELIVERED, service.claim(player));
            verify(pouch).claim(player);
        }
    }

    @Test
    void disablingDreamEntryDoesNotDiscardPendingDungeonCredits() throws Exception {
        RewardPouch pouch = mock(RewardPouch.class);
        DreamService service = service(pouch);
        UUID id = UUID.randomUUID();
        String claim = "session:crystal-vault";
        ItemStack[] items = {mock(ItemStack.class)};
        Class<?> rewardType = Class.forName(DreamService.class.getName() + "$PendingReward");
        var constructor = rewardType.getDeclaredConstructor(UUID.class, String.class, String.class, ItemStack[].class);
        constructor.setAccessible(true);
        var pending = new HashMap<String, Object>();
        pending.put(claim, constructor.newInstance(id, claim, "crystal-vault", items));
        field(service, "pendingRewards", pending);
        try (var worlds = mockStatic(ManagedWorlds.class); var bukkit = mockStatic(Bukkit.class)) {
            service.reload();
            service.tick(10_000L);
            verify(pouch).credit(id, claim, items);
            assertTrue(pending.isEmpty());
        }
    }

    private static DreamService service(RewardPouch pouch) throws Exception {
        DreamService service = mock(DreamService.class, CALLS_REAL_METHODS);
        field(service, "config", mock(InsomniaConfig.class));
        field(service, "api", at.vystorm.insomnia.testutil.TestCore.api());
        field(service, "plugin", mock(Plugin.class));
        field(service, "pouch", pouch);
        field(service, "naps", new HashMap<>());
        field(service, "active", new HashMap<>());
        field(service, "pendingEntries", new HashMap<>());
        field(service, "pendingRewards", new HashMap<>());
        field(service, "warnedClaims", new HashSet<>());
        return service;
    }

    private static void field(Object target, String name, Object value) throws Exception {
        var field = DreamService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
