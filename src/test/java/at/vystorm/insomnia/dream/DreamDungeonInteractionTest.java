package at.vystorm.insomnia.dream;

import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStageTesting;
import Vystorm.vystorm_core.item.RewardPouch;
import Vystorm.vystorm_core.worlds.ManagedWorld;
import at.vystorm.insomnia.config.InsomniaConfig;
import at.vystorm.insomnia.dream.world.DreamLayout;
import at.vystorm.insomnia.dream.world.DreamWorldGenerator;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static at.vystorm.insomnia.dream.world.DreamLayout.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Handler-level tests without world generation, server registries or a live reward database. */
class DreamDungeonInteractionTest {
    @Test void crystalWrongOrderResetsAndCompletedSequenceCreditsExactlyOnce() throws Exception {
        Fixture f = new Fixture(DungeonType.CRYSTAL_VAULT);
        Player player = f.player();
        Object visit = f.visit(player);
        f.click(player, 0);
        assertEquals(1, progress(visit).get(f.dungeon.id()));
        f.click(player, 2);
        assertEquals(0, progress(visit).get(f.dungeon.id()));
        verifyNoInteractions(f.pouch);

        // Reward construction and material metadata are isolated from Paper's live item registry.
        Material reward = mock(Material.class);
        when(reward.getMaxStackSize()).thenReturn(64);
        when(f.config.dreamRewards(f.dungeon.id())).thenReturn(List.of("test_reward:2"));
        try (var materials = mockStatic(Material.class);
             var items = mockConstruction(ItemStack.class);
             var bukkit = mockStatic(Bukkit.class)) {
            materials.when(() -> Material.matchMaterial("test_reward")).thenReturn(reward);
            for (int i = 0; i < 4; i++) f.click(player, i);
            assertEquals(4, progress(visit).get(f.dungeon.id()));
            assertTrue(claimed(visit).contains(f.dungeon.id()));
            assertEquals(1, items.constructed().size());
            String claim = DreamService.stableClaimId("session-" + player.getUniqueId(), f.dungeon.id());
            verify(f.pouch).credit(eq(player.getUniqueId()), eq(claim), any(ItemStack[].class));
            f.click(player, 3);
            f.click(player, 0);
            verifyNoMoreInteractions(f.pouch);
        }
    }

    @Test void cloudMovementRequiresDreamWorldAndOrderedPerPlayerAnchors() throws Exception {
        Fixture f = new Fixture(DungeonType.CLOUD_RUINS);
        Player first = f.player(), second = f.player();
        Object firstVisit = f.visit(first), secondVisit = f.visit(second);
        f.move(first, f.dungeon.puzzleAnchors().getFirst(), mock(World.class));
        assertTrue(progress(firstVisit).isEmpty(), "same coordinates in a foreign world cannot progress");
        f.move(first, f.dungeon.puzzleAnchors().get(2), f.world);
        assertTrue(progress(firstVisit).isEmpty(), "later waypoint cannot skip the sequence");
        for (BlockPos anchor : f.dungeon.puzzleAnchors()) f.move(first, anchor, f.world);
        assertEquals(4, progress(firstVisit).get(f.dungeon.id()));
        assertTrue(progress(secondVisit).isEmpty(), "another player's visit remains isolated");
        f.move(second, f.dungeon.puzzleAnchors().getFirst(), f.world);
        assertEquals(1, progress(secondVisit).get(f.dungeon.id()));
        assertEquals(4, progress(firstVisit).get(f.dungeon.id()));
        verifyNoInteractions(f.pouch);
    }

