package at.vystorm.insomnia.listener;

import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage;
import at.vystorm.insomnia.config.InsomniaConfig;
import at.vystorm.insomnia.event.InsomniaStageChangeEvent;
import at.vystorm.insomnia.service.ActivityService;
import at.vystorm.insomnia.service.FatigueService;
import at.vystorm.insomnia.service.HallucinationService;
import at.vystorm.insomnia.service.InsomniaMobService;
import at.vystorm.insomnia.service.MicrosleepService;
import at.vystorm.insomnia.service.RestedBonusService;
import at.vystorm.insomnia.service.SleepService;
import at.vystorm.insomnia.service.StimulantService;
import at.vystorm.insomnia.service.VisibilityLocks;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.Location;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerBedLeaveEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.event.player.PlayerToggleSprintEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.TimeSkipEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.projectiles.ProjectileSource;

public final class GameplayListener implements Listener {
    private final InsomniaConfig config;
    private final JavaPlugin plugin;
    private final Vystorm.vystorm_core.API api;
    private final FatigueService fatigue;
    private final ActivityService activity;
    private final SleepService sleep;
    private final StimulantService stimulants;
    private final HallucinationService hallucinations;
    private final MicrosleepService microsleep;
    private final InsomniaMobService mobs;
    private final RestedBonusService rested;
    private final VisibilityLocks visibility;

    public GameplayListener(JavaPlugin plugin, Vystorm.vystorm_core.API api, InsomniaConfig config, FatigueService fatigue, ActivityService activity,
                            SleepService sleep, StimulantService stimulants,
                            HallucinationService hallucinations, MicrosleepService microsleep,
                            InsomniaMobService mobs, RestedBonusService rested, VisibilityLocks visibility) {
        this.visibility = visibility;
        this.plugin = plugin;
        this.api = api;
        this.config = config;
        this.fatigue = fatigue;
        this.activity = activity;
        this.sleep = sleep;
        this.stimulants = stimulants;
        this.hallucinations = hallucinations;
        this.microsleep = microsleep;
        this.mobs = mobs;
        this.rested = rested;
    }

    /** Runs first so other plugins never see a player stuck in spectator by an interrupted microsleep. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoinRestore(PlayerJoinEvent event) {
        microsleep.restoreAfterCrash(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        long now = System.currentTimeMillis();
        fatigue.load(event.getPlayer(), now);
        activity.join(event.getPlayer(), now);
        mobs.hideFrom(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        microsleep.disconnect(player);
        sleep.stop(player, false);
        mobs.clearOwner(player.getUniqueId());
        hallucinations.restoreVisibility(player.getUniqueId());
        visibility.forget(player, org.bukkit.Bukkit.getOnlinePlayers());
        activity.quit(player.getUniqueId());
        fatigue.unload(player);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSprint(PlayerToggleSprintEvent event) {
        activity.sprint(event.getPlayer().getUniqueId(), event.isSprinting());
        activity.input(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        activity.swimming(player.getUniqueId(), player.isSwimming());
        if (!(event instanceof PlayerTeleportEvent) && event.getTo() != null
                && meaningfulMove(event.getFrom(), event.getTo())) {
            activity.input(player.getUniqueId(), System.currentTimeMillis());
        }
        if (microsleep.isActive(player.getUniqueId())) microsleep.enforce(player);
    }

    /** A block-crossing movement or a deliberate look change of at least five degrees counts as input. */
    public static boolean meaningfulMove(Location from, Location to) {
        if (from.getBlockX() != to.getBlockX() || from.getBlockY() != to.getBlockY() || from.getBlockZ() != to.getBlockZ()) return true;
        return angleDistance(from.getYaw(), to.getYaw()) >= 5.0f || Math.abs(from.getPitch() - to.getPitch()) >= 5.0f;
    }

    private static float angleDistance(float first, float second) {
        float difference = Math.abs(first - second) % 360.0f;
        return Math.min(difference, 360.0f - difference);
    }

