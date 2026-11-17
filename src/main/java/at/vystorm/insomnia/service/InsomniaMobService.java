package at.vystorm.insomnia.service;

import at.vystorm.insomnia.config.InsomniaConfig;
import at.vystorm.insomnia.core.CoreBridge;
import at.vystorm.insomnia.integration.BloodMoonIntegration;
import at.vystorm.insomnia.integration.CityClaimIntegration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

public final class InsomniaMobService {
    public record DropDefinition(Material material, double chance, int min, int max) {}
    public record Tier(int id, double minFatigue, List<EntityType> types, double healthMultiplier,
                       double damageMultiplier, List<DropDefinition> drops) {}
    private record MobMeta(UUID owner, int tier, long expiresAt) {}

    private static final String TAG_MOB = "insomnia_mob";
    private static final String TAG_OWNER = "insomnia_owner";
    private static final String TAG_TIER = "insomnia_tier";
    private static final String TAG_DROP_OWNER = "insomnia_drop_owner";
    private static final int SPAWN_ATTEMPTS = 8;
    private static final int VERTICAL_SEARCH = 6;

    private final JavaPlugin plugin;
    private final InsomniaConfig config;
    private final CoreBridge core;
    private final FatigueService fatigue;
    private final BloodMoonIntegration bloodMoon;
    private final CityClaimIntegration regions;
    private final Random random = new Random();
    private final Map<UUID, MobMeta> mobs = new HashMap<>();
    private final Map<UUID, Set<UUID>> byOwner = new HashMap<>();
    private final Map<UUID, Long> nextCheck = new HashMap<>();
    private final ShardRewardLimiter shardRewards = new ShardRewardLimiter();
    private List<Tier> tiers = List.of();
    private List<String> warnings = List.of();
    /** Per-tier Core loot override ({@code tiers.<id>.loot_table}); blank = {@link InsomniaMobLoot#CATEGORY}. */
    private Map<Integer, String> lootOverrides = Map.of();
    /** Mob level per tier for its loot ({@code tiers.<id>.loot_level}, default 15 x tier id). */
    private Map<Integer, Integer> lootLevels = Map.of();
    private long nextLootWarningAt;

    public InsomniaMobService(JavaPlugin plugin, InsomniaConfig config, CoreBridge core,
                              FatigueService fatigue, BloodMoonIntegration bloodMoon, CityClaimIntegration regions) {
        this.regions = regions;
        this.plugin = plugin;
        this.config = config;
        this.core = core;
        this.fatigue = fatigue;
        this.bloodMoon = bloodMoon;
        reload();
    }