    @Test void nightmareForeignDeathRetriesAndOwnerDeathCountsWithoutDropsOrDuplicateCredit() throws Exception {
        Fixture f = new Fixture(DungeonType.NIGHTMARE_KEEP);
        Player owner = f.player(), foreign = f.player();
        Object visit = f.visit(owner);
        set(visit, "waveSpawned", 3);
        set(visit, "waveKills", 1);
        LivingEntity foreignKill = f.mob(owner, foreign, visit);
        EntityDeathEvent foreignEvent = death(foreignKill);
        f.stages.fire(foreignEvent);
        assertEquals(1, get(visit, "waveKills"));
        assertEquals(2, get(visit, "waveSpawned"), "foreign death leaves a slot for spawn retry");
        assertTrue(foreignEvent.getDrops().isEmpty());
        verify(foreignEvent).setDroppedExp(0);

        LivingEntity ownerKill = f.mob(owner, owner, visit);
        EntityDeathEvent ownerEvent = death(ownerKill);
        f.stages.fire(ownerEvent);
        assertEquals(2, get(visit, "waveKills"));
        assertEquals(2, get(visit, "waveSpawned"));
        assertTrue(ownerEvent.getDrops().isEmpty());
        verify(ownerEvent).setDroppedExp(0);
        f.stages.fire(ownerEvent);
        assertEquals(2, get(visit, "waveKills"), "the same mob UUID cannot count twice");
        verifyNoInteractions(f.pouch);
    }

    @Test void playersCannotHurtEachOtherInTheDreamAndTheDeniedHitNeitherWakesNorEndsANap() throws Exception {
        Fixture f = new Fixture(DungeonType.NIGHTMARE_KEEP);
        Player victim = f.player(), attacker = f.player();
        when(victim.getWorld()).thenReturn(f.world);
        f.visit(victim);
        org.bukkit.event.entity.EntityDamageByEntityEvent hit = mock(org.bukkit.event.entity.EntityDamageByEntityEvent.class);
        boolean[] cancelled = {false};
        doAnswer(call -> { cancelled[0] = call.getArgument(0); return null; }).when(hit).setCancelled(anyBoolean());
        when(hit.isCancelled()).thenAnswer(call -> cancelled[0]);
        when(hit.getEntity()).thenReturn(victim); when(hit.getDamager()).thenReturn(attacker);

        f.stages.fire(hit);

        assertTrue(cancelled[0], "PvP in the dream is denied in PROTECT");
        verify(f.service).onEntityDamage(hit);
        verify(f.service, never()).onDamage(any());
        verify(f.service, never()).onNapDamage(any());
    }

    private static EntityDeathEvent death(LivingEntity mob) {
        EntityDeathEvent event = mock(EntityDeathEvent.class);
        when(event.getEntity()).thenReturn(mob);
        ItemStack drop = mock(ItemStack.class);
        List<ItemStack> drops = new ArrayList<>(List.of(drop));
        when(event.getDrops()).thenReturn(drops);
        return event;
    }

    private static final class Fixture {
        final DreamService service = mock(DreamService.class, CALLS_REAL_METHODS);
        final World world = mock(World.class);
        final InsomniaConfig config = mock(InsomniaConfig.class);
        final RewardPouch pouch = mock(RewardPouch.class);
        final Map<UUID, Object> active = new HashMap<>();
        /** The handlers of the shared events run through Core's stage dispatcher, the way the server delivers them. */
        final EventStageTesting stages = EventStageTesting.detached();
        final NamespacedKey mobKey = new NamespacedKey("insomnia", "dream_mob");
        final NamespacedKey ownerKey = new NamespacedKey("insomnia", "dream_owner");
        final NamespacedKey visitKey = new NamespacedKey("insomnia", "dream_visit");
        final Dungeon dungeon;

        Fixture(DungeonType type) throws Exception {
            var hub = DreamService.class.getDeclaredField("api");
            hub.setAccessible(true);
            hub.set(service, at.vystorm.insomnia.testutil.TestCore.api());
            List<BlockPos> anchors = List.of(new BlockPos(0, 100, 10), new BlockPos(0, 100, 20),
                    new BlockPos(0, 100, 30), new BlockPos(0, 100, 40));
            dungeon = new Dungeon(type.name().toLowerCase().replace('_', '-'), type, new BlockPos(0, 100, 0), anchors,
                    new BlockPos(0, 100, 45), new BlockPos(0, 100, 25), new Bounds(-10, 10, -1, 50));
            DreamWorldGenerator generator = mock(DreamWorldGenerator.class);
            when(generator.layout()).thenReturn(new DreamLayout(new BlockPos(0, 100, 0), List.of(dungeon), dungeon.bounds(), "test"));
            ManagedWorld managed = mock(ManagedWorld.class);
            when(managed.world()).thenReturn(world);
            set(service, "active", active); set(service, "config", config); set(service, "worldId", "test:dream");
            set(service, "generator", generator); set(service, "world", managed); set(service, "pouch", pouch);
            set(service, "pendingRewards", new LinkedHashMap<>()); set(service, "warnedClaims", new HashSet<>());
            set(service, "mobKey", mobKey); set(service, "ownerKey", ownerKey); set(service, "visitKey", visitKey);
            set(service, "plugin", EventStageTesting.plugin("VystormInsomnia"));
            service.handleSharedEvents(stages.stages());
        }