    /**
     * The events several Vystorm plugins handle go through Core's stages, so Core decides when these run.
     * PROTECT: hallucination mobs and their owner only hurt each other, and their explosions break no blocks.
     * ADJUST: no spectator teleport away from the own body during a microsleep. FINISH: damage to the sleeping
     * body is passed on with its final amount; hallucination mobs drop only their own loot whatever was added.
     * OBSERVE: activity, combat, and ending sleep or a microsleep after a death or a teleport that really happened.
     * Handlers that received cancelled events before still do.
     */
    public void handleSharedEvents(Vystorm.vystorm_core._coreMethods.eventRegulator.EventStages stages) {
        stages.handle(plugin, PlayerInteractEvent.class, EventStage.OBSERVE, this::onInteract);
        stages.handle(plugin, PlayerInteractEntityEvent.class, EventStage.OBSERVE, this::onInteractEntity);
        stages.handle(plugin, EntityDamageByEntityEvent.class, EventStage.PROTECT, this::onMobDamage);
        stages.handle(plugin, EntityDamageEvent.class, EventStage.FINISH, this::onAnyDamage);
        stages.handle(plugin, EntityDamageEvent.class, EventStage.OBSERVE, this::onReceivedDamageActivity);
        stages.handle(plugin, EntityDamageByEntityEvent.class, EventStage.OBSERVE, this::onCombat);
        stages.handle(plugin, PlayerDeathEvent.class, EventStage.OBSERVE, true, this::onPlayerDeath);
        stages.handle(plugin, EntityDeathEvent.class, EventStage.FINISH, true, this::onEntityDeath);
        stages.handle(plugin, EntityExplodeEvent.class, EventStage.PROTECT, true, this::onExplode);
        stages.handle(plugin, PlayerTeleportEvent.class, EventStage.ADJUST, this::onSpectateTeleport);
        stages.handle(plugin, PlayerTeleportEvent.class, EventStage.OBSERVE, this::onTeleported);
    }

    /** Stage OBSERVE, registered in {@link #handleSharedEvents}. */
    public void onInteract(PlayerInteractEvent event) { activity.input(event.getPlayer().getUniqueId(), System.currentTimeMillis()); }