    public void reload() {
        ConfigurationSection root = config.mobTiers();
        List<Tier> loaded = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        Map<Integer, String> overrides = new HashMap<>();
        Map<Integer, Integer> levels = new HashMap<>();
        if (root != null) {
            for (String key : root.getKeys(false)) {
                ConfigurationSection section = root.getConfigurationSection(key);
                if (section == null) {
                    problems.add("mobs.yml: tier '" + key + "' is not a section and was skipped");
                    continue;
                }
                int id;
                try { id = Integer.parseInt(key); }
                catch (NumberFormatException ignored) {
                    problems.add("mobs.yml: tier key '" + key + "' is not a number and was skipped");
                    continue;
                }

                List<EntityType> types = new ArrayList<>();
                for (String raw : section.getStringList("entities")) {
                    try {
                        EntityType type = EntityType.valueOf(raw.toUpperCase());
                        if (type.isAlive()) types.add(type);
                        else problems.add("mobs.yml: tier " + key + " entity '" + raw + "' is not a living entity");
                    } catch (IllegalArgumentException ignored) {
                        problems.add("mobs.yml: tier " + key + " has unknown entity '" + raw + "'");
                    }
                }
                List<DropDefinition> drops = new ArrayList<>();
                ConfigurationSection dropRoot = section.getConfigurationSection("drops");
                if (dropRoot != null) {
                    for (String dropKey : dropRoot.getKeys(false)) {
                        ConfigurationSection drop = dropRoot.getConfigurationSection(dropKey);
                        if (drop == null) {
                            problems.add("mobs.yml: tier " + key + " drop '" + dropKey + "' is not a section and was skipped");
                            continue;
                        }
                        Material material = Material.matchMaterial(drop.getString("material", ""));
                        if (material == null) {
                            problems.add("mobs.yml: tier " + key + " drop '" + dropKey + "' has unknown material '" + drop.getString("material", "") + "'");
                            continue;
                        }
                        int min = Math.max(1, drop.getInt("min", 1));
                        int max = Math.max(min, drop.getInt("max", min));
                        drops.add(new DropDefinition(material, Math.max(0.0, Math.min(1.0, drop.getDouble("chance", 0.0))), min, max));
                    }
                }
                String override = section.getString("loot_table", "");
                if (override != null && !override.isBlank()) overrides.put(id, override.trim());
                levels.put(id, Math.max(0, Math.min(1000, section.getInt("loot_level", defaultLootLevel(id)))));
                if (types.isEmpty()) problems.add("mobs.yml: tier " + key + " has no valid entities and was skipped");
                else loaded.add(new Tier(id, section.getDouble("min_fatigue", 70.0), List.copyOf(types),
                        Math.max(0.1, section.getDouble("health_multiplier", 1.0)),
                        Math.max(0.1, section.getDouble("damage_multiplier", 1.0)), List.copyOf(drops)));
            }
        }
        loaded.sort(Comparator.comparingDouble(Tier::minFatigue));
        tiers = List.copyOf(loaded);
        lootOverrides = Map.copyOf(overrides);
        lootLevels = Map.copyOf(levels);
        // Own drop tables for Core weighted loot; one replace per owner, so a reload swaps all of them.
        if (plugin != null && plugin.isEnabled()) {
            try {
                core.api().loot().replace(plugin, InsomniaMobLoot.tables(tiers));
            } catch (RuntimeException failure) {
                problems.add("Core loot tables not registered, mob drops use the built-in roll: " + failure.getMessage());
            }
        }
        warnings = List.copyOf(problems);
    }

    /** Entries skipped by the last reload, for the log. */
    public List<String> warnings() { return warnings; }

