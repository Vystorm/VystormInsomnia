package at.vystorm.insomnia.dream;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage;
import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStages;
import Vystorm.vystorm_core.item.RewardPouch;
import Vystorm.vystorm_core.region.RegionService;
import Vystorm.vystorm_core.worlds.*;
import at.vystorm.insomnia.config.InsomniaConfig;
import at.vystorm.insomnia.dream.world.DreamLayout;
import at.vystorm.insomnia.dream.world.DreamWorldGenerator;
import at.vystorm.insomnia.service.ActivityService;
import at.vystorm.insomnia.service.FatigueService;
import at.vystorm.insomnia.service.SleepService;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Bed;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded Insomnia content and visit lifecycle for the Core-managed Traumweite. Server-thread only. */
public final class DreamService implements Listener {
    private final Plugin plugin;
    private final API api;
    private final InsomniaConfig config;
    private final FatigueService fatigue;
    private final ActivityService activity;
    private final SleepService sleep;
    private final String worldId;
    private final Map<UUID, Nap> naps = new HashMap<>();
    private final Map<UUID, PendingEntry> pendingEntries = new HashMap<>();
    private final Map<UUID, DreamVisit> active = new HashMap<>();
    private final Map<String, PendingReward> pendingRewards = new LinkedHashMap<>();
    private final Set<String> warnedClaims = new HashSet<>();
    private final NamespacedKey mobKey;
    private final NamespacedKey ownerKey;
    private final NamespacedKey visitKey;
    private final DreamWorldGenerator generator;
    private ManagedWorld world;
    private RewardPouch pouch;
    private DreamQuestStore quests;
    private long nextRewardRetry;
    private boolean enabled;
    private boolean spawningWave;
    private final DreamCompanions companions = new DreamCompanions();
    /** Weekly nightmare challenge board ({@code data/weekly-nightmare.yml}); null while the challenge is off. */
    private WeeklyNightmare.Board weekly;

    public DreamService(Plugin plugin, API api, InsomniaConfig config, FatigueService fatigue, ActivityService activity, SleepService sleep) {
        this.plugin = plugin; this.api = api; this.config = config; this.fatigue = fatigue; this.activity = activity; this.sleep = sleep;
        this.worldId = config.dreamWorldId();
        mobKey = new NamespacedKey(plugin, "dream_mob"); ownerKey = new NamespacedKey(plugin, "dream_owner");
        visitKey = new NamespacedKey(plugin, "dream_visit");
        generator = new DreamWorldGenerator(config.dreamWorldSeed(), config.dreamDecorationDensity(), config.dreamAmbientLight(), config.dreamSkyIslandDensity(), config.dreamNaturalAccentDensity(), config.dreamNaturalObjectDensity(), config.dreamDungeonsEnabled());
    }