        Player player() {
            Player player = mock(Player.class);
            when(player.getUniqueId()).thenReturn(UUID.randomUUID());
            return player;
        }

        Object visit(Player player) throws Exception {
            Class<?> type = Class.forName(DreamService.class.getName() + "$DreamVisit");
            var constructor = type.getDeclaredConstructor(DreamService.class, String.class, String.class, long.class, Location.class);
            constructor.setAccessible(true);
            Object visit = constructor.newInstance(service, "session-" + player.getUniqueId(), "test:dream", Long.MAX_VALUE, null);
            active.put(player.getUniqueId(), visit);
            // Movement tests do not start the encounter; they exercise only waypoint handling.
            set(visit, "nightmareStarted", true);
            return visit;
        }

        void click(Player player, int index) {
            BlockPos anchor = dungeon.puzzleAnchors().get(index);
            Block block = mock(Block.class);
            when(block.getWorld()).thenReturn(world);
            when(block.getX()).thenReturn(anchor.x()); when(block.getY()).thenReturn(anchor.y() - 1); when(block.getZ()).thenReturn(anchor.z());
            when(block.getLocation()).thenReturn(new Location(world, anchor.x(), anchor.y() - 1, anchor.z()));
            PlayerInteractEvent event = mock(PlayerInteractEvent.class);
            when(event.getPlayer()).thenReturn(player); when(event.getHand()).thenReturn(EquipmentSlot.HAND);
            when(event.getAction()).thenReturn(Action.RIGHT_CLICK_BLOCK); when(event.getClickedBlock()).thenReturn(block);
            stages.fire(event);
        }

        void move(Player player, BlockPos anchor, World destinationWorld) {
            PlayerMoveEvent event = mock(PlayerMoveEvent.class);
            when(event.getPlayer()).thenReturn(player);
            when(event.getTo()).thenReturn(new Location(destinationWorld, anchor.x() + .5, anchor.y(), anchor.z() + .5));
            service.onMove(event);
        }

        LivingEntity mob(Player owner, Player killer, Object visit) throws Exception {
            LivingEntity mob = mock(LivingEntity.class);
            UUID id = UUID.randomUUID();
            when(mob.getUniqueId()).thenReturn(id); when(mob.getKiller()).thenReturn(killer);
            PersistentDataContainer data = mock(PersistentDataContainer.class);
            when(mob.getPersistentDataContainer()).thenReturn(data);
            when(data.has(mobKey, PersistentDataType.BYTE)).thenReturn(true);
            String ownerId = owner.getUniqueId().toString();
            String sessionId = (String) get(visit, "sessionId");
            when(data.get(ownerKey, PersistentDataType.STRING)).thenReturn(ownerId);
            when(data.get(visitKey, PersistentDataType.STRING)).thenReturn(sessionId);
            mobs(visit).add(id);
            return mob;
        }
    }

    @SuppressWarnings("unchecked") private static Map<String, Integer> progress(Object visit) throws Exception { return (Map<String, Integer>) get(visit, "progress"); }
    @SuppressWarnings("unchecked") private static Set<String> claimed(Object visit) throws Exception { return (Set<String>) get(visit, "claimed"); }
    @SuppressWarnings("unchecked") private static Set<UUID> mobs(Object visit) throws Exception { return (Set<UUID>) get(visit, "mobs"); }
    private static Object get(Object target, String name) throws Exception { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
    private static void set(Object target, String name, Object value) throws Exception { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value); }
}
