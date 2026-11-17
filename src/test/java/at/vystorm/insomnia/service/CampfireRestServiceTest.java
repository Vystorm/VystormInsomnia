package at.vystorm.insomnia.service;

import at.vystorm.insomnia.config.InsomniaConfig;
import at.vystorm.insomnia.model.PlayerFatigueState;
import at.vystorm.insomnia.testutil.TestServer;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Campfire;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class CampfireRestServiceTest {
    private InsomniaConfig config;
    private SleepService sleep;
    private ActivityService activity;
    private FatigueService fatigue;
    private CampfireRestService rest;
    private Player player;
    private PlayerFatigueState state;
    private World world;
    private Campfire fire;
    private Location location;

    @BeforeEach
    void setUp() {
        Server server = TestServer.fresh();
        when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
        config = mock(InsomniaConfig.class);
        when(config.stageThresholds()).thenReturn(List.of(20.0, 40.0, 60.0, 80.0));
        when(config.campfireRestEnabled()).thenReturn(true);
        when(config.campfireRadius()).thenReturn(3);
        when(config.campfireSettleMillis()).thenReturn(5_000L);
        when(config.campfireFatiguePerSecond()).thenReturn(0.2);
        when(config.campfireMinFatigue()).thenReturn(30.0);
        when(config.message(any(), anyString(), any(Object[].class))).thenReturn("msg");
        sleep = mock(SleepService.class);
        activity = mock(ActivityService.class);
        PersistenceService persistence = mock(PersistenceService.class);
        when(persistence.synchronous()).thenReturn(true);

        UUID id = UUID.randomUUID();
        state = new PlayerFatigueState(id, 0L);
        state.fatigue(50.0);
        when(persistence.load(any(UUID.class), anyLong())).thenReturn(state);

        world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        Block air = mock(Block.class);
        when(air.getType()).thenReturn(Material.AIR);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(air);
        fire = mock(Campfire.class);
        when(fire.isLit()).thenReturn(true);
        Block campfire = mock(Block.class);
        when(campfire.getType()).thenReturn(Material.CAMPFIRE);
        when(campfire.getBlockData()).thenReturn(fire);
        when(world.getBlockAt(2, 64, 1)).thenReturn(campfire);

        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        location = new Location(world, 0.5, 64.0, 0.5);
        when(player.getLocation()).thenAnswer(ignored -> location.clone());
        when(server.getOnlinePlayers()).thenAnswer(ignored -> List.of(player));

        fatigue = new FatigueService(config, persistence, activity);
        fatigue.load(player, 0L);
        rest = new CampfireRestService(config, fatigue, sleep, activity, Vystorm.vystorm_core.compat.PlatformCompatInterface.DEFAULT);
    }

    @Test
    void standingStillByALitCampfireLowersFatigueSlowly() {
        rest.tick(0L);
        rest.tick(5_000L);
        assertTrue(rest.resting(player.getUniqueId()));
        verify(config).message(eq(player), eq("rest.started"), any(Object[].class));
        assertEquals(50.0, state.fatigue(), 1e-9, "nothing is paid out on the start tick");
        rest.tick(6_000L);
        rest.tick(7_000L);
        assertEquals(49.6, state.fatigue(), 1e-9);
        assertEquals(0.0, state.sleepDebt(), 1e-9);
        assertEquals(0L, state.restedUntilEpochMillis(), "no rested bonus from a campfire");
        assertTrue(fatigue.recentHistory(player.getUniqueId()).stream().anyMatch(e -> e.marker().equals("rest_started")));
    }

    @Test
    void fatigueStopsAtTheFloorWithOneMessage() {
        state.fatigue(30.3);
        rest.tick(0L);
        rest.tick(5_000L);
        rest.tick(6_000L);
        rest.tick(7_000L);
        rest.tick(8_000L);
        assertEquals(30.0, state.fatigue(), 1e-9);
        assertFalse(rest.resting(player.getUniqueId()));
        verify(config, times(1)).message(eq(player), eq("rest.floor"), any(Object[].class));
    }

    @Test
    void stimulantSuppressionIsKeptWhileResting() {
        state.stimulantSuppression(10.0); // effective 40 of raw 50
        rest.tick(0L);
        rest.tick(5_000L);
        rest.tick(6_000L);
        assertEquals(39.8, state.fatigue(), 1e-9);
        assertEquals(10.0, state.stimulantSuppression(), 1e-9);
    }

    @Test
    void noRestInCreativeInCombatWhileSleepingOrByAnUnlitFire() {
        when(player.getGameMode()).thenReturn(GameMode.CREATIVE);
        rest.tick(0L);
        rest.tick(5_000L);
        assertFalse(rest.resting(player.getUniqueId()));

        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(activity.inCombat(eq(player.getUniqueId()), anyLong())).thenReturn(true);
        rest.tick(6_000L);
        assertFalse(rest.resting(player.getUniqueId()));

        when(activity.inCombat(eq(player.getUniqueId()), anyLong())).thenReturn(false);
        when(sleep.isSleeping(player.getUniqueId())).thenReturn(true);
        rest.tick(7_000L);
        assertFalse(rest.resting(player.getUniqueId()));

        when(sleep.isSleeping(player.getUniqueId())).thenReturn(false);
        when(fire.isLit()).thenReturn(false);
        rest.tick(8_000L);
        assertFalse(rest.resting(player.getUniqueId()));
        assertEquals(50.0, state.fatigue(), 1e-9);
    }

    @Test
    void microsleepBlocksTheRest() {
        rest.busyWhen(player.getUniqueId()::equals);
        rest.tick(0L);
        rest.tick(5_000L);
        assertFalse(rest.resting(player.getUniqueId()));
    }

    @Test
    void walkingAwayEndsTheRestAndSwitchingOffClearsIt() {
        rest.tick(0L);
        rest.tick(5_000L);
        location = new Location(world, 3.5, 64.0, 0.5);
        rest.tick(6_000L);
        assertFalse(rest.resting(player.getUniqueId()));

        rest.tick(7_000L);
        rest.tick(12_000L);
        assertTrue(rest.resting(player.getUniqueId()), "the fire is still within three blocks");
        when(config.campfireRestEnabled()).thenReturn(false);
        rest.tick(13_000L);
        assertFalse(rest.resting(player.getUniqueId()));
    }

    @Test
    void aTickThatComesAFewMillisecondsEarlyStillRuns() {
        when(config.campfireSettleMillis()).thenReturn(0L);
        rest.tick(0L);
        rest.tick(997L);
        assertTrue(rest.resting(player.getUniqueId()));
    }

    @Test
    void onlyLitCampfiresCount() {
        assertTrue(CampfireRestService.litCampfire(Material.CAMPFIRE, fire));
        assertTrue(CampfireRestService.litCampfire(Material.SOUL_CAMPFIRE, fire));
        assertFalse(CampfireRestService.litCampfire(Material.FURNACE, fire));
        assertFalse(CampfireRestService.litCampfire(Material.CAMPFIRE, mock(BlockData.class)));
        when(fire.isLit()).thenReturn(false);
        assertFalse(CampfireRestService.litCampfire(Material.CAMPFIRE, fire));
    }

    @Test
    void campfireSearchRespectsTheRadius() {
        assertTrue(CampfireRestService.campfireNear(new Location(world, 0.5, 64, 0.5), 2));
        assertFalse(CampfireRestService.campfireNear(new Location(world, 0.5, 64, 0.5), 1));
        assertFalse(CampfireRestService.campfireNear(new Location(world, 0.5, 68, 0.5), 3), "too far below the feet");
    }
}
