package at.vystorm.insomnia.service;

import at.vystorm.insomnia.config.InsomniaConfig;
import at.vystorm.insomnia.core.CoreBridge;
import at.vystorm.insomnia.integration.CityClaimIntegration;
import at.vystorm.insomnia.testutil.TestServer;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class InsomniaMobHandlerTest {
    private Server server;
    private JavaPlugin plugin;
    private InsomniaConfig config;
    private CoreBridge core;
    private CityClaimIntegration regions;
    private InsomniaMobService service;
    private World world;
    private Player owner;
    private Player stranger;
    private Zombie zombie;
    private Skeleton skeleton;

    @BeforeEach
    void setUp() {
        server = TestServer.fresh();
        plugin = mock(JavaPlugin.class);
        config = mock(InsomniaConfig.class);
        when(config.mobLifetimeMillis()).thenReturn(60_000L);
        when(config.mobMinDistance()).thenReturn(8);
        when(config.mobMaxDistance()).thenReturn(12);
        YamlConfiguration tiers = new YamlConfiguration();
        tiers.set("tiers.1.min_fatigue", 70.0);
        tiers.set("tiers.1.entities", List.of("ZOMBIE"));
        when(config.mobTiers()).thenReturn(tiers.getConfigurationSection("tiers"));
        core = mock(CoreBridge.class);
        when(core.api()).thenReturn(at.vystorm.insomnia.testutil.TestCore.api());
        regions = mock(CityClaimIntegration.class);
        service = new InsomniaMobService(plugin, config, core, null, null, regions);

        world = mock(World.class);
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);
        when(server.getWorlds()).thenReturn(List.of(world));
        owner = player();
        stranger = player();
        zombie = entity(Zombie.class);
        skeleton = entity(Skeleton.class);
        when(server.getOnlinePlayers()).thenAnswer(ignored -> List.of(owner, stranger));
        service.track(zombie, owner, 1, 0L);
        service.track(skeleton, owner, 1, 0L);
    }

    private Player player() {
        Player player = entity(Player.class);
        when(player.getWorld()).thenReturn(world);
        return player;
    }

    private <T extends Entity> T entity(Class<T> type) {
        T entity = mock(type);
        UUID id = UUID.randomUUID();
        when(entity.getUniqueId()).thenReturn(id);
        when(world.getEntity(id)).thenReturn(entity);
        return entity;
    }

    private static Arrow arrowFrom(Entity shooter) {
        Arrow arrow = mock(Arrow.class);
        when(arrow.getUniqueId()).thenReturn(UUID.randomUUID());
        when(arrow.getShooter()).thenReturn((org.bukkit.projectiles.ProjectileSource) shooter);
        return arrow;
    }

    private static EntityDamageByEntityEvent hit(Entity damager, Entity victim) {
        EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
        when(event.getDamager()).thenReturn(damager);
        when(event.getEntity()).thenReturn(victim);
        return event;
    }

    @Test
    void personalMobIsPrivateBeforeWorldTrackingBegins() {
        when(config.maxMobsGlobal()).thenReturn(10);
        when(config.maxMobsPerPlayer()).thenReturn(10);
        InsomniaMobService spawning = spy(service);
        Location location = new Location(world, 0, 64, 0);
        doReturn(location).when(spawning).spawnLocation(owner);
        Zombie fresh = entity(Zombie.class);
        when(world.spawn(eq(location), eq(Zombie.class), org.mockito.ArgumentMatchers.<java.util.function.Consumer<Zombie>>any())).thenAnswer(call -> {
            java.util.function.Consumer<Zombie> initializer = call.getArgument(2);
            initializer.accept(fresh);
            verify(fresh).setVisibleByDefault(false);
            verify(owner, never()).showEntity(plugin, fresh);
            return fresh;
        });
        try (var attributes = mockStatic(Vystorm.vystorm_core.compat.ServerCompat.class)) {
            assertTrue(spawning.spawn(owner, 1, 0));
        }
        verify(owner).showEntity(plugin, fresh);
        verify(stranger).hideEntity(plugin, fresh);
    }

    @Test
    void spawnHidesTheMobFromEveryoneButTheOwner() {
        verify(stranger).hideEntity(plugin, zombie);
        verify(owner, never()).hideEntity(plugin, zombie);
        verify(owner).showEntity(plugin, zombie);
    }

    @Test
    void playersJoiningLaterDoNotSeeForeignMobs() {
        Player late = player();
        service.hideFrom(late);
        verify(late).hideEntity(plugin, zombie);
        verify(late).hideEntity(plugin, skeleton);

        clearInvocations(owner);
        service.hideFrom(owner);
        verify(owner, never()).hideEntity(any(), any());
    }

    @Test
    void ownerMayHitOwnMobInMeleeAndWithProjectiles() {
        EntityDamageByEntityEvent melee = hit(owner, zombie);
        EntityDamageByEntityEvent arrow = hit(arrowFrom(owner), zombie);
        service.handleDamage(melee);
        service.handleDamage(arrow);
        verify(melee, never()).setCancelled(true);
        verify(arrow, never()).setCancelled(true);
    }

    @Test
    void strangersCannotHitForeignMobsInMeleeOrWithProjectiles() {
        EntityDamageByEntityEvent melee = hit(stranger, zombie);
        EntityDamageByEntityEvent arrow = hit(arrowFrom(stranger), zombie);
        assertTrue(service.handleDamage(melee));
        assertTrue(service.handleDamage(arrow));
        verify(melee).setCancelled(true);
        verify(arrow).setCancelled(true);
    }

    @Test
    void mobProjectilesOnlyHurtTheOwner() {
        EntityDamageByEntityEvent onStranger = hit(arrowFrom(skeleton), stranger);
        EntityDamageByEntityEvent onOwner = hit(arrowFrom(skeleton), owner);
        EntityDamageByEntityEvent meleeOnStranger = hit(zombie, stranger);
        service.handleDamage(onStranger);
        service.handleDamage(onOwner);
        service.handleDamage(meleeOnStranger);
        verify(onStranger).setCancelled(true);
        verify(meleeOnStranger).setCancelled(true);
        verify(onOwner, never()).setCancelled(true);
    }

    @Test
    void unrelatedFightsAreIgnored() {
        Zombie wild = entity(Zombie.class);
        EntityDamageByEntityEvent event = hit(wild, stranger);
        assertFalse(service.handleDamage(event));
        verify(event, never()).setCancelled(anyBoolean());
    }

    @Test
    void mobsOnlyTargetTheirOwnerAndAreNeverTargeted() {
        EntityTargetLivingEntityEvent strangerTarget = target(zombie, stranger);
        EntityTargetLivingEntityEvent ownerTarget = target(zombie, owner);
        Zombie wild = entity(Zombie.class);
        EntityTargetLivingEntityEvent targeted = target(wild, zombie);
        service.handleTarget(strangerTarget);
        service.handleTarget(ownerTarget);
        service.handleTarget(targeted);
        verify(strangerTarget).setCancelled(true);
        verify(ownerTarget, never()).setCancelled(true);
        verify(targeted).setCancelled(true);
    }

    private static EntityTargetLivingEntityEvent target(Entity source, org.bukkit.entity.LivingEntity target) {
        EntityTargetLivingEntityEvent event = mock(EntityTargetLivingEntityEvent.class);
        when(event.getEntity()).thenReturn(source);
        when(event.getTarget()).thenReturn(target);
        return event;
    }

    @Test
    void dropsAreReservedForTheOwnerIncludingHoppers() {
        Item drop = mock(Item.class);
        String ownerId = owner.getUniqueId().toString();
        when(core.getNbt(drop, "insomnia_drop_owner")).thenReturn(ownerId);
        EntityPickupItemEvent byOwner = pickup(owner, drop);
        EntityPickupItemEvent byStranger = pickup(stranger, drop);
        Zombie picker = entity(Zombie.class);
        EntityPickupItemEvent byZombie = pickup(picker, drop);
        service.handlePickup(byOwner);
        service.handlePickup(byStranger);
        service.handlePickup(byZombie);
        verify(byOwner, never()).setCancelled(true);
        verify(byStranger).setCancelled(true);
        verify(byZombie).setCancelled(true);

        InventoryPickupItemEvent hopper = mock(InventoryPickupItemEvent.class);
        when(hopper.getItem()).thenReturn(drop);
        assertTrue(service.handleInventoryPickup(hopper));
        verify(hopper).setCancelled(true);

        Item normal = mock(Item.class);
        InventoryPickupItemEvent normalHopper = mock(InventoryPickupItemEvent.class);
        when(normalHopper.getItem()).thenReturn(normal);
        assertFalse(service.handleInventoryPickup(normalHopper));
        verify(normalHopper, never()).setCancelled(anyBoolean());
    }

    private static EntityPickupItemEvent pickup(org.bukkit.entity.LivingEntity who, Item item) {
        EntityPickupItemEvent event = mock(EntityPickupItemEvent.class);
        when(event.getEntity()).thenReturn(who);
        when(event.getItem()).thenReturn(item);
        return event;
    }

    @Test
    void deathClearsVanillaLootAndUnregistersTheMob() {
        EntityDeathEvent event = mock(EntityDeathEvent.class);
        List<ItemStack> drops = new ArrayList<>(List.of(mock(ItemStack.class)));
        when(event.getEntity()).thenReturn(zombie);
        when(event.getDrops()).thenReturn(drops);

        assertTrue(service.handleDeath(event));
        assertTrue(drops.isEmpty());
        verify(event).setDroppedExp(0);
        Player late = player();
        service.hideFrom(late);
        verify(late, never()).hideEntity(plugin, zombie);

        EntityDeathEvent wild = mock(EntityDeathEvent.class);
        Zombie other = entity(Zombie.class);
        when(wild.getEntity()).thenReturn(other);
        assertFalse(service.handleDeath(wild));
    }

    @Test
    void spawnSearchFollowsThePlayerUnderground() {
        when(owner.getLocation()).thenReturn(new Location(world, 0.5, 20, 0.5));
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        // Cave: stone below y=20, open air from 20 upwards, a surface far above would be y=80.
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(call -> block(call.getArgument(1)));

        Location spawn = service.spawnLocation(owner);

        assertNotNull(spawn);
        assertEquals(20.0, spawn.getY());
        verify(world, never()).getHighestBlockYAt(anyInt(), anyInt());
    }

    @Test
    void noSpawnInUnloadedChunksOrProtectedAreas() {
        when(owner.getLocation()).thenReturn(new Location(world, 0.5, 20, 0.5));
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(call -> block(call.getArgument(1)));

        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(false);
        assertNull(service.spawnLocation(owner));

        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        when(regions.protectedArea(any())).thenReturn(true);
        assertNull(service.spawnLocation(owner));
    }

    private Block block(int y) {
        Block block = mock(Block.class);
        boolean solid = y < 20;
        when(block.isSolid()).thenReturn(solid);
        when(block.isPassable()).thenReturn(!solid);
        when(block.getRelative(BlockFace.UP)).thenAnswer(ignored -> block(y + 1));
        when(block.getRelative(BlockFace.DOWN)).thenAnswer(ignored -> block(y - 1));
        return block;
    }
}
