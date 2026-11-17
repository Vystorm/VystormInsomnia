package at.vystorm.insomnia.service;

import at.vystorm.insomnia.config.InsomniaConfig;
import at.vystorm.insomnia.core.CoreBridge;
import at.vystorm.insomnia.model.PlayerFatigueState;
import at.vystorm.insomnia.testutil.FakePdc;
import at.vystorm.insomnia.testutil.TestServer;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class MicrosleepServiceTest {
    private Server server;
    private InsomniaConfig config;
    private CoreBridge core;
    private FatigueService fatigue;
    private SleepService sleep;
    private ActivityService activity;
    private VisibilityLocks visibility;
    private final List<Runnable> scheduled = new ArrayList<>();
    private MicrosleepService service;
    private World world;
    private Player owner;
    private Player viewer;
    private ArmorStand body;
    private ArmorStand camera;
    private ItemStack sword;
    private ItemStack swordCopy;
    private final UUID worldId = UUID.randomUUID();
    private final FakePdc pdc = new FakePdc();

    @BeforeEach
    void setUp() {
        server = TestServer.fresh();
        config = mock(InsomniaConfig.class);
        when(config.microsleepMinSeconds()).thenReturn(2);
        when(config.microsleepMaxSeconds()).thenReturn(2);
        core = mock(CoreBridge.class);
        when(core.api()).thenReturn(at.vystorm.insomnia.testutil.TestCore.api());
        fatigue = mock(FatigueService.class);
        when(fatigue.ready(any(UUID.class))).thenReturn(true);
        sleep = mock(SleepService.class);
        activity = mock(ActivityService.class);
        JavaPlugin plugin = mock(JavaPlugin.class);
        visibility = new VisibilityLocks(plugin);
        service = new MicrosleepService(plugin, config, core, fatigue, sleep, activity, visibility,
                (task, ticks) -> scheduled.add(task));

        world = mock(World.class);
        when(world.getUID()).thenReturn(worldId);
        when(server.getWorld(worldId)).thenReturn(world);
        UUID ownerId = UUID.randomUUID();
        owner = player(ownerId);
        viewer = player(UUID.randomUUID());
        when(owner.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(owner.isOnGround()).thenReturn(true);
        when(owner.getPersistentDataContainer()).thenReturn(pdc.container);
        when(owner.teleport(any(Location.class))).thenReturn(true);
        when(owner.getLocation()).thenReturn(new Location(world, 10.5, 64.0, -3.5, 90f, 10f));
        PlayerFatigueState state = new PlayerFatigueState(ownerId, 0L);
        when(fatigue.require(ownerId)).thenReturn(state);

        PlayerInventory inventory = mock(PlayerInventory.class);
        sword = mock(ItemStack.class);
        swordCopy = mock(ItemStack.class);
        when(sword.getType()).thenReturn(Material.DIAMOND_SWORD);
        doReturn(swordCopy).when(sword).clone();
        when(inventory.getItemInMainHand()).thenReturn(sword);
        when(owner.getInventory()).thenReturn(inventory);

        body = stand(UUID.randomUUID());
        camera = stand(UUID.randomUUID());
        List<ArmorStand> spawns = new ArrayList<>(List.of(body, camera));
        when(world.spawn(any(Location.class), eq(ArmorStand.class), any(Consumer.class))).thenAnswer(invocation -> {
            ArmorStand next = spawns.removeFirst();
            Consumer<ArmorStand> init = invocation.getArgument(2);
            init.accept(next);
            return next;
        });
        when(server.getOnlinePlayers()).thenAnswer(ignored -> List.of(owner, viewer));
        when(server.getPlayer(ownerId)).thenReturn(owner);
    }

    private Player player(UUID id) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getWorld()).thenReturn(world);
        when(player.isOnline()).thenReturn(true);
        return player;
    }

    private ArmorStand stand(UUID id) {
        ArmorStand stand = mock(ArmorStand.class);
        when(stand.getUniqueId()).thenReturn(id);
        when(stand.getWorld()).thenReturn(world);
        when(stand.isValid()).thenReturn(true);
        when(stand.getLocation()).thenAnswer(ignored -> new Location(world, 10.5, 65.35, -3.5));
        return stand;
    }

    @Test
    void placeholderBodyHoldsNoRealItemsAndLocksEverySlot() {
        assertTrue(service.start(owner, 1_000L));

        verify(body, never()).getEquipment();
        for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
                EquipmentSlot.FEET, EquipmentSlot.HAND, EquipmentSlot.OFF_HAND)) {
            verify(body).addEquipmentLock(slot, ArmorStand.LockType.ADDING_OR_CHANGING);
            verify(body).addEquipmentLock(slot, ArmorStand.LockType.REMOVING_OR_CHANGING);
        }
        verify(core).setNbt(body, MicrosleepService.TAG_PLACEHOLDER, owner.getUniqueId().toString());
        verify(owner).setGameMode(GameMode.SPECTATOR);
    }

    @Test
    @SuppressWarnings("unchecked")
    void equipmentIsMirroredToOtherViewersAsPacketsOnly() {
        service.start(owner, 1_000L);
        assertEquals(1, scheduled.size());
        scheduled.getFirst().run();

        ArgumentCaptor<Map<EquipmentSlot, ItemStack>> items = ArgumentCaptor.forClass(Map.class);
        verify(viewer).sendEquipmentChange(eq(body), items.capture());
        assertEquals(Map.of(EquipmentSlot.HAND, swordCopy), items.getValue());
        verify(owner, never()).sendEquipmentChange(any(), anyMap());
    }

    @Test
    void mirrorIsSkippedWhenTheSessionAlreadyEnded() {
        service.start(owner, 1_000L);
        service.end(owner, true);
        scheduled.getFirst().run();
        verify(viewer, never()).sendEquipmentChange(any(), anyMap());
    }

    @Test
    void manipulatingASleepingBodyIsCancelled() {
        service.start(owner, 1_000L);
        PlayerArmorStandManipulateEvent event = manipulate(body);
        assertTrue(service.handleManipulate(event));
        verify(event).setCancelled(true);
    }

    @Test
    void manipulatingAnOrphanedTaggedBodyIsCancelled() {
        ArmorStand orphan = stand(UUID.randomUUID());
        String foreignOwner = UUID.randomUUID().toString();
        when(core.getNbt(orphan, MicrosleepService.TAG_PLACEHOLDER)).thenReturn(foreignOwner);
        PlayerArmorStandManipulateEvent event = manipulate(orphan);
        assertTrue(service.handleManipulate(event));
        verify(event).setCancelled(true);
    }

    @Test
    void foreignArmorStandsStayUntouched() {
        ArmorStand foreign = stand(UUID.randomUUID());
        PlayerArmorStandManipulateEvent event = manipulate(foreign);
        assertFalse(service.handleManipulate(event));
        verify(event, never()).setCancelled(anyBoolean());
    }

    @Test
    void damageToAnOrphanedBodyIsCancelledAndRemovesIt() {
        ArmorStand orphan = stand(UUID.randomUUID());
        String foreignOwner = UUID.randomUUID().toString();
        when(core.getNbt(orphan, MicrosleepService.TAG_PLACEHOLDER)).thenReturn(foreignOwner);
        EntityDamageEvent event = mock(EntityDamageEvent.class);
        when(event.getEntity()).thenReturn(orphan);
        assertTrue(service.handlePlaceholderDamage(event));
        verify(event).setCancelled(true);
        verify(orphan).remove();
    }

    @Test
    void startPersistsTheSessionAndEndClearsIt() {
        when(owner.getAllowFlight()).thenReturn(true);
        service.start(owner, 1_000L);

        String raw = (String) pdc.values.get(MicrosleepService.SESSION_KEY);
        MicrosleepService.Snapshot snapshot = MicrosleepService.Snapshot.decode(raw);
        assertNotNull(snapshot);
        assertEquals(GameMode.SURVIVAL, snapshot.gameMode());
        assertTrue(snapshot.allowFlight());
        assertEquals(worldId, snapshot.world());
        assertEquals(10.5, snapshot.x());
        assertEquals(-3.5, snapshot.z());
        assertEquals(90f, snapshot.yaw());
        assertEquals(body.getUniqueId(), snapshot.body());
        assertEquals(camera.getUniqueId(), snapshot.camera());
        verify(body).setPersistent(false);
        verify(camera).setPersistent(false);

        service.end(owner, true);
        assertFalse(pdc.values.containsKey(MicrosleepService.SESSION_KEY));
    }

    @Test
    void sessionIsWrittenBeforeTheGameModeChanges() {
        doAnswer(call -> {
            assertTrue(pdc.values.containsKey(MicrosleepService.SESSION_KEY), "spectator set before session was persisted");
            return null;
        }).when(owner).setGameMode(GameMode.SPECTATOR);
        assertTrue(service.start(owner, 1_000L));
    }

    @Test
    void joinAfterCrashRestoresGameModeFlightPositionAndRemovesLeftovers() {
        service.start(owner, 1_000L);
        String raw = (String) pdc.values.get(MicrosleepService.SESSION_KEY);
        MicrosleepService crashed = new MicrosleepService(mock(JavaPlugin.class), config, core, fatigue, sleep, activity, visibility, (t, d) -> { });
        pdc.values.put(MicrosleepService.SESSION_KEY, raw);
        when(owner.getGameMode()).thenReturn(GameMode.SPECTATOR);
        when(server.getEntity(body.getUniqueId())).thenReturn(body);
        clearInvocations(owner, body);

        assertTrue(crashed.restoreAfterCrash(owner));

        verify(owner).setGameMode(GameMode.SURVIVAL);
        verify(owner).setAllowFlight(false);
        ArgumentCaptor<Location> target = ArgumentCaptor.forClass(Location.class);
        verify(owner).teleport(target.capture());
        assertEquals(world, target.getValue().getWorld());
        assertEquals(10.5, target.getValue().getX());
        assertEquals(64.0, target.getValue().getY());
        assertEquals(10f, target.getValue().getPitch());
        verify(body).remove();
        assertFalse(pdc.values.containsKey(MicrosleepService.SESSION_KEY));
        assertFalse(crashed.restoreAfterCrash(owner), "restore must run only once");
    }

    @Test
    void joinWithoutStoredSessionChangesNothing() {
        assertFalse(service.restoreAfterCrash(owner));
        verify(owner, never()).setGameMode(any());
        verify(owner, never()).teleport(any(Location.class));
    }

    @Test
    void corruptSessionFallsBackToSurvival() {
        pdc.values.put(MicrosleepService.SESSION_KEY, "garbage");
        when(owner.getGameMode()).thenReturn(GameMode.SPECTATOR);
        assertTrue(service.restoreAfterCrash(owner));
        verify(owner).setGameMode(GameMode.SURVIVAL);
        assertFalse(pdc.values.containsKey(MicrosleepService.SESSION_KEY));
    }

    @Test
    void snapshotRoundTripsAndRejectsUnknownFormats() {
        MicrosleepService.Snapshot snapshot = new MicrosleepService.Snapshot(GameMode.ADVENTURE, true, true, worldId,
                1.25, -60.0, 3.75, -45.5f, 12.0f, UUID.randomUUID(), UUID.randomUUID());
        assertEquals(snapshot, MicrosleepService.Snapshot.decode(snapshot.encode()));
        assertNull(MicrosleepService.Snapshot.decode("2;" + snapshot.encode().substring(2)));
        assertNull(MicrosleepService.Snapshot.decode(snapshot.encode().replace("ADVENTURE", "FLYING")));
    }

    @Test
    void onlyUntrackedTaggedEntitiesAreOrphans() {
        service.start(owner, 1_000L);
        when(core.getNbt(body, MicrosleepService.TAG_PLACEHOLDER)).thenReturn("x");
        when(core.getNbt(camera, MicrosleepService.TAG_CAMERA)).thenReturn("x");
        assertFalse(service.isOrphan(body));
        assertFalse(service.isOrphan(camera));

        ArmorStand leftoverCamera = stand(UUID.randomUUID());
        when(core.getNbt(leftoverCamera, MicrosleepService.TAG_CAMERA)).thenReturn("x");
        assertTrue(service.isOrphan(leftoverCamera));
        assertFalse(service.isOrphan(stand(UUID.randomUUID())));
    }

    @Test
    void damageOnTheBodyWakesThePlayerBeforeHittingThem() {
        service.start(owner, 1_000L);
        org.bukkit.event.entity.EntityDamageByEntityEvent event = mock(org.bukkit.event.entity.EntityDamageByEntityEvent.class);
        org.bukkit.entity.Zombie zombie = mock(org.bukkit.entity.Zombie.class);
        when(event.getEntity()).thenReturn(body);
        when(event.getDamager()).thenReturn(zombie);
        when(event.getFinalDamage()).thenReturn(4.0);

        assertTrue(service.handlePlaceholderDamage(event));

        org.mockito.InOrder order = inOrder(owner);
        order.verify(owner).setGameMode(GameMode.SURVIVAL);
        order.verify(owner).damage(4.0, zombie);
        verify(event).setCancelled(true);
        assertFalse(service.isActive(owner.getUniqueId()));
    }

    @Test
    void doesNotDozeOffInUnsafeSituations() {
        List<Runnable> unsafe = List.of(
                () -> when(owner.isInsideVehicle()).thenReturn(true),
                () -> when(owner.isGliding()).thenReturn(true),
                () -> when(owner.isInWater()).thenReturn(true),
                () -> when(owner.isInLava()).thenReturn(true),
                () -> when(owner.isOnGround()).thenReturn(false),
                () -> when(activity.inCombat(owner.getUniqueId(), 1_000L)).thenReturn(true));
        for (Runnable situation : unsafe) {
            owner = player(UUID.randomUUID());
            when(owner.getGameMode()).thenReturn(GameMode.SURVIVAL);
            when(owner.isOnGround()).thenReturn(true);
            when(owner.getLocation()).thenReturn(new Location(world, 0, 64, 0));
            situation.run();
            assertFalse(service.start(owner, 1_000L));
            verify(owner, never()).setGameMode(any());
        }
    }

    @Test
    void ownCameraTeleportKeepsTheSession() {
        service.start(owner, 1_000L);
        PlayerTeleportEvent event = teleport(PlayerTeleportEvent.TeleportCause.SPECTATE, new Location(world, 10.5, 65.35, -3.5));
        service.handleTeleport(event);
        verify(event, never()).setCancelled(true);
        assertTrue(service.isActive(owner.getUniqueId()));
    }

    @Test
    void spectatorTeleportElsewhereIsRefused() {
        service.start(owner, 1_000L);
        PlayerTeleportEvent event = teleport(PlayerTeleportEvent.TeleportCause.SPECTATE, new Location(world, 400, 70, 400));
        service.handleTeleport(event);
        verify(event).setCancelled(true);
        assertTrue(service.isActive(owner.getUniqueId()));
    }

    @Test
    void otherTeleportsEndTheSessionWithoutReturning() {
        service.start(owner, 1_000L);
        clearInvocations(owner);
        service.handleTeleport(teleport(PlayerTeleportEvent.TeleportCause.COMMAND, new Location(world, 400, 70, 400)));
        assertFalse(service.isActive(owner.getUniqueId()));
        verify(owner).setGameMode(GameMode.SURVIVAL);
        verify(owner, never()).teleport(any(Location.class));
    }

    private PlayerTeleportEvent teleport(PlayerTeleportEvent.TeleportCause cause, Location to) {
        PlayerTeleportEvent event = mock(PlayerTeleportEvent.class);
        when(event.getPlayer()).thenReturn(owner);
        when(event.getCause()).thenReturn(cause);
        when(event.getTo()).thenReturn(to);
        return event;
    }

    @Test
    void sessionEndsByItselfAndRestoresFlight() {
        when(owner.getAllowFlight()).thenReturn(true);
        when(owner.isFlying()).thenReturn(false);
        service.start(owner, 1_000L);
        service.tick(2_999L);
        assertTrue(service.isActive(owner.getUniqueId()));
        clearInvocations(owner);

        service.tick(3_000L);                         // 2 s configured

        assertFalse(service.isActive(owner.getUniqueId()));
        verify(owner).setGameMode(GameMode.SURVIVAL);
        verify(owner).setAllowFlight(true);
        verify(owner).setFlying(false);
        verify(owner).teleport(any(Location.class));
        verify(viewer).showPlayer(any(JavaPlugin.class), eq(owner));
        verify(body).remove();
        verify(camera).remove();
    }

    @Test
    void lowFatiguePlayersCostNoPersistentDataReads() {
        when(config.microsleepEnabled()).thenReturn(true);
        when(config.microsleepMinFatigue()).thenReturn(80.0);
        when(server.getOnlinePlayers()).thenAnswer(ignored -> List.of(owner));
        clearInvocations(owner);

        service.tick(10_000L);

        verify(owner, never()).getPersistentDataContainer();
        assertFalse(service.isActive(owner.getUniqueId()));
    }

    @Test
    void tiredPlayerStillSkipsMicrosleepDuringRecoveryGrace() {
        when(config.microsleepEnabled()).thenReturn(true);
        when(config.microsleepMinFatigue()).thenReturn(80.0);
        when(config.microsleepChance()).thenReturn(1.0);
        when(server.getOnlinePlayers()).thenAnswer(ignored -> List.of(owner));
        fatigue.require(owner.getUniqueId()).addFatigue(95.0);
        pdc.values.put(MicrosleepService.GRACE_KEY, 60_000L);

        service.tick(10_000L);

        assertFalse(service.isActive(owner.getUniqueId()));
    }

    @Test
    void shutdownLeavesNoSpectatorAndNoEntities() {
        service.start(owner, 1_000L);
        clearInvocations(owner);
        service.shutdown();
        verify(owner).setGameMode(GameMode.SURVIVAL);
        verify(body).remove();
        verify(camera).remove();
        assertFalse(pdc.values.containsKey(MicrosleepService.SESSION_KEY));
        assertFalse(service.isActive(owner.getUniqueId()));
    }

    @Test
    void endingMicrosleepKeepsAHallucinationHideOfTheSleeperActive() {
        service.start(owner, 1_000L);
        verify(viewer).hidePlayer(any(JavaPlugin.class), eq(owner));
        visibility.hide(viewer, owner);               // viewer's own sight_loss starts meanwhile

        service.end(owner, true);
        verify(viewer, never()).showPlayer(any(JavaPlugin.class), eq(owner));

        visibility.show(viewer, owner);               // sight_loss ends
        verify(viewer).showPlayer(any(JavaPlugin.class), eq(owner));
    }

    @Test void disconnectWhileOfflineRetainsRecoveryAndGraceDoesNotRenewOnRepeatedJoin() {
        when(config.microsleepRecoveryGraceMillis()).thenReturn(30_000L);
        service.start(owner, 1_000L);
        when(owner.isOnline()).thenReturn(false);
        service.disconnect(owner);
        assertTrue(pdc.values.containsKey(MicrosleepService.SESSION_KEY));
        assertFalse(service.isActive(owner.getUniqueId()));
        verify(body).remove(); verify(camera).remove();
        when(owner.isOnline()).thenReturn(true);
        service.restoreAfterCrash(owner);
        long deadline = (Long) pdc.values.get(MicrosleepService.GRACE_KEY);
        assertFalse(service.restoreAfterCrash(owner));
        assertEquals(deadline,pdc.values.get(MicrosleepService.GRACE_KEY));
        assertFalse(service.start(owner, deadline - 1));
        assertEquals(1_000L,fatigue.require(owner.getUniqueId()).lastMicrosleepEpochMillis());
    }

    @Test void cancelledReturnRetainsSnapshotAndRetryDoesNotRenewGrace() {
        when(config.microsleepRecoveryGraceMillis()).thenReturn(30_000L);
        service.start(owner, 1_000L);
        when(owner.teleport(any(Location.class))).thenReturn(false);
        service.end(owner,true);
        assertTrue(pdc.values.containsKey(MicrosleepService.SESSION_KEY));
        service.restoreAfterCrash(owner);
        long deadline = (Long) pdc.values.get(MicrosleepService.GRACE_KEY);
        service.restoreAfterCrash(owner);
        assertEquals(deadline,pdc.values.get(MicrosleepService.GRACE_KEY));
        when(owner.teleport(any(Location.class))).thenReturn(true);
        service.restoreAfterCrash(owner);
        assertFalse(pdc.values.containsKey(MicrosleepService.SESSION_KEY));
    }

    @Test void missingWorldRetainsSnapshotWithoutLeavingSpectatorOrLoadingWorld() {
        service.start(owner, 1_000L);
        when(server.getWorld(worldId)).thenReturn(null);
        service.end(owner,true);
        assertTrue(pdc.values.containsKey(MicrosleepService.SESSION_KEY));
        verify(owner).setGameMode(GameMode.SURVIVAL);
        verify(owner,never()).teleport(any(Location.class));
    }

    @Test void nonFiniteSnapshotIsRejected() {
        var snapshot = new MicrosleepService.Snapshot(GameMode.SURVIVAL,false,false,worldId,1,64,1,0,0,UUID.randomUUID(),UUID.randomUUID());
        assertNull(MicrosleepService.Snapshot.decode(snapshot.encode().replace(";64.0;",";NaN;")));
    }

    private static PlayerArmorStandManipulateEvent manipulate(ArmorStand stand) {
        PlayerArmorStandManipulateEvent event = mock(PlayerArmorStandManipulateEvent.class);
        when(event.getRightClicked()).thenReturn(stand);
        return event;
    }
}