    public void cleanupOrphans() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if ("true".equalsIgnoreCase(core.getNbt(entity, TAG_MOB)) || core.getNbt(entity, MicrosleepService.TAG_PLACEHOLDER) != null
                        || core.getNbt(entity, MicrosleepService.TAG_CAMERA) != null) {
                    entity.remove();
                }
            }
        }
    }

    /** A tagged Insomnia mob that this runtime does not track (left behind by a crash). */
    public boolean isOrphan(Entity entity) {
        return !mobs.containsKey(entity.getUniqueId()) && "true".equalsIgnoreCase(core.getNbt(entity, TAG_MOB));
    }

    public void tick(long now) {
        shardRewards.prune(now, config.shardWindowMillis());
        for (Map.Entry<UUID, MobMeta> entry : mobs.entrySet().toArray(Map.Entry[]::new)) {
            UUID entityId = entry.getKey();
            MobMeta meta = entry.getValue();
            Entity entity = findEntity(entityId);
            Player owner = Bukkit.getPlayer(meta.owner());
            if (entity == null || !entity.isValid() || owner == null || !owner.isOnline()
                    || now >= meta.expiresAt() || !entity.getWorld().equals(owner.getWorld()) || owner.isSleeping()) {
                if (entity != null) entity.remove();
                unregister(entityId, meta.owner());
                continue;
            }
            if (entity instanceof Mob mob && !owner.equals(mob.getTarget())) mob.setTarget(owner);
        }

        if (!config.mobsEnabled() || tiers.isEmpty()) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID owner = player.getUniqueId();
            if (player.isDead() || player.isSleeping() || fatigue.fatigue(owner) < config.mobMinFatigue()) continue;
            if (byOwner.getOrDefault(owner, Set.of()).size() >= config.maxMobsPerPlayer() || mobs.size() >= config.maxMobsGlobal()) continue;
            long due = nextCheck.getOrDefault(owner, 0L);
            if (now < due) continue;
            nextCheck.put(owner, now + config.mobCheckMillis());
            double chance = config.mobSpawnChance() * Math.max(0.0, bloodMoon.insomniaSpawnMultiplier(player));
            if (random.nextDouble() <= Math.min(1.0, chance)) spawn(player, tierFor(fatigue.fatigue(owner)).id(), now);
        }
    }

    public boolean spawn(Player owner, int tierId, long now) {
        Tier tier = tiers.stream().filter(candidate -> candidate.id() == tierId).findFirst().orElse(null);
        if (tier == null || mobs.size() >= config.maxMobsGlobal()) return false;
        if (byOwner.getOrDefault(owner.getUniqueId(), Set.of()).size() >= config.maxMobsPerPlayer()) return false;
        Location location = spawnLocation(owner);
        if (location == null) return false;
        EntityType type = tier.types().get(random.nextInt(tier.types().size()));
        Class<? extends Entity> entityClass = type.getEntityClass();
        if (entityClass == null || !LivingEntity.class.isAssignableFrom(entityClass)) return false;
        // Apply default visibility before the world tracker can send any spawn packet.
        // Hiding an already spawned entity briefly exposes it to other clients.
        LivingEntity living = owner.getWorld().spawn(location, entityClass.asSubclass(LivingEntity.class),
                spawned -> spawned.setVisibleByDefault(false));
        scale(living, tier);
        if (living instanceof Mob mob) {
            mob.setTarget(owner);
            mob.setPersistent(false);
            mob.setRemoveWhenFarAway(true);
            mob.setCanPickupItems(false);
        }
        track(living, owner, tier.id(), now);
        return true;
    }

    /** Tags the entity, limits its visibility to the owner and registers it. */
    void track(Entity entity, Player owner, int tierId, long now) {
        core.setNbt(entity, TAG_MOB, "true");
        core.setNbt(entity, TAG_OWNER, owner.getUniqueId().toString());
        core.setNbt(entity, TAG_TIER, Integer.toString(tierId));
        InsomniaMobLoot.claim(core.api().loot().mobs(), plugin, entity);
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!viewer.getUniqueId().equals(owner.getUniqueId())) viewer.hideEntity(plugin, entity);
        }
        owner.showEntity(plugin, entity);
        MobMeta meta = new MobMeta(owner.getUniqueId(), tierId, now + config.mobLifetimeMillis());
        mobs.put(entity.getUniqueId(), meta);
        byOwner.computeIfAbsent(owner.getUniqueId(), ignored -> new HashSet<>()).add(entity.getUniqueId());
    }

    /** hideEntity only reaches players online at spawn time; a player joining later is hidden here. */
    public void hideFrom(Player viewer) {
        for (Map.Entry<UUID, MobMeta> entry : mobs.entrySet()) {
            if (entry.getValue().owner().equals(viewer.getUniqueId())) continue;
            Entity entity = findEntity(entry.getKey());
            if (entity != null) viewer.hideEntity(plugin, entity);
        }
    }

    /** Projectiles count for whoever shot them: the owner's arrows hit, a mob's arrows are the mob's. */
    private static Entity source(Entity damager) {
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) return shooter;
        return damager;
    }

    public boolean handleDamage(EntityDamageByEntityEvent event) {
        Entity source = source(event.getDamager());
        MobMeta damagerMeta = mobs.get(source.getUniqueId());
        if (damagerMeta != null) {
            if (!(event.getEntity() instanceof Player player) || !player.getUniqueId().equals(damagerMeta.owner())) {
                event.setCancelled(true);
                return true;
            }
        }
        MobMeta victimMeta = mobs.get(event.getEntity().getUniqueId());
        if (victimMeta != null) {
            Player attacker = source instanceof Player p ? p : null;
            if (attacker == null || !attacker.getUniqueId().equals(victimMeta.owner())) {
                event.setCancelled(true);
                return true;
            }
        }
        return damagerMeta != null || victimMeta != null;
    }

    public boolean handleDeath(EntityDeathEvent event) {
        MobMeta meta = mobs.remove(event.getEntity().getUniqueId());
        if (meta == null) return false;
        unregister(event.getEntity().getUniqueId(), meta.owner());
        event.getDrops().clear();
        event.setDroppedExp(0);
        Tier tier = tiers.stream().filter(value -> value.id() == meta.tier()).findFirst().orElse(null);
        if (tier == null) return true;
        long now = System.currentTimeMillis();
        // Core weighted loot when the own tier table is registered; otherwise the built-in roll below.
        if (core.api().loot().exists(InsomniaMobLoot.tableId(tier.id()))) {
            dropCoreLoot(event, meta, tier, now);
            return true;
        }
        for (DropDefinition drop : tier.drops()) {
            boolean shard = drop.material() == Material.ECHO_SHARD;
            double multiplier = shard ? shardRewards.chance(meta.owner(), now, config.shardWindowMillis(),
                    config.shardCapPerWindow(), config.shardDiminishingAfter()) : 1.0;
            if (multiplier <= 0 || random.nextDouble() >= drop.chance() * multiplier) continue;
            int amount = drop.min() == drop.max() ? drop.min() : random.nextInt(drop.min(), drop.max() + 1);
            if (shard) {
                amount = Math.min(amount, Math.max(0, config.shardCapPerWindow() - shardRewards.count(meta.owner(), now, config.shardWindowMillis())));
                if (amount == 0) continue;
                for (int i = 0; i < amount; i++) shardRewards.awarded(meta.owner(), now);
            }
            Item item = event.getEntity().getWorld().dropItemNaturally(event.getEntity().getLocation(), new ItemStack(drop.material(), amount));
            core.setNbt(item, TAG_DROP_OWNER, meta.owner().toString());
            item.setPickupDelay(10);
        }
        return true;
    }

    /** Default loot level of a tier: 15 per tier id (tier 1 = 15, tier 3 = 45). */
    static int defaultLootLevel(int tier) { return Math.max(0, tier) * 15; }

    /** Mob level of a tier for its loot (Core mob loot luck: the level, never the player's luck). */
    public int lootLevel(int tier) { return lootLevels.getOrDefault(tier, defaultLootLevel(tier)); }

    /** Core table or category a tier rolls: the configured {@code loot_table} when Core knows it, else the category. */
    public String lootId(int tier) {
        return core.api().loot().choose(lootOverrides.getOrDefault(tier, ""), InsomniaMobLoot.CATEGORY);
    }

    /**
     * Rolls the tier's drops through Core and drops them owner-only like the built-in roll. ECHO_SHARD hits keep the
     * rolling-window limit: the reduced chance is applied as a second check (same total odds) and the amount is capped.
     */
    private void dropCoreLoot(EntityDeathEvent event, MobMeta meta, Tier tier, long now) {
        Location location = event.getEntity().getLocation();
        Player ownerPlayer = Bukkit.getPlayer(meta.owner());
        // Mob loot rule: what drops is the tier's own table, how good only the tier's level (never the owner's luck).
        core.api().loot().mobs().markRolled(event.getEntity());
        var context = Vystorm.vystorm_core.loot.RollContext.of(location)
                .withSelf(InsomniaMobLoot.tableId(tier.id()))
                .withSource(plugin.getName());
        var result = core.api().loot().rolls().forMob(lootId(tier.id()), context, lootLevel(tier.id()),
                Vystorm.vystorm_core.loot.MobLoot.Rank.COMMON).random(random).roll();
        List<String> problems = new ArrayList<>(result.problems());
        // Extra parts of other plugins (VystormItems monster drops), rolled with the same level; owner-only like the rest.
        var kill = new Vystorm.vystorm_core.loot.MobLoot.Kill(event.getEntity(), event.getEntity().getType().getKey().getKey(),
                lootLevel(tier.id()), Vystorm.vystorm_core.loot.MobLoot.Rank.COMMON, ownerPlayer, plugin.getName());
        for (ItemStack extra : core.api().loot().mobs().extras(plugin, kill, problems::add)) {
            Item item = event.getEntity().getWorld().dropItemNaturally(location, extra);
            core.setNbt(item, TAG_DROP_OWNER, meta.owner().toString());
            item.setPickupDelay(10);
        }
        String shardRef = InsomniaMobLoot.itemRef(Material.ECHO_SHARD);
        for (var hit : result.hits()) {
            int amount = hit.amount();
            if (hit.item().equalsIgnoreCase(shardRef) || hit.item().equalsIgnoreCase("echo_shard")) {
                double multiplier = shardRewards.chance(meta.owner(), now, config.shardWindowMillis(),
                        config.shardCapPerWindow(), config.shardDiminishingAfter());
                if (multiplier <= 0 || (multiplier < 1.0 && random.nextDouble() >= multiplier)) continue;
                amount = Math.min(amount, Math.max(0, config.shardCapPerWindow() - shardRewards.count(meta.owner(), now, config.shardWindowMillis())));
                if (amount == 0) continue;
                for (int i = 0; i < amount; i++) shardRewards.awarded(meta.owner(), now);
            }
            var drop = new Vystorm.vystorm_core.loot.LootResult.Hit(hit.item(), amount, hit.rarity(), hit.tier(), hit.probability(),
                    hit.table(), hit.entry(), hit.enchantLevel(), hit.enchantSeed(), hit.pity());
            List<ItemStack> stacks;
            try {
                stacks = core.api().loot().rolls().create(drop);
            } catch (RuntimeException failure) {
                problems.add("Unresolved loot item " + hit.item() + ": " + failure.getMessage());
                continue;
            }
            for (ItemStack stack : stacks) {
                Item item = event.getEntity().getWorld().dropItemNaturally(location, stack);
                core.setNbt(item, TAG_DROP_OWNER, meta.owner().toString());
                item.setPickupDelay(10);
            }
        }
        if (!problems.isEmpty() && now >= nextLootWarningAt) {
            nextLootWarningAt = now + 60_000L;
            plugin.getLogger().warning("Nightmare mob loot problems: " + String.join("; ", problems));
        }
    }

    public boolean handlePickup(EntityPickupItemEvent event) {
        String owner = core.getNbt(event.getItem(), TAG_DROP_OWNER);
        if (owner == null) return false;
        if (!(event.getEntity() instanceof Player player) || !player.getUniqueId().toString().equals(owner)) event.setCancelled(true);
        return true;
    }

    /** Hoppers and hopper minecarts must not collect drops meant for one player. */
    public boolean handleInventoryPickup(InventoryPickupItemEvent event) {
        if (core.getNbt(event.getItem(), TAG_DROP_OWNER) == null) return false;
        event.setCancelled(true);
        return true;
    }

    public boolean handleTarget(EntityTargetLivingEntityEvent event) {
        if (event.getTarget() == null) return false;
        MobMeta targetMeta = mobs.get(event.getTarget().getUniqueId());
        if (targetMeta != null) {
            event.setCancelled(true);
            return true;
        }
        MobMeta sourceMeta = mobs.get(event.getEntity().getUniqueId());
        if (sourceMeta != null && (!(event.getTarget() instanceof Player player) || !player.getUniqueId().equals(sourceMeta.owner()))) {
            event.setCancelled(true);
            return true;
        }
        return sourceMeta != null;
    }

    public boolean handleExplosion(EntityExplodeEvent event) {
        if (event.getEntity() == null || !mobs.containsKey(event.getEntity().getUniqueId())) return false;
        event.blockList().clear();
        return true;
    }

    public void clearOwner(UUID owner) {
        Set<UUID> ids = new HashSet<>(byOwner.getOrDefault(owner, Set.of()));
        for (UUID id : ids) {
            Entity entity = findEntity(id);
            if (entity != null) entity.remove();
            mobs.remove(id);
        }
        byOwner.remove(owner);
    }

    public List<Integer> tierIds() { return tiers.stream().map(Tier::id).toList(); }

    /** Loaded tiers (sorted by min fatigue). */
    List<Tier> tiers() { return tiers; }

    public void shutdown() {
        for (UUID id : new HashSet<>(mobs.keySet())) {
            Entity entity = findEntity(id);
            if (entity != null) entity.remove();
        }
        mobs.clear();
        byOwner.clear();
        nextCheck.clear();
    }

    private Tier tierFor(double value) {
        Tier selected = tiers.get(0);
        for (Tier tier : tiers) if (value >= tier.minFatigue()) selected = tier;
        return selected;
    }

    /**
     * Searches around the player's own height instead of the surface, so players in caves or the Nether are
     * reached. Unloaded chunks are skipped (no sync loads), and nothing spawns in or next to protected areas
     * (Core regions with market priority or higher: spawn, safe, arena, market).
     */
    Location spawnLocation(Player player) {
        int min = Math.max(2, config.mobMinDistance());
        int max = Math.max(min, config.mobMaxDistance());
        World world = player.getWorld();
        Location origin = player.getLocation();
        if (regions != null && regions.protectedArea(origin)) return null;
        for (int attempt = 0; attempt < SPAWN_ATTEMPTS; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double distance = min + random.nextDouble() * (max - min);
            int x = (int) Math.floor(origin.getX() + Math.cos(angle) * distance);
            int z = (int) Math.floor(origin.getZ() + Math.sin(angle) * distance);
            if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
            Location candidate = standableNear(world, x, origin.getBlockY(), z);
            if (candidate == null || (regions != null && regions.protectedArea(candidate))) continue;
            return candidate;
        }
        return null;
    }

    private Location standableNear(World world, int x, int baseY, int z) {
        int low = Math.max(world.getMinHeight() + 1, baseY - VERTICAL_SEARCH);
        int high = Math.min(world.getMaxHeight() - 2, baseY + VERTICAL_SEARCH);
        for (int offset = 0; offset <= VERTICAL_SEARCH; offset++) {
            for (int y : offset == 0 ? new int[] {baseY} : new int[] {baseY + offset, baseY - offset}) {
                if (y < low || y > high) continue;
                Block feet = world.getBlockAt(x, y, z);
                Block head = feet.getRelative(BlockFace.UP);
                Block floor = feet.getRelative(BlockFace.DOWN);
                if (feet.isPassable() && !feet.isLiquid() && head.isPassable() && !head.isLiquid() && core.api().compat().gameplay().isSolid(floor)) {
                    return new Location(world, x + 0.5, y, z + 0.5);
                }
            }
        }
        return null;
    }

    private void scale(LivingEntity entity, Tier tier) {
        scaleAttribute(entity, core.api().compat().server().attribute("MAX_HEALTH", "GENERIC_MAX_HEALTH"), tier.healthMultiplier(), true);
        scaleAttribute(entity, core.api().compat().server().attribute("ATTACK_DAMAGE", "GENERIC_ATTACK_DAMAGE"), tier.damageMultiplier(), false);
    }

    private static void scaleAttribute(LivingEntity entity, Attribute attribute, double multiplier, boolean heal) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance == null) return;
        instance.setBaseValue(Math.max(0.0001, instance.getBaseValue() * multiplier));
        if (heal) entity.setHealth(Math.min(instance.getValue(), entity.getMaxHealth()));
    }

    private void unregister(UUID entity, UUID owner) {
        mobs.remove(entity);
        Set<UUID> ids = byOwner.get(owner);
        if (ids != null) {
            ids.remove(entity);
            if (ids.isEmpty()) byOwner.remove(owner);
        }
    }

    private Entity findEntity(UUID uuid) {
        for (World world : Bukkit.getWorlds()) {
            Entity entity;
            try { entity=world.getEntity(uuid); }
            catch(NoSuchMethodError absent) { entity=Bukkit.getEntity(uuid);if(entity!=null && !entity.getWorld().equals(world)) entity=null; }
            if (entity != null) return entity;
        }
        return null;
    }
}
