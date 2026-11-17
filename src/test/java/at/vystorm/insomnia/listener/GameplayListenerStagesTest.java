package at.vystorm.insomnia.listener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage;
import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStageTesting;
import at.vystorm.insomnia.config.InsomniaConfig;
import at.vystorm.insomnia.service.ActivityService;
import at.vystorm.insomnia.service.FatigueService;
import at.vystorm.insomnia.service.HallucinationService;
import at.vystorm.insomnia.service.InsomniaMobService;
import at.vystorm.insomnia.service.MicrosleepService;
import at.vystorm.insomnia.service.RestedBonusService;
import at.vystorm.insomnia.service.SleepService;
import at.vystorm.insomnia.service.StimulantService;
import at.vystorm.insomnia.service.VisibilityLocks;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The gameplay handlers of the shared events, called by Vystorm Core's real stage dispatcher. */
class GameplayListenerStagesTest {
    private final Plugin other = EventStageTesting.plugin("AnotherPlugin");
    private final ActivityService activity = mock(ActivityService.class);
    private final MicrosleepService microsleep = mock(MicrosleepService.class);
    private final InsomniaMobService mobs = mock(InsomniaMobService.class);
    private final SleepService sleep = mock(SleepService.class);
    private final UUID id = UUID.randomUUID();
    private final Player player = mock(Player.class);
    private final List<String> calls = new ArrayList<>();
    private GameplayListener listener;

    @BeforeEach
    void setUp() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getName()).thenReturn("VystormInsomnia");
        when(plugin.isEnabled()).thenReturn(true);
        listener = new GameplayListener(plugin, mock(API.class), mock(InsomniaConfig.class), mock(FatigueService.class),
                activity, sleep, mock(StimulantService.class), mock(HallucinationService.class),
                microsleep, mobs, mock(RestedBonusService.class), mock(VisibilityLocks.class));
        when(player.getUniqueId()).thenReturn(id);
    }

    private EventStageTesting shared() {
        EventStageTesting stages = EventStageTesting.detached();
        listener.handleSharedEvents(stages.stages());
        return stages;
    }

    /** A hit of a zombie on the player whose cancelled state behaves like the real event's. */
    private EntityDamageByEntityEvent hit() {
        EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
        boolean[] cancelled = {false};
        doAnswer(call -> { cancelled[0] = call.getArgument(0); return null; }).when(event).setCancelled(anyBoolean());
        when(event.isCancelled()).thenAnswer(call -> cancelled[0]);
        when(event.getEntity()).thenReturn(player);
        when(event.getDamager()).thenReturn(mock(Zombie.class));
        return event;
    }

    @Test
    void givenBlockBreak_whenAnotherPluginDeniesIt_thenItDoesNotCountAsMining() {
        boolean[] deny = {true};
        EventStageTesting harness = EventStageTesting.detached();
        listener.handleBlockEvents(harness.stages());
        harness.handle(EventStageTesting.plugin("AProtectionPlugin"), BlockBreakEvent.class, EventStage.PROTECT,
                event -> event.setCancelled(deny[0]));

        assertTrue(harness.fire(new BlockBreakEvent(mock(Block.class), player)).isCancelled());
        verify(activity, never()).mining(eq(id), anyLong());

        deny[0] = false;
        harness.fire(new BlockBreakEvent(mock(Block.class), player));
        verify(activity).mining(eq(id), anyLong());
    }

    @Test
    void givenHallucinationMob_whenItsHitIsDenied_thenNoLaterHandlerOfTheHitRuns() {
        EventStageTesting stages = shared();
        stages.handle(other, EntityDamageEvent.class, EventStage.ACT, event -> calls.add("act"));
        EntityDamageByEntityEvent hit = hit();
        when(mobs.handleDamage(hit)).thenAnswer(call -> { hit.setCancelled(true); return true; });

        assertTrue(stages.fire(hit).isCancelled());

        verify(microsleep, never()).handlePlaceholderDamage(any());
        verify(activity, never()).input(any(), anyLong());
        verify(activity, never()).combat(any(), anyLong(), anyBoolean());
        assertEquals(List.of(), calls);
    }

    @Test
    void givenAllowedHit_thenItCountsAsActivityAndCombatAfterEveryOtherStage() {
        EventStageTesting stages = shared();
        stages.handle(other, EntityDamageEvent.class, EventStage.ACT, event -> {
            verify(activity, never()).combat(any(), anyLong(), anyBoolean());
            calls.add("act");
        });
        EntityDamageByEntityEvent hit = hit();

        assertFalse(stages.fire(hit).isCancelled());

        assertEquals(List.of("act"), calls);
        verify(mobs).handleDamage(hit);
        verify(microsleep).handlePlaceholderDamage(hit);
        verify(activity).input(eq(id), anyLong());
        verify(activity).combat(eq(id), anyLong(), eq(false));
    }

    @Test
    void givenHitThatALaterPluginCancels_thenItIsNoCombat() {
        EventStageTesting stages = shared();
        stages.handle(other, EntityDamageEvent.class, EventStage.FINISH, event -> event.setCancelled(true));

        stages.fire(hit());

        verify(activity, never()).combat(any(), anyLong(), anyBoolean());
        verify(activity, never()).input(any(), anyLong());
    }

    @Test
    void givenMicrosleep_whenATeleportIsDenied_thenItDoesNotEndTheMicrosleep() {
        boolean[] deny = {true};
        EventStageTesting stages = shared();
        stages.handle(other, PlayerTeleportEvent.class, EventStage.PROTECT, event -> event.setCancelled(deny[0]));
        Location from = new Location(null, 0, 64, 0), to = new Location(null, 50, 64, 0);

        stages.fire(new PlayerTeleportEvent(player, from, to, TeleportCause.COMMAND));
        verify(microsleep, never()).handleTeleport(any());

        deny[0] = false;
        PlayerTeleportEvent allowed = stages.fire(new PlayerTeleportEvent(player, from, to, TeleportCause.COMMAND));
        verify(microsleep).handleTeleport(allowed);
    }

    @Test
    void givenMicrosleep_whenTheSpectatorTeleportRuleRefuses_thenNothingObservesATeleport() {
        EventStageTesting stages = shared();
        stages.handle(other, PlayerTeleportEvent.class, EventStage.OBSERVE, event -> calls.add("observe"));
        PlayerTeleportEvent spectate = new PlayerTeleportEvent(player, new Location(null, 0, 64, 0), new Location(null, 50, 64, 0), TeleportCause.SPECTATE);
        doAnswer(call -> { spectate.setCancelled(true); return null; }).when(microsleep).handleTeleport(spectate);

        assertTrue(stages.fire(spectate).isCancelled());

        verify(microsleep).handleTeleport(spectate);
        assertEquals(List.of(), calls);
    }

    @Test
    void givenCancelledDeaths_thenTheHandlersStillRunAsBefore() {
        EventStageTesting stages = shared();
        PlayerDeathEvent death = mock(PlayerDeathEvent.class);
        when(death.getEntity()).thenReturn(player);
        when(death.isCancelled()).thenReturn(true);
        EntityDeathEvent mobDeath = mock(EntityDeathEvent.class);
        when(mobDeath.isCancelled()).thenReturn(true);

        stages.fire(death);
        stages.fire(mobDeath);

        verify(sleep).stop(player, false);
        verify(mobs).clearOwner(id);
        verify(mobs).handleDeath(mobDeath);
        // A player's death is an EntityDeathEvent on the same handler list, as with a Bukkit listener.
        verify(mobs).handleDeath(death);
    }
}