    /** Stage OBSERVE, registered in {@link #handleSharedEvents}. */
    public void onInteractEntity(PlayerInteractEntityEvent event) { activity.input(event.getPlayer().getUniqueId(), System.currentTimeMillis()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        java.util.UUID player = event.getPlayer().getUniqueId();
        org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
            if (org.bukkit.Bukkit.getPlayer(player) != null) activity.input(player, System.currentTimeMillis());
        });
    }

    /** Stage OBSERVE, registered in {@link #handleSharedEvents}. */
    public void onReceivedDamageActivity(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) activity.input(player.getUniqueId(), System.currentTimeMillis());
    }

    /**
     * Block breaks go through Core's stages, so Core decides when they run among all Vystorm plugins. Mining only
     * counts as activity: OBSERVE, the final outcome, never a cancelled event.
     */
    public void handleBlockEvents(Vystorm.vystorm_core._coreMethods.eventRegulator.EventStages stages) {
        stages.handle(plugin, BlockBreakEvent.class, Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage.OBSERVE, this::onMine);
    }

    /** Stage OBSERVE, registered in {@link #handleBlockEvents}. */
    public void onMine(BlockBreakEvent event) {
        activity.mining(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    /** Stage FINISH, registered in {@link #handleSharedEvents}. */
    public void onAnyDamage(EntityDamageEvent event) {
        microsleep.handlePlaceholderDamage(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onArmorStandManipulate(PlayerArmorStandManipulateEvent event) {
        microsleep.handleManipulate(event);
    }

    /** Stage PROTECT, registered in {@link #handleSharedEvents}: the deny part of the former damage handler. */
    public void onMobDamage(EntityDamageByEntityEvent event) {
        mobs.handleDamage(event);
    }

    /** Stage OBSERVE, registered in {@link #handleSharedEvents}: combat counts only for a hit nobody cancelled. */
    public void onCombat(EntityDamageByEntityEvent event) {
        long now = System.currentTimeMillis();
        Player attacker = playerFromDamager(event.getDamager());
        Player victim = event.getEntity() instanceof Player player ? player : null;
        boolean boss = activity.isBoss(event.getDamager()) || activity.isBoss(event.getEntity());
        if (attacker != null) activity.combat(attacker.getUniqueId(), now, boss);
        if (victim != null) activity.combat(victim.getUniqueId(), now, boss);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBedEnter(PlayerBedEnterEvent event) {
        if (event.getBedEnterResult() != PlayerBedEnterEvent.BedEnterResult.OK) return;
        Player player = event.getPlayer();
        activity.input(player.getUniqueId(), System.currentTimeMillis());
        if (microsleep.isActive(player.getUniqueId())) microsleep.end(player, true);
        sleep.start(player, System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBedLeave(PlayerBedLeaveEvent event) {
        activity.input(event.getPlayer().getUniqueId(), System.currentTimeMillis());
        sleep.stop(event.getPlayer(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        activity.input(event.getPlayer().getUniqueId(), System.currentTimeMillis());
        stimulants.consume(event.getPlayer(), event.getItem(), System.currentTimeMillis());
    }

    /** Stage OBSERVE, also for a cancelled death; registered in {@link #handleSharedEvents}. */
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (microsleep.isActive(player.getUniqueId())) microsleep.end(player, false);
        sleep.stop(player, false);
        mobs.clearOwner(player.getUniqueId());
    }

    /** Stage FINISH, also for a cancelled death; registered in {@link #handleSharedEvents}. */
    public void onEntityDeath(EntityDeathEvent event) {
        mobs.handleDeath(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        mobs.handlePickup(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHopperPickup(InventoryPickupItemEvent event) {
        mobs.handleInventoryPickup(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent event) {
        mobs.handleTarget(event);
    }

    /** Stage PROTECT, also for a cancelled explosion; registered in {@link #handleSharedEvents}. */
    public void onExplode(EntityExplodeEvent event) {
        mobs.handleExplosion(event);
    }

    /** Stage ADJUST, registered in {@link #handleSharedEvents}: the rule part of the former teleport handler. */
    public void onSpectateTeleport(PlayerTeleportEvent event) {
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.SPECTATE) microsleep.handleTeleport(event);
    }

    /** Stage OBSERVE, registered in {@link #handleSharedEvents}: a teleport that really happens ends the microsleep. */
    public void onTeleported(PlayerTeleportEvent event) {
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.SPECTATE) microsleep.handleTeleport(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        mobs.clearOwner(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player && microsleep.isActive(player.getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSneak(PlayerToggleSneakEvent event) {
        if (microsleep.isActive(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    /** The startup scan only sees loaded chunks; leftovers in chunks loaded later are removed here. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (microsleep.isOrphan(entity) || mobs.isOrphan(entity)) entity.remove();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onTimeSkip(TimeSkipEvent event) {
        if (config.cancelVanillaNightSkip() && at.vystorm.insomnia.sleep.SkipReasons.isNightSkip(event)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onXp(PlayerExpChangeEvent event) {
        rested.applyXp(event, System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFood(FoodLevelChangeEvent event) {
        rested.applyHunger(event, System.currentTimeMillis());
    }

    /** Client-mod toast for a stage change; {@code true} = shown, the chat message is skipped. */
    @FunctionalInterface
    public interface StageToasts { boolean show(Player player, at.vystorm.insomnia.api.InsomniaStage stage, String messageKey); }

    private StageToasts stageToasts;

    public void stageToasts(StageToasts toasts) { this.stageToasts = toasts; }

    @EventHandler
    public void onStageChange(InsomniaStageChangeEvent event) {
        String key = switch (event.current()) {
            case TIRED -> "stage_tired";
            case EXHAUSTED -> "stage_exhausted";
            case OVERMUEDET -> "stage_overmuedet";
            case SLEEP_DEPRIVATION -> "stage_sleep_deprivation";
            case RESTED -> null;
        };
        if (key == null) return;
        if (stageToasts != null && stageToasts.show(event.player(), event.current(), key)) return;
        String label = config.messages() == null ? "Müdigkeit" : config.messages().ref("menu.chat_button");
        at.vystorm.insomnia.ui.ChatLinks.withMenu(api, event.player(), config.message(event.player(), key), label);
    }

    private static Player playerFromDamager(Entity entity) {
        if (entity instanceof Player player) return player;
        if (entity instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player player) return player;
        }
        return null;
    }
}