    /** Opens durable storage before making the world available; on any failure the feature stays disabled. */
    public boolean enable() {
        try {
            pouch = api.loot().pouches().open(plugin, api, config.dreamPouchNamespace());
            quests = new DreamQuestStore(api.data().versioned().open(plugin, api,
                    "data", "dream-quests.json", DreamQuestStore.Data.class, 1, Map.of(), DreamQuestStore.Data::new));
        } catch (Exception failure) {
            plugin.getLogger().severe("Dream rewards disabled safely: " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
            pouch = null;
            return false;
        }
        if (!config.dreamEnabled()) return false;
        loadWeekly();
        try {
            WorldSpec spec = worldSpec();
            world = api.worldGenerator().worlds().register(plugin, spec);
            if (world == null || world.world() == null) throw new IllegalStateException("Core did not load the managed dream world");
            // Six bounded arrival chunks stay available through the short transfer delay.
            // Holding only these plugin-owned tickets prevents async loads expiring before the next master tick.
            for (DreamLayout.Tower tower : generator.layout().towers())
                world.world().addPluginChunkTicket(tower.arrival().x() >> 4, tower.arrival().z() >> 4, plugin);
            placePuzzleMarkers(world.world(), generator.layout());
            // One startup-only pass repairs tagged encounter entities left in a saved chunk by an older run.
            for (Entity entity : world.world().getEntities()) if (isDreamMob(entity)) removeMob(entity);
            enabled = true;
            return true;
        } catch (Exception failure) {
            plugin.getLogger().severe("Dream Realm disabled safely: " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
            if (world != null && world.world() != null) world.world().removePluginChunkTickets(plugin);
            api.worldGenerator().worlds().release(plugin);
            world = null;
            return false;
        }
    }

    private WorldSpec worldSpec() {
        VisitPolicy policy = VisitPolicy.builder().gameMode(GameMode.ADVENTURE).invulnerable(false)
                .lockInventory(true).fullFood(true).cooldownSeconds(config.dreamCooldownSeconds())
                .isolateExperience(true).allowCommands("dream", "wake", "dreamloot")
                .blockRegionKinds(config.dreamBlockedRegionKinds())
                .toolKit(player -> Map.of(0, exitTool(player))).returnTo(this::safeReturn).build();
        return WorldSpec.builder(worldId, config.dreamWorldName(), generator)
                .layoutHash(generator.layout().layoutHash())
                .environment(World.Environment.NORMAL).autoSave(false).lockWeather(true).time(6000)
                .gameRule(api.compat().gameplay().booleanGameRule("DO_DAYLIGHT_CYCLE", "ADVANCE_TIME"), false).gameRule(api.compat().gameplay().booleanGameRule("DO_MOB_SPAWNING", "SPAWN_MOBS"), false)
                .gameRule(GameRule.MOB_GRIEFING, false).difficulty(Difficulty.NORMAL).borderSize(2 * Math.max(Math.abs(generator.layout().bounds().minX()), Math.abs(generator.layout().bounds().maxX())) + 32)
                .spawn(new SpawnPoint(generator.layout().safeSpawn().x() + .5, generator.layout().safeSpawn().y(), generator.layout().safeSpawn().z() + .5, 0, 0))
                .region(RegionSpec.square("dream", "insomnia_dream", 100, Math.max(Math.abs(generator.layout().bounds().minX()), Math.abs(generator.layout().bounds().maxX())) + 16, Set.of("insomnia", "dream"), Map.of("safe", true)))
                .protect(Protection.EDITS, Protection.WEATHER, Protection.TIME_SKIP, Protection.HUNGER, Protection.PORTALS,
                        Protection.ENTITY_INTERACT, Protection.ITEM_PICKUP)
                .visibility(Visibility.NONE).policy(policy).hooks(new DreamHooks()).build();
    }

    private Location safeReturn(Player player, Location origin) {
        if (origin == null || origin.getWorld() == null) return null;
        DreamVisit visit = active.get(player.getUniqueId());
        PendingEntry pending = pendingEntries.get(player.getUniqueId());
        Location selectedBed = visit != null ? visit.bed : pending == null ? null : pending.bed;
        if (safeFeet(origin)) return origin;
        if (selectedBed != null && safeBed(selectedBed)) {
            Location base = selectedBed.clone().subtract(0, 1, 0);
            for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
                Location candidate = base.clone().add(x, 0, z);
                if (safeFeet(candidate)) return candidate;
            }
        }
        return origin.getWorld().getSpawnLocation();
    }

    private boolean safeBed(Location feet) {
        return feet != null && feet.getWorld() != null && safeDayBed(feet.clone().subtract(0, 1, 0).getBlock());
    }

    private boolean safeFeet(Location feet) {
        if (feet == null || feet.getWorld() == null) return false;
        Block at = feet.getBlock();
        return at.isPassable() && at.getRelative(0, 1, 0).isPassable() && at.getRelative(0, -1, 0).getType().isSolid();
    }

    private ItemStack exitTool(Player player) {
        ItemStack sword = new ItemStack(Material.IRON_SWORD);
        var meta = sword.getItemMeta();
        if (meta != null) {
            api.compat().platform().displayName(meta, net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection()
                    .deserialize(config.messages().text(player, "dream.exit_tool")));
            sword.setItemMeta(meta);
        }
        return sword;
    }

    public boolean enabled() { return enabled; }
    /** A daytime nap (bed allowed by Insomnia although vanilla says "not possible now"). */
    public boolean napping(UUID id) { return naps.containsKey(id); }
    public boolean inDream(UUID id) { return active.containsKey(id) || api.worldGenerator().worlds().visits().inVisit(id) && api.worldGenerator().worlds().visits().visit(id).map(v -> worldId.equals(v.worldId())).orElse(false); }

    /** Entry command; Core remains the authority for regions, recovery state, cooldown and durable admission. */
    public void enter(Player player) {
        if(active.containsKey(player.getUniqueId())) { showDungeons(player); return; }
        stopNap(player);
        if (!enabled) { tell(player, config.dreamEnabled() ? "dream.disabled" : at.vystorm.insomnia.command.InsomniaCommand.OFF); return; }
        if (!fatigue.ready(player.getUniqueId())) { tell(player, "data_loading"); return; }
        // Fatigue off: it never rises, so a player below the entry fatigue could never dream (0.31.1).
        if (!config.fatigueEnabled() && fatigue.fatigue(player.getUniqueId()) < config.dreamMinimumFatigue()) { needs(player, "fatigue.enabled"); return; }
        if (player.getGameMode() != GameMode.SURVIVAL) { tell(player, "dream.survival_only"); return; }
        if (activity.inCombat(player.getUniqueId(), System.currentTimeMillis())) { tell(player, "dream.in_combat"); return; }
        if (api.worldGenerator().worlds().visits().inVisit(player.getUniqueId()) || api.worldGenerator().worlds().visits().hasSnapshot(player.getUniqueId())) { tell(player, "dream.recovery_pending"); return; }
        if (fatigue.fatigue(player.getUniqueId()) < config.dreamMinimumFatigue()) { tell(player, "dream.not_tired", "fatigue", Math.round(fatigue.fatigue(player.getUniqueId()))); return; }
        String worldName = player.getWorld().getName();
        if (player.getWorld().getEnvironment() != World.Environment.NORMAL || api.worldGenerator().worlds().managed(player.getWorld())) { tell(player, "dream.world_denied"); return; }
        List<String> allowed = config.dreamAllowedWorlds();
        if (!allowed.isEmpty() && allowed.stream().noneMatch(s -> s.equalsIgnoreCase(worldName))) { tell(player, "dream.world_denied"); return; }
        if (player.getItemOnCursor() != null && !api.compat().gameplay().isEmpty(player.getItemOnCursor())) { tell(player, "dream.clear_cursor"); return; }
        if (player.getOpenInventory().getType() == org.bukkit.event.inventory.InventoryType.CRAFTING) {
            for (ItemStack item : player.getOpenInventory().getTopInventory().getContents()) if (item != null && !api.compat().gameplay().isEmpty(item)) { tell(player, "dream.clear_crafting"); return; }
        }
        Location bed = nearbySafeBed(player);
        if (bed == null) { tell(player, "dream.bed_required"); return; }
        final DreamLayout.BlockPos arrival = generator.layout().safeSpawn();
        World target = world == null ? null : world.world();
        if (target == null) { tell(player, "dream.disabled"); return; }
        double f = fatigue.fatigue(player.getUniqueId());
        long duration = durationMillis(f, config.dreamMinimumFatigue(), config.dreamMaxDurationFatigue(),
                config.dreamMinDurationMillis(), config.dreamMaxDurationMillis());
        try {
            if (!pouch.readyForVisit(player.getUniqueId())) { tell(player, "dream.pouch_recovery"); return; }
        }
        catch (RuntimeException failure) {
            plugin.getLogger().warning("Dream entry refused because reward storage is unavailable for " + player.getUniqueId() + ": " + failure.getMessage());
            tell(player, "dream.storage_unavailable"); return;
        }
        long now = System.currentTimeMillis();
        pendingEntries.put(player.getUniqueId(), new PendingEntry(now, duration, bed.clone(), sleep.qualityAt(player)));
        Location targetSpawn = new Location(target, arrival.x() + 0.5, arrival.y(), arrival.z() + 0.5);
        if (player.isSleeping()) {
            player.wakeup(false);
            plugin.getServer().getScheduler().runTask(plugin, () -> requestEnter(player, targetSpawn));
        } else requestEnter(player, targetSpawn);
    }

    private void requestEnter(Player player, Location targetSpawn) {
        if (!player.isOnline() || player.isDead() || !pendingEntries.containsKey(player.getUniqueId())) return;
        VisitResult result;
        try { result = api.worldGenerator().worlds().visits().enter(player, worldId, targetSpawn); }
        catch (RuntimeException failure) { pendingEntries.remove(player.getUniqueId()); plugin.getLogger().warning("Dream entry failed: " + failure.getMessage()); tell(player, "dream.entry_failed"); return; }
        if (!result.ok()) {
            pendingEntries.remove(player.getUniqueId());
            tell(player, "dream.entry_refused", "reason", result.name());
            return;
        }
        // Core enters asynchronously after the durable snapshot; hooks bind the visit on arrival.
    }

    private Location nearbySafeBed(Player player) {
        Location here = player.getLocation(); Block best = null; double distance = 10.0;
        for (int x = -3; x <= 3; x++) for (int y = -3; y <= 3; y++) for (int z = -3; z <= 3; z++) {
            Block candidate = here.getBlock().getRelative(x, y, z);
            if (!(candidate.getBlockData() instanceof Bed bedData) || bedData.getPart() != Bed.Part.FOOT) continue;
            if (!candidate.getRelative(0, -1, 0).getType().isSolid()) continue;
            double d = candidate.getLocation().distanceSquared(here);
            if (d <= 9.0 && d < distance) { best = candidate; distance = d; }
        }
        if (best == null) return null;
        Location feet = best.getLocation().add(0.5, 1, 0.5);
        return safeBed(feet) ? feet : null;
    }

    public void wake(Player player, ExitReason reason) {
        if (stopNap(player)) return;
        var visit = api.worldGenerator().worlds().visits().visit(player.getUniqueId()).orElse(null);
        if (visit == null || !worldId.equals(visit.worldId())) { tell(player, "dream.not_visiting"); return; }
        api.worldGenerator().worlds().visits().exit(player, reason);
    }

    public RewardPouch.ClaimResult claim(Player player) {
        if (pouch == null) { tell(player, "dream.disabled"); return RewardPouch.ClaimResult.BUSY; }
        try {
            creditQuestEnding(player);
            RewardPouch.ClaimResult result = pouch.claim(player);
            tell(player, switch (result) {
                case DELIVERED -> "dream.loot_delivered"; case EMPTY -> "dream.loot_empty";
                case FULL -> "dream.loot_full"; case BUSY -> "dream.loot_busy"; case RECOVERY_REQUIRED -> "dream.loot_recovery";
            });
            return result;
        } catch (RuntimeException failure) {
            plugin.getLogger().warning("Dream reward claim failed for " + player.getUniqueId() + ": " + failure.getMessage());
            tell(player, "dream.loot_recovery"); return RewardPouch.ClaimResult.RECOVERY_REQUIRED;
        }
    }

    public void tick(long now) {
        for (UUID id : List.copyOf(naps.keySet())) {
            Player player = Bukkit.getPlayer(id);
            if (player == null) { naps.remove(id); continue; }
            Nap nap = naps.get(id);
            if (player.isDead() || !player.getWorld().equals(nap.origin.getWorld()) || !safeBed(nap.bed)
                    || !player.isSleeping() || !config.daytimeBedSleepEnabled()) stopNap(player);
        }
        if (!pendingRewards.isEmpty() && now >= nextRewardRetry) {
            nextRewardRetry = now + 5_000L;
            for (PendingReward reward : List.copyOf(pendingRewards.values())) credit(reward);
        }
        if (!enabled) return;
        pendingEntries.entrySet().removeIf(entry -> {
            if (now - entry.getValue().requestedAt < 10_000L) return false;
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null) tell(player, "dream.entry_failed");
            return true;
        });
        for (var entry : active.entrySet().toArray(Map.Entry[]::new)) {
            UUID id = (UUID) entry.getKey(); DreamVisit visit = (DreamVisit) entry.getValue();
            Player player = Bukkit.getPlayer(id);
            if (player == null) continue; // Core's QUIT hook will close session.
            if (player.getLocation().getY() < DreamWorldGenerator.MIN_Y - 4) { wake(player, ExitReason.PLUGIN); continue; }
            long remaining = visit.deadline - now;
            if (remaining <= 0) { tell(player, "dream.waking"); wake(player, ExitReason.PLUGIN); continue; }
            for (int warning : config.dreamWarningSeconds()) {
                long boundary = warning * 1000L;
                if (remaining <= boundary && visit.warned.add(warning)) tell(player, "dream.time_warning", "seconds", warning);
            }
            if (now >= visit.nextHud) {
                visit.nextHud = now + 1_000L;
                updateTimeBar(player, visit, remaining);
            }
            TowerTransfer.Result transfer = visit.tower.tick(player, now, towerAllowed(visit, player, now), config.dreamTowerCooldownMillis());
            if (transfer == TowerTransfer.Result.ARRIVED) {
                tell(player, "dream.tower_arrived", "side", visit.tower.side() == 0 ? "A" : "B");
                if (config.dreamTowerEffects()) player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, .8f, .65f);
            } else if (transfer == TowerTransfer.Result.MISDIRECTED) {
                wake(player, ExitReason.PLUGIN); continue;
            } else if (transfer == TowerTransfer.Result.FAILED || transfer == TowerTransfer.Result.REFUSED) tell(player, "dream.tower_failed");
            if (visit.nightmareStarted && !visit.nightmareDone) advanceNightmare(visit, player, now);
        }
    }

    private void placePuzzleMarkers(World world, DreamLayout layout) {
        for (DreamLayout.Dungeon dungeon : layout.dungeons()) {
            for (int i = 0; i < dungeon.puzzleAnchors().size(); i++) {
                DreamLayout.BlockPos anchor = dungeon.puzzleAnchors().get(i);
                Material marker = switch (dungeon.type()) {
                    case CLOUD_RUINS -> i == 0 ? Material.MOSS_BLOCK : Material.GLOWSTONE;
                    case CRYSTAL_VAULT -> i == 0 ? Material.AMETHYST_BLOCK : Material.BUDDING_AMETHYST;
                    case NIGHTMARE_KEEP -> i == 0 ? Material.POLISHED_BLACKSTONE_BRICKS : Material.SOUL_SOIL;
                };
                world.getBlockAt(anchor.x(), anchor.y() - 1, anchor.z()).setType(marker, false);
            }
            if (dungeon.type() == DreamLayout.DungeonType.CLOUD_RUINS) {
                DreamLayout.BlockPos reward = dungeon.reward();
                world.getBlockAt(reward.x(), reward.y() - 1, reward.z()).setType(Material.GOLD_BLOCK, false);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (pouch != null) pouch.reconnected(event.getPlayer());
        for (var activeEntry : active.entrySet()) for (UUID mobId : activeEntry.getValue().mobs) {
            Entity mob = Bukkit.getEntity(mobId);
            if (mob != null && mob.isValid() && !activeEntry.getKey().equals(event.getPlayer().getUniqueId())) event.getPlayer().hideEntity(plugin, mob);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBedEnter(PlayerBedEnterEvent event) {
        if (!config.daytimeBedSleepEnabled() || event.useBed() == Event.Result.DENY || event.getBedEnterResult() != PlayerBedEnterEvent.BedEnterResult.NOT_POSSIBLE_NOW
                || event.getBed().getWorld().getEnvironment() != World.Environment.NORMAL) return;
        if (!fatigue.ready(event.getPlayer().getUniqueId()) || fatigue.fatigue(event.getPlayer().getUniqueId()) < config.dreamMinimumFatigue()) return;
        if (!safeDayBed(event.getBed())) return;
        try {
            if (!api.area().regions().flag(event.getBed().getLocation(), "interact", true)) return;
        } catch (RuntimeException unavailable) { return; }
        event.setUseBed(Event.Result.ALLOW);
        Player sleeper = event.getPlayer();
        UUID id = sleeper.getUniqueId();
        Bed data = (Bed) event.getBed().getBlockData();
        Block foot = data.getPart() == Bed.Part.FOOT ? event.getBed() : event.getBed().getRelative(data.getFacing().getOppositeFace());
        naps.put(id, new Nap(sleeper.getLocation().clone(), foot.getLocation().add(.5, 1, .5), sleeper.getPose(), api.compat().gameplay().hasFixedPose(sleeper),sleeper.isSleepingIgnored()));
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(id);
            if (player == null || !naps.containsKey(id)) return;
            if (event.useBed() == Event.Result.DENY || !player.isSleeping()) { stopNap(player); return; }
            player.setSleepingIgnored(true);
            sleep.start(player, System.currentTimeMillis());
            tell(player, "dream.napping");
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onNapLeave(PlayerBedLeaveEvent event) {
        if (!naps.containsKey(event.getPlayer().getUniqueId())) return;
        if (!event.getPlayer().isDead() && safeBed(naps.get(event.getPlayer().getUniqueId()).bed)
                && at.vystorm.insomnia.compat.paper26.BedLeaveOrigin.automaticDaylightWake()) {
            event.setCancelled(true);
            return;
        }
        Nap nap=naps.remove(event.getPlayer().getUniqueId());
        if(nap!=null)event.getPlayer().setSleepingIgnored(nap.sleepingIgnored);
        sleep.stop(event.getPlayer(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBedChoice(PlayerBedEnterEvent event) {
        if (enabled && !config.sleepDialogEnabled() && fatigue.ready(event.getPlayer().getUniqueId())
                && fatigue.fatigue(event.getPlayer().getUniqueId()) >= config.dreamMinimumFatigue()) tell(event.getPlayer(), "dream.choice");
    }

    private boolean safeDayBed(Block bedBlock) {
        if (!(bedBlock.getBlockData() instanceof Bed clicked)) return false;
        Block foot = clicked.getPart() == Bed.Part.FOOT ? bedBlock : bedBlock.getRelative(clicked.getFacing().getOppositeFace());
        if (!(foot.getBlockData() instanceof Bed footData) || footData.getPart() != Bed.Part.FOOT) return false;
        Block head = foot.getRelative(footData.getFacing());
        return head.getBlockData() instanceof Bed headData && headData.getPart() == Bed.Part.HEAD
                && foot.getRelative(0, -1, 0).getType().isSolid() && head.getRelative(0, -1, 0).getType().isSolid()
                && foot.getRelative(0, 1, 0).isPassable() && foot.getRelative(0, 2, 0).isPassable()
                && head.getRelative(0, 1, 0).isPassable() && head.getRelative(0, 2, 0).isPassable();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        DreamVisit visit = active.get(event.getPlayer().getUniqueId());
        if (visit == null || event.getTo() == null || !worldId.equals(visit.worldId)
                || world == null || !event.getTo().getWorld().equals(world.world())) return;
        for (DreamLayout.Dungeon dungeon : generator.layout().dungeons()) {
            if(near(event.getTo(),dungeon.entry(),6) && visit.announced.add(dungeon.id()))
                tell(event.getPlayer(),"dream.hint_"+dungeon.type().name().toLowerCase(Locale.ROOT));
            if (dungeon.side() != visit.tower.side() || dungeon.type() != DreamLayout.DungeonType.CLOUD_RUINS || visit.claimed.contains(dungeon.id())) continue;
            int progress = visit.progress.getOrDefault(dungeon.id(), 0);
            if (progress >= dungeon.puzzleAnchors().size()) continue;
            DreamLayout.BlockPos anchor = dungeon.puzzleAnchors().get(progress);
            if (near(event.getTo(), anchor, 2.2)) {
                visit.progress.put(dungeon.id(), progress + 1);
                tell(event.getPlayer(), "dream.cloud_waypoint", "step", progress + 1, "total", dungeon.puzzleAnchors().size());
                if (progress + 1 == dungeon.puzzleAnchors().size()) tell(event.getPlayer(), "dream.cloud_ready");
            }
        }
        startNightmareIfNear(visit, event.getPlayer(), event.getTo());
    }

    /**
     * The events several Vystorm plugins handle go through Core's stages, so Core decides when these run.
     * PROTECT: no PvP in the dream, dream mobs fight only their own party. ACT: the tower button and the dungeon
     * markers consume the click (also a click Bukkit delivers as cancelled, as before). FINISH: a hit that would
     * kill, judged by the final damage, wakes the dreamer instead; dream mobs leave no drops whatever was added.
     * OBSERVE: a nap ends on damage or a teleport that really happened; dying in the dream wakes.
     */
    public void handleSharedEvents(EventStages stages) {
        stages.handle(plugin, PlayerInteractEvent.class, EventStage.ACT, true, this::onInteract);
        stages.handle(plugin, EntityDamageByEntityEvent.class, EventStage.PROTECT, this::onEntityDamage);
        stages.handle(plugin, EntityDamageEvent.class, EventStage.FINISH, this::onDamage);
        stages.handle(plugin, EntityDamageEvent.class, EventStage.OBSERVE, this::onNapDamage);
        stages.handle(plugin, EntityDeathEvent.class, EventStage.FINISH, true, this::onMobDeath);
        stages.handle(plugin, PlayerDeathEvent.class, EventStage.OBSERVE, true, this::onDeath);
        stages.handle(plugin, PlayerTeleportEvent.class, EventStage.OBSERVE, this::onNapTeleport);
    }

    /** Stage ACT, also for cancelled clicks; registered in {@link #handleSharedEvents}. */
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer(); DreamVisit visit = active.get(player.getUniqueId());
        if (event.getHand() != EquipmentSlot.HAND || visit == null || event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null
                || world == null || !event.getClickedBlock().getWorld().equals(world.world())) return;
        Block clicked = event.getClickedBlock();
        for (DreamLayout.Tower tower : generator.layout().towers()) {
            DreamLayout.BlockPos button = tower.button();
            if (clicked.getX() != button.x() || clicked.getY() != button.y() || clicked.getZ() != button.z()) continue;
            event.setCancelled(true);
            long now = System.currentTimeMillis();
            TowerTransfer.Result result = visit.tower.request(player, tower, now, towerAllowed(visit, player, now), config.dreamTowerDelayMillis());
            tell(player, result == TowerTransfer.Result.WAITING ? "dream.tower_departing" : "dream.tower_unavailable");
            if (result == TowerTransfer.Result.WAITING && config.dreamTowerEffects()) player.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, .5f, 1.3f);
            return;
        }
        for (DreamLayout.Dungeon dungeon : generator.layout().dungeons()) {
            if (dungeon.side() != visit.tower.side()) continue;
            boolean cloudReward = dungeon.type() == DreamLayout.DungeonType.CLOUD_RUINS && sameBlock(clicked, dungeon.reward());
            if (visit.claimed.contains(dungeon.id()) || (!isMarker(dungeon, clicked) && !cloudReward)) continue;
            if (dungeon.type() == DreamLayout.DungeonType.CRYSTAL_VAULT) {
                int progress = visit.progress.getOrDefault(dungeon.id(), 0);
                if (progress >= dungeon.puzzleAnchors().size()) {
                    if (sameBlock(clicked, dungeon.puzzleAnchors().getLast())) completeDungeon(visit, player, dungeon);
                    event.setCancelled(true); return;
                }
                DreamLayout.BlockPos expected = dungeon.puzzleAnchors().get(progress);
                if (sameBlock(clicked, expected)) {
                    progress++;
                    visit.progress.put(dungeon.id(), progress);
                    player.playSound(clicked.getLocation(), "minecraft:block.amethyst_block.chime", 0.9f, 1.1f + progress * 0.1f);
                    if (progress == dungeon.puzzleAnchors().size()) completeDungeon(visit, player, dungeon);
                    else tell(player, "dream.crystal_progress", "step", progress, "total", dungeon.puzzleAnchors().size());
                } else {
                    visit.progress.put(dungeon.id(), 0);
                    tell(player, "dream.crystal_reset");
                }
                event.setCancelled(true); return;
            }
            if (dungeon.type() == DreamLayout.DungeonType.CLOUD_RUINS) {
                DreamLayout.BlockPos reward = dungeon.reward();
                if (progressComplete(visit, dungeon) && sameBlock(clicked, reward)) completeDungeon(visit, player, dungeon);
                else tell(player, progressComplete(visit, dungeon) ? "dream.reward_marker" : "dream.cloud_need_waypoints");
                event.setCancelled(true); return;
            }
        }
        if (event.getItem() != null && api.worldGenerator().worlds().visits().isTool(event.getItem())) event.setCancelled(true);
    }

    private boolean towerAllowed(DreamVisit visit, Player player, long now) {
        return enabled && active.get(player.getUniqueId()) == visit && visit.deadline > now
                && world != null && world.world().equals(player.getWorld())
                && !(visit.nightmareStarted && !visit.nightmareDone)
                && !activity.inCombat(player.getUniqueId(), now)
                && api.worldGenerator().worlds().visits().visit(player.getUniqueId()).map(coreVisit -> coreVisit.phase() == Visit.Phase.ACTIVE).orElse(false);
    }

    private boolean isMarker(DreamLayout.Dungeon dungeon, Block clicked) {
        for (DreamLayout.BlockPos anchor : dungeon.puzzleAnchors()) if (sameBlock(clicked, anchor)) return true;
        return false;
    }

    private boolean progressComplete(DreamVisit visit, DreamLayout.Dungeon dungeon) {
        return visit.progress.getOrDefault(dungeon.id(), 0) >= dungeon.puzzleAnchors().size();
    }

    /** Stage FINISH, registered in {@link #handleSharedEvents}. */
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player) || !active.containsKey(player.getUniqueId()) || world == null || !player.getWorld().equals(world.world())) return;
        if (event.getCause() == EntityDamageEvent.DamageCause.VOID
                || event.getFinalDamage() >= player.getHealth() + player.getAbsorptionAmount()) {
            event.setCancelled(true);
            wake(player, ExitReason.PLUGIN);
        }
    }

    /** Stage PROTECT, registered in {@link #handleSharedEvents}. */
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof Player victim && world != null && victim.getWorld().equals(world.world())
                && playerAttacker(event.getDamager()) != null && !playerAttacker(event.getDamager()).equals(victim)) {
            event.setCancelled(true);
            return;
        }
        if (isDreamMob(event.getEntity())) {
            UUID owner = mobOwner(event.getEntity());
            Player attacker = playerAttacker(event.getDamager());
            if (attacker == null || !shares(owner, attacker.getUniqueId())) event.setCancelled(true);
        } else if (event.getEntity() instanceof Player victim && world != null && victim.getWorld().equals(world.world()) && isDreamMobAttacker(event.getDamager())) {
            UUID owner = mobOwner(projectileShooter(event.getDamager()));
            if (!shares(owner, victim.getUniqueId())) event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent event) {
        if (!isDreamMob(event.getEntity())) return;
        UUID owner = mobOwner(event.getEntity());
        if (!(event.getTarget() instanceof Player target) || !shares(owner, target.getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (world != null && event.getLocation().getWorld().equals(world.world()) && !spawningWave) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onExperience(PlayerExpChangeEvent event) {
        if (inDream(event.getPlayer().getUniqueId())) event.setAmount(0);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectile(ProjectileLaunchEvent event) {
        Entity shooter = projectileShooter(event.getEntity());
        if (!isDreamMob(shooter)) return;
        UUID owner = mobOwner(shooter);
        DreamVisit visit = active.get(owner);
        if (visit == null) { event.getEntity().remove(); return; }
        visit.projectiles.removeIf(id -> Bukkit.getEntity(id) == null);
        if (visit.projectiles.size() >= 128) { event.getEntity().remove(); return; }
        visit.projectiles.add(event.getEntity().getUniqueId());
        for (Player viewer : Bukkit.getOnlinePlayers()) if (!shares(owner, viewer.getUniqueId())) viewer.hideEntity(plugin, event.getEntity());
    }

    /** Stage FINISH, also for a cancelled death; registered in {@link #handleSharedEvents}. */
    public void onMobDeath(EntityDeathEvent event) {
        if (!isDreamMob(event.getEntity())) return;
        event.getDrops().clear(); event.setDroppedExp(0);
        UUID owner = mobOwner(event.getEntity());
        String sessionId = event.getEntity().getPersistentDataContainer().get(visitKey, PersistentDataType.STRING);
        DreamVisit visit = owner == null ? null : active.get(owner);
        if (visit != null && visit.sessionId.equals(sessionId) && visit.mobs.remove(event.getEntity().getUniqueId())) {
            if(event.getEntity().getKiller() != null && shares(owner, event.getEntity().getKiller().getUniqueId())) visit.waveKills++;
            else visit.waveSpawned=Math.max(visit.waveKills,visit.waveSpawned-1);
        }
        removeMob(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) { stopNap(event.getPlayer()); cleanup(event.getPlayer().getUniqueId(), true); }
    /** Stage OBSERVE, also for a cancelled death; registered in {@link #handleSharedEvents}. */
    public void onDeath(PlayerDeathEvent event) { if (world != null && event.getEntity().getWorld().equals(world.world())) wake(event.getEntity(), ExitReason.PLUGIN); }

    private void startNightmareIfNear(DreamVisit visit, Player player, Location location) {
        DreamLayout.Dungeon keep = dungeon(DreamLayout.DungeonType.NIGHTMARE_KEEP, visit.tower.side());
        if (keep == null || visit.nightmareStarted || !near(location, keep.encounterSpawn(), 4.5)) return;
        visit.nightmareStarted = true; visit.nightmareWave = 0;
        visit.nightmareStartedAt = System.currentTimeMillis();
        visit.modifiers = weeklyModifiers();
        if (!visit.modifiers.isEmpty()) tell(player, "weekly.started", "modifiers", modifierNames(player, visit.modifiers));
        beginWave(visit, player);
    }

    private void advanceNightmare(DreamVisit visit, Player player, long now) {
        if (visit.waveKills < visit.waveExpected) {
            if (visit.waveSpawned < visit.waveExpected && now >= visit.nextSpawnRetry) {
                visit.nextSpawnRetry = now + 5_000L;
                spawnWave(visit, player);
            }
            return;
        }
        List<Integer> waves = config.dreamNightmareWaves();
        if (visit.nightmareWave < waves.size()) { beginWave(visit, player); return; }
        visit.nightmareDone = true;
        DreamLayout.Dungeon keep = dungeon(DreamLayout.DungeonType.NIGHTMARE_KEEP, visit.tower.side());
        if (keep != null) completeDungeon(visit, player, keep);
        if (!visit.modifiers.isEmpty()) finishWeekly(player, now - visit.nightmareStartedAt);
    }

    private void beginWave(DreamVisit visit, Player owner) {
        List<Integer> waves = config.dreamNightmareWaves();
        if (visit.nightmareWave >= waves.size()) return;
        visit.waveExpected = Math.clamp(waves.get(visit.nightmareWave++) + (visit.modifiers.contains(WeeklyNightmare.Modifier.HORDE) ? 2 : 0), 1, 32);
        visit.waveSpawned = 0; visit.waveKills = 0; visit.mobs.clear();
        spawnWave(visit, owner);
        tell(owner, "dream.nightmare_wave", "wave", visit.nightmareWave, "total", waves.size());
        if (visit.modifiers.contains(WeeklyNightmare.Modifier.DARKNESS)) {
            List<UUID> dreamers = new ArrayList<>(companions.companionsOf(owner.getUniqueId()));
            dreamers.add(owner.getUniqueId());
            for (UUID id : dreamers) { Player p = Bukkit.getPlayer(id);
                if (p != null) p.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.DARKNESS, 20 * 12, 0, false, false)); }
        }
    }

    private void spawnWave(DreamVisit visit, Player owner) {
        DreamLayout.Dungeon keep = dungeon(DreamLayout.DungeonType.NIGHTMARE_KEEP, visit.tower.side());
        if (keep == null || world == null || world.world() == null) return;
        List<DreamLayout.BlockPos> safeAnchors = keep.puzzleAnchors();
        // The dream world is locked to daylight; these wardens do not burn in open arenas.
        EntityType[] types = {EntityType.HUSK, EntityType.VINDICATOR, EntityType.WITHER_SKELETON};
        for (int i = visit.waveSpawned; i < visit.waveExpected; i++) {
            DreamLayout.BlockPos safeAnchor = safeAnchors.get(i % safeAnchors.size());
            Location spawn = blockLocation(world.world(), safeAnchor).add(i % 2 == 0 ? -0.65 : 0.65, 0, (i % 3 - 1) * 0.4);
            Block feet = spawn.getBlock();
            if (!feet.isPassable() || !feet.getRelative(0, 1, 0).isPassable() || !feet.getRelative(0, -1, 0).getType().isSolid()) continue;
            Entity entity;
            try {
                spawningWave = true;
                Class<? extends Entity> entityClass = types[(visit.nightmareWave + i) % types.length].getEntityClass();
                if (entityClass == null || !Mob.class.isAssignableFrom(entityClass)) continue;
                entity = world.world().spawn(spawn, entityClass.asSubclass(Mob.class),
                        spawned -> spawned.setVisibleByDefault(false));
            }
            catch (RuntimeException spawnFailure) { continue; }
            finally { spawningWave = false; }
            if (!(entity instanceof Mob mob) || !entity.isValid()) { if (entity != null) entity.remove(); continue; }
            var pdc = entity.getPersistentDataContainer();
            pdc.set(mobKey, PersistentDataType.BYTE, (byte) 1);
            pdc.set(ownerKey, PersistentDataType.STRING, owner.getUniqueId().toString());
            pdc.set(visitKey, PersistentDataType.STRING, visit.sessionId);
            mob.setRemoveWhenFarAway(false); mob.setPersistent(true); mob.setCanPickupItems(false); mob.setTarget(owner);
            for (WeeklyNightmare.Modifier modifier : visit.modifiers) {
                org.bukkit.potion.PotionEffectType effect = switch (modifier) {
                    case SWIFT -> org.bukkit.potion.PotionEffectType.SPEED;
                    case ARMORED -> org.bukkit.potion.PotionEffectType.RESISTANCE;
                    case FEROCIOUS -> org.bukkit.potion.PotionEffectType.STRENGTH;
                    default -> null;
                };
                if (effect != null) mob.addPotionEffect(new org.bukkit.potion.PotionEffect(effect, org.bukkit.potion.PotionEffect.INFINITE_DURATION, 0, false, false));
            }
            api.compat().platform().customName(mob, net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection()
                    .deserialize(config.messages().text(owner, "dream.mob_name")));
            mob.setCustomNameVisible(false);
            for (Player online : Bukkit.getOnlinePlayers()) if (!shares(owner.getUniqueId(), online.getUniqueId())) online.hideEntity(plugin, entity);
            for (Player online : Bukkit.getOnlinePlayers()) if (shares(owner.getUniqueId(), online.getUniqueId())) online.showEntity(plugin, entity);
            visit.mobs.add(entity.getUniqueId());
            visit.waveSpawned++;
        }
    }

    private void completeDungeon(DreamVisit visit, Player player, DreamLayout.Dungeon dungeon) {
        if (visit.claimed.contains(dungeon.id())) return;
        ItemStack[] reward = parseRewards(dungeon.id());
        if (reward.length == 0) { tell(player, "dream.reward_unconfigured"); return; }
        String claimId = stableClaimId(visit.sessionId, dungeon.id());
        PendingReward pending = new PendingReward(player.getUniqueId(), claimId, dungeon.id(), reward);
        PendingReward queued = pendingRewards.putIfAbsent(claimId, pending);
        if (queued == null) credit(pending);
        // Only a durable credit (or the same durable receipt on retry) completes the dungeon.
        if (visit.claimed.contains(dungeon.id())) tell(player, "dream.dungeon_complete", "dungeon", dungeon.id());
    }

    private ItemStack[] parseRewards(String dungeon) {
        List<ItemStack> items = new ArrayList<>();
        for (String spec : config.dreamRewards(dungeon)) {
            String[] parts = spec.split(":", 2);
            Material material = Material.matchMaterial(parts[0].trim());
            int amount = parts.length == 2 ? parsePositive(parts[1], 1) : 1;
            if (material == null || material.isAir() || amount > material.getMaxStackSize()) continue;
            items.add(new ItemStack(material, amount));
        }
        return items.toArray(ItemStack[]::new);
    }

    private void credit(PendingReward reward) {
        try {
            pouch.credit(reward.player, reward.claimId, reward.items);
            Player questPlayer = Bukkit.getPlayer(reward.player);
            if (questPlayer != null && questPlayer.isOnline() && questAvailable(questPlayer)) {
                var completed = generator.layout().dungeons().stream().filter(d -> d.id().equals(reward.dungeon)).findFirst();
                if (completed.isPresent() && quests.complete(reward.player, completed.get().type())) showQuest(questPlayer);
                creditQuestEnding(questPlayer);
            }
            pendingRewards.remove(reward.claimId);
            warnedClaims.remove(reward.claimId);
            DreamVisit visit = active.get(reward.player);
            if (visit != null && visit.sessionId.equals(reward.sessionId())) visit.claimed.add(reward.dungeon);
            Player player = Bukkit.getPlayer(reward.player);
            if (player != null && player.isOnline()) tell(player, "dream.loot_pouched", "dungeon", reward.dungeon);
        } catch (RuntimeException failure) {
            if (warnedClaims.add(reward.claimId)) plugin.getLogger().warning("Dream reward credit will retry for " + reward.player + " (" + reward.claimId + "): " + failure.getMessage());
        }
    }

    private static int parsePositive(String raw, int fallback) { try { return Math.max(1, Integer.parseInt(raw)); } catch (NumberFormatException ignored) { return fallback; } }

    private boolean questAvailable(Player player) {
        return quests != null && config.dreamQuestsEnabled() && player.hasPermission("insomnia.dream.quest");
    }

    /** Choices change only the persisted campaign, never inventory or the active visit snapshot. */
    public void quest(Player player, String action) {
        if (!config.dreamQuestsEnabled()) { tell(player, at.vystorm.insomnia.command.InsomniaCommand.OFF); return; }
        if (!config.dreamEnabled()) { needs(player, "dream.enabled"); return; }
        if (!questAvailable(player)) { tell(player, "quest.unavailable"); return; }
        try {
            String selected=action==null?"status":action.toLowerCase(Locale.ROOT);
            switch(selected) {
                case "start" -> quests.setActive(player.getUniqueId(),true);
                case "pause" -> quests.setActive(player.getUniqueId(),false);
                case "mercy", "courage", "restore", "master" -> quests.choose(player.getUniqueId(),selected);
                case "status" -> { }
                default -> { tell(player,"quest.usage"); return; }
            }
            creditQuestEnding(player);
            showQuest(player);
        } catch(RuntimeException failure) {
            plugin.getLogger().warning("Dream quest storage unavailable: "+failure.getMessage());
            tell(player,"dream.storage_unavailable");
        }
    }

    private void showQuest(Player player) {
        var state=quests.progress(player.getUniqueId());
        if(state.finished()) { tell(player,"quest.ending_"+state.ending());tell(player,"quest.reward");return; }
        if(!state.active()) {tell(player,"quest.paused");return;}
        tell(player,"quest.chapter_"+state.stage());
        if(state.required()!=null)tell(player,"quest.objective","dungeon",config.messages().text(player,"dream.name_"+state.required().name().toLowerCase(Locale.ROOT)));
    }

    private void creditQuestEnding(Player player) {
        if(!questAvailable(player) || pouch==null)return;
        var state=quests.progress(player.getUniqueId());
        if(!state.finished())return;
        List<ItemStack> rewards=new ArrayList<>();
        for(String spec:config.dreamQuestRewards(state.ending())) {
            String[] parts=spec.split(":",2);Material material=Material.matchMaterial(parts[0].trim());
            int amount=parts.length==2?parsePositive(parts[1],1):1;
            if(material==null || material.isAir() || !material.isItem() || amount>material.getMaxStackSize())
                throw new IllegalArgumentException("Invalid dream quest reward: "+spec);
            rewards.add(new ItemStack(material,amount));
        }
        if(rewards.isEmpty() || rewards.size()>36)throw new IllegalArgumentException("Dream quest reward count must be 1..36");
        pouch.credit(player.getUniqueId(),DreamQuestStore.rewardId(player.getUniqueId()),rewards.toArray(ItemStack[]::new));
    }
    public static long durationMillis(double fatigue, double minimumFatigue, double maximumFatigue, long minimumMillis, long maximumMillis) {
        double ratio = Math.clamp((fatigue - minimumFatigue) / Math.max(1.0, maximumFatigue - minimumFatigue), 0.0, 1.0);
        return minimumMillis + Math.round((maximumMillis - minimumMillis) * ratio);
    }
    public static String stableSessionId(UUID player, long visitStartedAt, String worldId) {
        String source = player + ":" + visitStartedAt + ":" + worldId;
        return UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8)).toString();
    }
    public static String stableClaimId(String sessionId, String dungeonId) { return sessionId + ":" + dungeonId; }
    private boolean isDreamMob(Entity entity) { return entity != null && entity.getPersistentDataContainer().has(mobKey, PersistentDataType.BYTE); }
    private UUID mobOwner(Entity entity) { try { return UUID.fromString(entity.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING)); } catch (RuntimeException ignored) { return null; } }
    private static Player playerAttacker(Entity entity) { return entity instanceof Player p ? p : entity instanceof Projectile p && p.getShooter() instanceof Player shooter ? shooter : null; }
    private boolean isDreamMobAttacker(Entity entity) { return isDreamMob(projectileShooter(entity)); }
    private static Entity projectileShooter(Entity entity) {
        return entity instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter ? shooter : entity;
    }
    private void removeMob(Entity entity) { if (entity != null && entity.isValid()) entity.remove(); }

    private void updateTimeBar(Player player, DreamVisit visit, long remaining) {
        if (visit.timeBar == null) return;
        String time = DreamTimeBar.timeText(remaining);
        String title = config.messages() == null ? "Dream time: " + time
                : config.messages().text(player, "dream.time_bar", "time", time);
        visit.timeBar.update(remaining, title);
    }

    private void cleanup(UUID id, boolean finishSleep) {
        endBonds(id);
        DreamVisit visit = active.remove(id);
        if (visit != null && visit.timeBar != null) visit.timeBar.close();
        if (finishSleep) { Player player = Bukkit.getPlayer(id); if (player != null) sleep.stop(player, true); }
        if (visit != null) {
            visit.tower.cancel();
            Set<UUID> owned = new HashSet<>(visit.mobs); owned.addAll(visit.projectiles);
            for (UUID mob : owned) { Entity entity = Bukkit.getEntity(mob); if (entity != null) removeMob(entity); }
        }
        pendingEntries.remove(id);
    }

    private static boolean sameBlock(Block block, DreamLayout.BlockPos pos) { return block.getX() == pos.x() && block.getY() == pos.y() - 1 && block.getZ() == pos.z(); }
    private static boolean near(Location location, DreamLayout.BlockPos pos, double radius) {
        return location.getWorld() != null && location.getBlockX() - pos.x() <= radius && pos.x() - location.getBlockX() <= radius
                && location.getBlockZ() - pos.z() <= radius && pos.z() - location.getBlockZ() <= radius
                && Math.abs(location.getY() - pos.y()) <= 2.5;
    }
    private static Location blockLocation(World world, DreamLayout.BlockPos pos) { return new Location(world, pos.x() + 0.5, pos.y(), pos.z() + 0.5); }
    private DreamLayout.Dungeon dungeon(DreamLayout.DungeonType type, int side) { return generator.layout().dungeons().stream().filter(d -> d.type() == type && d.side() == side).findFirst().orElse(null); }
    // ---- shared dreams ----

    /** The host or one of their companions (null-safe for services built without the constructor in tests). */
    private boolean shares(UUID host, UUID player) {
        DreamCompanions c = companions;
        return c == null ? host != null && host.equals(player) : c.shares(host, player);
    }

    private boolean companionsEnabled() { return plugin.getConfig().getBoolean("dream.companions.enabled", true); }
    private int maxCompanions() { return Math.clamp(plugin.getConfig().getInt("dream.companions.max", 3), 1, 8); }

    /** {@code /dream invite <player>}: the inviter and the target must both be in the dream. */
    public void invite(Player host, String name) {
        if (!companionsEnabled()) { tell(host, at.vystorm.insomnia.command.InsomniaCommand.OFF); return; }
        if (!config.dreamEnabled()) { needs(host, "dream.enabled"); return; }
        if (!active.containsKey(host.getUniqueId())) { tell(host, "companion.not-dreaming"); return; }
        Player target = name == null ? null : Bukkit.getPlayerExact(name);
        if (target == null || !active.containsKey(target.getUniqueId()) || !host.canSee(target)) { tell(host, "companion.target-not-dreaming", "player", name == null ? "?" : name); return; }
        String result = companions.invite(host.getUniqueId(), target.getUniqueId(), System.currentTimeMillis(), 60_000L, maxCompanions());
        if (!result.equals("ok")) { tell(host, "companion.error-" + result, "player", target.getName(), "max", maxCompanions()); return; }
        tell(host, "companion.invited", "player", target.getName());
        api.compat().platform().sendMessage(target, net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection()
                .deserialize(config.message(target, "companion.invitation", "player", host.getName()))
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/dream join " + host.getName())));
    }

    /** {@code /dream join [player]}: accepts an invitation (the newest one without a name). */
    public void join(Player player, String name) {
        if (!companionsEnabled()) { tell(player, at.vystorm.insomnia.command.InsomniaCommand.OFF); return; }
        if (!config.dreamEnabled()) { needs(player, "dream.enabled"); return; }
        long now = System.currentTimeMillis();
        UUID host;
        if (name == null || name.isBlank()) {
            List<UUID> open = companions.invitesFor(player.getUniqueId(), now);
            host = open.isEmpty() ? null : open.getFirst();
        } else {
            Player p = Bukkit.getPlayerExact(name);
            host = p == null ? null : p.getUniqueId();
        }
        if (host == null || !active.containsKey(host)) { tell(player, "companion.error-no-invite"); return; }
        String result = companions.accept(player.getUniqueId(), host, now, maxCompanions());
        Player hostPlayer = Bukkit.getPlayer(host);
        if (!result.equals("ok")) { tell(player, "companion.error-" + result, "player", hostPlayer == null ? "?" : hostPlayer.getName(), "max", maxCompanions()); return; }
        DreamVisit visit = active.get(host);
        if (visit != null) for (UUID mob : visit.mobs) { Entity e = Bukkit.getEntity(mob); if (e != null) player.showEntity(plugin, e); }
        tell(player, "companion.joined", "player", hostPlayer == null ? "?" : hostPlayer.getName());
        if (hostPlayer != null) tell(hostPlayer, "companion.joined-host", "player", player.getName());
    }

    /** {@code /dream leave}: ends your shared dream (as companion or host); your own dream goes on. */
    public void leaveShared(Player player) {
        if (companions.hostOf(player.getUniqueId()).isEmpty() && companions.companionsOf(player.getUniqueId()).isEmpty()) { tell(player, "companion.none"); return; }
        endBonds(player.getUniqueId());
    }

    /** Hides the host's wardens from former companions and tells both sides. */
    private void endBonds(UUID id) {
        if (companions == null) return;
        for (Map.Entry<UUID, UUID> bond : companions.leave(id)) {
            UUID host = bond.getKey(), companion = bond.getValue();
            Player c = Bukkit.getPlayer(companion), h = Bukkit.getPlayer(host);
            DreamVisit visit = active.get(host);
            if (visit != null && c != null) for (UUID mob : visit.mobs) { Entity e = Bukkit.getEntity(mob); if (e != null) c.hideEntity(plugin, e); }
            if (c != null) tell(c, "companion.ended", "player", h == null ? "?" : h.getName());
            if (h != null) tell(h, "companion.ended", "player", c == null ? "?" : c.getName());
        }
    }

    /** Names of the players sharing the viewer's dream, for {@code /dream} (empty when none). */
    public List<String> sharedWith(UUID id) {
        List<String> out = new ArrayList<>();
        companions.hostOf(id).map(Bukkit::getPlayer).ifPresent(p -> out.add(p.getName()));
        for (UUID c : companions.companionsOf(id)) { Player p = Bukkit.getPlayer(c); if (p != null) out.add(p.getName()); }
        return out;
    }

    // ---- weekly nightmare challenge ------------------------------------------------------------------------

    private java.io.File weeklyFile() { return new java.io.File(plugin.getDataFolder(), "data/weekly-nightmare.yml"); }

    private void loadWeekly() {
        if (!config.dreamWeeklyEnabled()) { weekly = null; return; }
        long week = WeeklyNightmare.week(System.currentTimeMillis());
        // a weekly file with a YAML error is copied aside first: saveWeekly() writes it again
        org.bukkit.configuration.file.YamlConfiguration yaml = at.vystorm.insomnia.config.YamlCheck.loadData(weeklyFile().toPath(), plugin.getLogger());
        Map<String, String> rows = new HashMap<>();
        var section = yaml.getConfigurationSection("times");
        if (section != null && yaml.getLong("week", Long.MIN_VALUE) == week) for (String id : section.getKeys(false)) rows.put(id, section.getString(id, ""));
        weekly = WeeklyNightmare.Board.decode(week, rows);
    }

    private void saveWeekly() {
        if (weekly == null) return;
        org.bukkit.configuration.file.YamlConfiguration yaml = new org.bukkit.configuration.file.YamlConfiguration();
        yaml.set("week", weekly.week());
        weekly.encode().forEach((id, value) -> yaml.set("times." + id, value));
        try {
            java.io.File file = weeklyFile();
            java.nio.file.Files.createDirectories(file.getParentFile().toPath());
            api.data().files().write(file.toPath(), yaml.saveToString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception failure) { plugin.getLogger().warning("Could not save data/weekly-nightmare.yml: " + failure.getMessage()); }
    }

    /** This week's modifiers, or none while the weekly challenge is off. */
    private List<WeeklyNightmare.Modifier> weeklyModifiers() {
        if (weekly == null) return List.of();
        long week = WeeklyNightmare.week(System.currentTimeMillis());
        if (weekly.roll(week)) saveWeekly();
        return WeeklyNightmare.modifiers(week, config.dreamWorldSeed(), config.dreamWeeklyModifierCount());
    }

    private String modifierNames(Player player, List<WeeklyNightmare.Modifier> modifiers) {
        List<String> names = new ArrayList<>();
        for (WeeklyNightmare.Modifier modifier : modifiers) names.add(config.messages().text(player, "weekly.modifier." + modifier.key()));
        return String.join(", ", names);
    }

    private void finishWeekly(Player player, long millis) {
        if (weekly == null) return;
        if (weekly.roll(WeeklyNightmare.week(System.currentTimeMillis()))) saveWeekly();
        boolean first = !weekly.cleared(player.getUniqueId());
        boolean best = weekly.record(player.getUniqueId(), player.getName(), millis);
        saveWeekly();
        tell(player, best ? "weekly.record" : "weekly.cleared", "time", WeeklyNightmare.format(millis), "rank", weekly.rank(player.getUniqueId()), "count", weekly.size());
        if (!first || pouch == null) return;
        List<ItemStack> items = new ArrayList<>();
        for (String spec : config.dreamWeeklyRewards()) {
            String[] parts = spec.split(":", 2);
            Material material = Material.matchMaterial(parts[0].trim());
            int amount = parts.length == 2 ? parsePositive(parts[1], 1) : 1;
            if (material != null && !material.isAir() && amount <= material.getMaxStackSize()) items.add(new ItemStack(material, amount));
        }
        if (items.isEmpty()) return;
        try {
            pouch.credit(player.getUniqueId(), "weekly:" + weekly.week() + ":" + player.getUniqueId(), items.toArray(ItemStack[]::new));
            tell(player, "weekly.reward");
        } catch (RuntimeException failure) {
            plugin.getLogger().warning("Weekly nightmare reward for " + player.getName() + " failed: " + failure.getMessage());
        }
    }

    /** {@code /dream weekly}: this week's modifiers, the time left and the five fastest clears. */
    public void weekly(Player player) {
        if (!config.dreamWeeklyEnabled()) { tell(player, at.vystorm.insomnia.command.InsomniaCommand.OFF); return; }
        if (!config.dreamEnabled()) { needs(player, "dream.enabled"); return; }
        if (!enabled || weekly == null) { tell(player, "weekly.disabled"); return; }
        List<WeeklyNightmare.Modifier> modifiers = weeklyModifiers();
        long left = WeeklyNightmare.weekEnd(weekly.week()) - System.currentTimeMillis();
        tell(player, "weekly.header", "modifiers", modifierNames(player, modifiers), "days", left / WeeklyNightmare.DAY, "hours", left / 3_600_000L % 24);
        List<WeeklyNightmare.Entry> top = weekly.top(5);
        if (top.isEmpty()) tell(player, "weekly.empty");
        for (int i = 0; i < top.size(); i++) tell(player, "weekly.row", "rank", i + 1, "player", top.get(i).name(), "time", WeeklyNightmare.format(top.get(i).millis()));
        var own = weekly.best(player.getUniqueId());
        if (own.isPresent()) tell(player, "weekly.own", "time", WeeklyNightmare.format(own.getAsLong()), "rank", weekly.rank(player.getUniqueId()));
        else tell(player, "weekly.own-none");
    }

    private void tell(Player player, String key, Object... args) { player.sendMessage(config.message(player, key, args)); }
    /** The feature itself is on but needs the switch {@code path}, which is off (0.31.1). */
    private void needs(Player player, String path) { player.sendMessage(config.needs(player, path)); }

    private void showDungeons(Player player) {
        for(var dungeon:generator.layout().dungeons()) {
            String name=config.messages().text(player,"dream.name_"+dungeon.type().name().toLowerCase(Locale.ROOT));
            tell(player,"dream.dungeon_direction","name",name,"x",dungeon.entry().x(),"y",dungeon.entry().y(),"z",dungeon.entry().z());
        }
    }

    public void shutdown() {
        shutdown(true);
    }

    private void shutdown(boolean closeRewards) {
        for (UUID id : List.copyOf(naps.keySet())) { Player player = Bukkit.getPlayer(id); if (player != null) stopNap(player); }
        naps.clear();
        enabled = false;
        for (UUID id : active.keySet().toArray(UUID[]::new)) cleanup(id, true);
        pendingEntries.clear();
        World loadedWorld = world == null ? null : world.world();
        if (loadedWorld != null) {
            for (Entity entity : loadedWorld.getEntities()) if (isDreamMob(entity)) removeMob(entity);
            loadedWorld.removePluginChunkTickets(plugin);
        }
        api.worldGenerator().worlds().release(plugin);
        active.clear(); world = null;
        if (closeRewards) { pendingRewards.clear(); pouch = null; }
    }

    /** Applies safe policy changes after Insomnia's validated config reload. */
    public void reload() {
        if (!config.dreamEnabled()) { shutdown(false); return; }
        if (!enabled || world == null) { enable(); return; }
        if (!generator.matchesConfiguration(config.dreamWorldSeed(), config.dreamDecorationDensity(), config.dreamAmbientLight(), config.dreamSkyIslandDensity(), config.dreamNaturalAccentDensity(), config.dreamNaturalObjectDensity(), config.dreamDungeonsEnabled()))
            plugin.getLogger().warning("Dream world seed/decoration changes require a server restart; active geometry remains unchanged.");
        try {
            world = api.worldGenerator().worlds().reconfigure(plugin, worldSpec());
        } catch (RuntimeException failure) {
            plugin.getLogger().warning("Dream Realm policy reload rejected; existing Core policy remains active: " + failure.getMessage());
        }
    }

    private final class DreamHooks implements VisitHooks {
        @Override public void arrived(Player player, Visit visit) {
            PendingEntry request = pendingEntries.remove(player.getUniqueId());
            if (request == null) { api.worldGenerator().worlds().visits().exit(player, ExitReason.PLUGIN); return; }
            String sessionId = stableSessionId(player.getUniqueId(), visit.startedAt(), visit.worldId());
            DreamVisit state = new DreamVisit(sessionId, visit.worldId(), System.currentTimeMillis() + request.duration, request.bed);
            active.put(player.getUniqueId(), state);
            if (!sleep.startDream(player, System.currentTimeMillis(), request.quality)) { api.worldGenerator().worlds().visits().exit(player, ExitReason.PLUGIN); return; }
            state.timeBar = new DreamTimeBar(player, request.duration);
            updateTimeBar(player, state, request.duration);
            tell(player, "dream.entered", "minutes", Math.max(1, request.duration / 60_000L));
            showDungeons(player);
            if (questAvailable(player)) showQuest(player);
        }

        @Override public void leaving(Player player, Visit visit, ExitReason reason) {
            try { sleep.stop(player, true); }
            finally { cleanup(player.getUniqueId(), false); }
        }

        @Override public Location respawn(Player player, Visit visit) { return generator.layout().safeSpawn() == null || world == null ? null : blockLocation(world.world(), generator.layout().safeSpawn()); }
    }

    private boolean stopNap(Player player) {
        Nap nap = naps.remove(player.getUniqueId());
        if (nap == null) return false;
        player.setSleepingIgnored(nap.sleepingIgnored);
        if (player.isSleeping()) player.wakeup(false);
        sleep.stop(player, true);
        return true;
    }

    /** Stage OBSERVE, registered in {@link #handleSharedEvents}. */
    public void onNapDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) stopNap(player);
    }
    /** Stage OBSERVE, registered in {@link #handleSharedEvents}. */
    public void onNapTeleport(PlayerTeleportEvent event) { stopNap(event.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onNapSneak(PlayerToggleSneakEvent event) { if (event.isSneaking()) stopNap(event.getPlayer()); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCombust(EntityCombustEvent event) { if (isDreamMob(event.getEntity())) event.setCancelled(true); }

    private record Nap(Location origin, Location bed, Pose pose, boolean fixed, boolean sleepingIgnored) {}
    private record PendingEntry(long requestedAt, long duration, Location bed, double quality) {}
    private record PendingReward(UUID player, String claimId, String dungeon, ItemStack[] items) {
        String sessionId() { int split = claimId.lastIndexOf(':'); return split <= 0 ? claimId : claimId.substring(0, split); }
    }
    /** Inner (not static) so that it can hand Core's compat parts to the tower transfer. */
    private final class DreamVisit {
        final String sessionId, worldId; final long deadline; final Location bed;
        DreamTimeBar timeBar;
        final TowerTransfer tower = new TowerTransfer(api.compat().platform(), api.compat().gameplay());
        final Map<String,Integer> progress = new HashMap<>(); final Set<String> claimed = new HashSet<>(), announced = new HashSet<>();
        final Set<Integer> warned = new HashSet<>(); final Set<UUID> mobs = new HashSet<>(), projectiles = new HashSet<>();
        List<WeeklyNightmare.Modifier> modifiers = List.of();
        long nextHud, nextSpawnRetry, nightmareStartedAt; int nightmareWave, waveExpected, waveSpawned, waveKills; boolean nightmareStarted, nightmareDone;
        DreamVisit(String sessionId, String worldId, long deadline, Location bed) { this.sessionId=sessionId; this.worldId=worldId; this.deadline=deadline; this.bed=bed; }
    }
}
