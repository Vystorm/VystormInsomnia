package at.vystorm.insomnia.service;

import Vystorm.vystorm_core.schedule.TaskGroup;
import at.vystorm.insomnia.config.InsomniaConfig;
import at.vystorm.insomnia.core.CoreBridge;
import at.vystorm.insomnia.model.PlayerFatigueState;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.BiConsumer;

public final class MicrosleepService {
    public static final String TAG_PLACEHOLDER = "insomnia_placeholder_owner";
    public static final String TAG_CAMERA = "insomnia_camera_owner";
    private static final List<EquipmentSlot> BODY_SLOTS = List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST,
            EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.HAND, EquipmentSlot.OFF_HAND);
    /** The spawn packet goes out with the next entity tracker tick; equipment sent earlier would be dropped by the client. */
    private static final long MIRROR_DELAY_TICKS = 2L;
    /**
     * Plain Paper PDC instead of Core NBT on purpose: the way back out of spectator must not depend on
     * Core's entity-NBT toggle.
     */
    static final NamespacedKey SESSION_KEY = new NamespacedKey("vystorminsomnia", "microsleep_session");

    static final NamespacedKey GRACE_KEY = new NamespacedKey("vystorminsomnia", "microsleep_recovery_until");
    private static final NamespacedKey RECOVERED_KEY = new NamespacedKey("vystorminsomnia", "microsleep_recovered_token");
    private static final NamespacedKey PENDING_GRACE_KEY = new NamespacedKey("vystorminsomnia", "microsleep_pending_grace");

    public static long recoveryRemaining(Player player, long now) {
        Long until = player.getPersistentDataContainer().get(GRACE_KEY, PersistentDataType.LONG);
        return until == null ? 0 : Math.max(0, until - now);
    }

    public void disconnect(Player player) {
        nextCheck.remove(player.getUniqueId());
        if (!isActive(player.getUniqueId())) return;
        player.getPersistentDataContainer().set(PENDING_GRACE_KEY, PersistentDataType.BYTE, (byte) 1);
        end(player, true);
    }

    private void recoveryGrace(Player player, String token) {
        var pdc = player.getPersistentDataContainer();
        String previous = pdc.get(RECOVERED_KEY, PersistentDataType.STRING);
        boolean pending = pdc.has(PENDING_GRACE_KEY, PersistentDataType.BYTE);
        if (pending || (token != null && !token.equals(previous))) {
            pdc.set(GRACE_KEY, PersistentDataType.LONG, System.currentTimeMillis() + config.microsleepRecoveryGraceMillis());
            if (token != null) pdc.set(RECOVERED_KEY, PersistentDataType.STRING, token);
            pdc.remove(PENDING_GRACE_KEY);
            player.sendMessage(config.message(player, "microsleep_recovered", "seconds", config.microsleepRecoveryGraceMillis() / 1000));
        }
    }

    private record Session(UUID playerId, GameMode gameMode, Location location, boolean allowFlight,
                           boolean flying, ArmorStand body, ArmorStand camera, long endsAt) {}

    private final JavaPlugin plugin;
    private final InsomniaConfig config;
    private final CoreBridge core;
    private final FatigueService fatigue;
    private final SleepService sleep;
    private final ActivityService activity;
    private final VisibilityLocks visibility;
    private final Random random = new Random();
    private final Map<UUID, Session> active = new HashMap<>();
    private final Map<UUID, UUID> bodyOwners = new HashMap<>();
    private final Map<UUID, Long> nextCheck = new HashMap<>();
    private final BiConsumer<Runnable, Long> later;
    /** Group behind {@link #later} in production (null in tests with an injected scheduler); closed on shutdown. */
    private TaskGroup tasks;

    public MicrosleepService(JavaPlugin plugin, InsomniaConfig config, CoreBridge core,
                             FatigueService fatigue, SleepService sleep, ActivityService activity,
                             VisibilityLocks visibility) {
        this(plugin, config, core, fatigue, sleep, activity, visibility, core.api().tasks().of(plugin, "microsleep"));
    }

    private MicrosleepService(JavaPlugin plugin, InsomniaConfig config, CoreBridge core, FatigueService fatigue,
                              SleepService sleep, ActivityService activity, VisibilityLocks visibility, TaskGroup tasks) {
        this(plugin, config, core, fatigue, sleep, activity, visibility, (task, ticks) -> tasks.later(ticks, task));
        this.tasks = tasks;
    }

    MicrosleepService(JavaPlugin plugin, InsomniaConfig config, CoreBridge core, FatigueService fatigue,
                      SleepService sleep, ActivityService activity, VisibilityLocks visibility,
                      BiConsumer<Runnable, Long> later) {
        this.later = later;
        this.visibility = visibility;
        this.activity = activity;
        this.plugin = plugin;
        this.config = config;
        this.core = core;
        this.fatigue = fatigue;
        this.sleep = sleep;
    }

    public boolean isActive(UUID player) { return active.containsKey(player); }

    /** True for bodies and cameras of running sessions. */
    public boolean ownsEntity(UUID entity) {
        if (bodyOwners.containsKey(entity)) return true;
        for (Session session : active.values()) if (session.camera().getUniqueId().equals(entity)) return true;
        return false;
    }

    /** A tagged body or camera that no running session owns (left behind by a crash). */
    public boolean isOrphan(Entity entity) {
        if (ownsEntity(entity.getUniqueId())) return false;
        return core.getNbt(entity, TAG_PLACEHOLDER) != null || core.getNbt(entity, TAG_CAMERA) != null;
    }

    public void tick(long now) {
        for (Session session : active.values().toArray(Session[]::new)) {
            Player player = Bukkit.getPlayer(session.playerId());
            if (player == null || !player.isOnline()) {
                removeEntities(session);
                active.remove(session.playerId());
                nextCheck.remove(session.playerId());
                continue;
            }
            if (now >= session.endsAt() || !session.body().isValid() || !session.camera().isValid()) {
                end(player, true);
            } else if (player.getSpectatorTarget() == null || !player.getSpectatorTarget().getUniqueId().equals(session.camera().getUniqueId())) {
                player.setSpectatorTarget(session.camera());
            }
        }

        if (!config.microsleepEnabled()) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            // Cheap in-memory checks first; the two persistent-data reads only for players who could really doze off now.
            if (active.containsKey(uuid) || sleep.isSleeping(uuid) || player.isDead() || now < nextCheck.getOrDefault(uuid, 0L)) continue;
            if (player.getGameMode() != GameMode.SURVIVAL && player.getGameMode() != GameMode.ADVENTURE) continue;
            if (!fatigue.ready(uuid)) continue;
            PlayerFatigueState state = fatigue.require(uuid);
            if (state.fatigue() < config.microsleepMinFatigue()) continue;
            if (now - state.lastMicrosleepEpochMillis() < config.microsleepCooldownMillis()) continue;
            if (recoveryRemaining(player, now) > 0
                    || player.getPersistentDataContainer().has(SESSION_KEY, PersistentDataType.STRING)) continue;
            nextCheck.put(uuid, now + config.microsleepCheckMillis());
            if (random.nextDouble() <= config.microsleepChance()) start(player, now);
        }
    }

    public boolean start(Player player, long now) {
        UUID uuid = player.getUniqueId();
        if (active.containsKey(uuid) || sleep.isSleeping(uuid)) return false;
        if (player.getGameMode() != GameMode.SURVIVAL && player.getGameMode() != GameMode.ADVENTURE) return false;
        if (!safeToDozeOff(player, now)) return false;
        if (recoveryRemaining(player, now) > 0 || player.getPersistentDataContainer().has(SESSION_KEY, PersistentDataType.STRING)) return false;

        Location origin = player.getLocation().clone();
        // The body carries no real items: equipment is only mirrored to viewers as packets (see mirrorEquipment),
        // so nothing on the stand can be taken, dropped or duplicated.
        ArmorStand body = player.getWorld().spawn(origin, ArmorStand.class, stand -> {
            stand.setGravity(false);
            stand.setArms(true);
            stand.setBasePlate(false);
            stand.setInvulnerable(false);
            stand.setCanPickupItems(false);
            stand.setPersistent(false);
            for (EquipmentSlot slot : BODY_SLOTS) {
                stand.addEquipmentLock(slot, ArmorStand.LockType.ADDING_OR_CHANGING);
                stand.addEquipmentLock(slot, ArmorStand.LockType.REMOVING_OR_CHANGING);
            }
        });
        core.setNbt(body, TAG_PLACEHOLDER, uuid.toString());

        ArmorStand camera = player.getWorld().spawn(origin.clone().add(0.0, 1.35, 0.0), ArmorStand.class, stand -> {
            stand.setVisible(false);
            stand.setGravity(false);
            stand.setMarker(true);
            stand.setInvulnerable(true);
            stand.setPersistent(false);
        });
        core.setNbt(camera, TAG_CAMERA, uuid.toString());

        int min = Math.max(1, config.microsleepMinSeconds());
        int max = Math.max(min, config.microsleepMaxSeconds());
        int seconds = min == max ? min : random.nextInt(min, max + 1);
        Session session = new Session(uuid, player.getGameMode(), origin, player.getAllowFlight(), player.isFlying(),
                body, camera, now + seconds * 1000L);
        active.put(uuid, session);
        bodyOwners.put(body.getUniqueId(), uuid);

        PlayerFatigueState state = fatigue.require(uuid);
        state.lastMicrosleepEpochMillis(now);
        player.sendMessage(config.message(player, "microsleep"));
        // Stored in the player's own data file, so it is saved together with the spectator game mode:
        // any saved state that has SPECTATOR from us also carries the way back (see restoreAfterCrash).
        player.getPersistentDataContainer().set(SESSION_KEY, PersistentDataType.STRING,
                Snapshot.of(session).encode());
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (!other.getUniqueId().equals(uuid)) visibility.hide(other, player);
        }
        player.setGameMode(GameMode.SPECTATOR);
        player.setSpectatorTarget(camera);
        Map<EquipmentSlot, ItemStack> visual = visualEquipment(player);
        later.accept(() -> mirrorEquipment(uuid, body, visual), MIRROR_DELAY_TICKS);
        return true;
    }

    /**
     * Only on firm ground and out of danger: the player returns to the start position, so starting mid-air,
     * in a vehicle or in liquid would freeze or cheat a fall, and a fight would lose its target.
     */
    boolean safeToDozeOff(Player player, long now) {
        if (player.isDead() || player.isInsideVehicle() || player.isGliding()) return false;
        if (player.isInWater() || core.api().compat().gameplay().isInLava(player) || !((Entity) player).isOnGround()) return false;
        return activity == null || !activity.inCombat(player.getUniqueId(), now);
    }

    /**
     * Setting the spectator target teleports with cause SPECTATE; that one belongs to the session. Any other
     * spectator teleport (hotbar player list) is refused, every other cause ends the session where it is.
     */
    public void handleTeleport(PlayerTeleportEvent event) {
        Session session = active.get(event.getPlayer().getUniqueId());
        if (session == null) return;
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.SPECTATE) {
            Location to = event.getTo();
            Location camera = session.camera().getLocation();
            boolean ownCamera = to != null && camera.getWorld() != null && camera.getWorld().equals(to.getWorld())
                    && camera.distanceSquared(to) <= 4.0;
            if (!ownCamera) event.setCancelled(true);
            return;
        }
        end(event.getPlayer(), false);
    }

    /** Snapshot of what the body should look like; the stand itself stays empty. */
    private Map<EquipmentSlot, ItemStack> visualEquipment(Player player) {
        Map<EquipmentSlot, ItemStack> items = new EnumMap<>(EquipmentSlot.class);
        PlayerInventory inventory = player.getInventory();
        items.put(EquipmentSlot.HEAD, copy(inventory.getHelmet()));
        items.put(EquipmentSlot.CHEST, copy(inventory.getChestplate()));
        items.put(EquipmentSlot.LEGS, copy(inventory.getLeggings()));
        items.put(EquipmentSlot.FEET, copy(inventory.getBoots()));
        items.put(EquipmentSlot.HAND, copy(inventory.getItemInMainHand()));
        items.put(EquipmentSlot.OFF_HAND, copy(inventory.getItemInOffHand()));
        return items;
    }

    private ItemStack copy(ItemStack item) {
        return item == null || core.api().compat().gameplay().isEmpty(item) ? null : item.clone();
    }

    /** Client-side only: viewers see the owner's gear on the body, the server entity holds nothing. */
    private void mirrorEquipment(UUID owner, ArmorStand body, Map<EquipmentSlot, ItemStack> items) {
        Session session = active.get(owner);
        if (session == null || session.body() != body || !body.isValid()) return;
        items.values().removeIf(java.util.Objects::isNull);
        if (items.isEmpty()) return;
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.getUniqueId().equals(owner) || !viewer.getWorld().equals(body.getWorld())) continue;
            viewer.sendEquipmentChange(body, items);
        }
    }

    /** Blocks taking or placing items on a sleeping body, including orphans left by an older version or a crash. */
    public boolean handleManipulate(PlayerArmorStandManipulateEvent event) {
        ArmorStand stand = event.getRightClicked();
        if (!bodyOwners.containsKey(stand.getUniqueId()) && core.getNbt(stand, TAG_PLACEHOLDER) == null) return false;
        event.setCancelled(true);
        return true;
    }

    public boolean handlePlaceholderDamage(EntityDamageEvent event) {
        UUID ownerId = bodyOwners.get(event.getEntity().getUniqueId());
        if (ownerId == null) {
            if (!isOrphan(event.getEntity())) return false;
            event.setCancelled(true);
            event.getEntity().remove();
            return true;
        }
        event.setCancelled(true);
        Player owner = Bukkit.getPlayer(ownerId);
        if (owner == null || !owner.isOnline()) return true;
        double damage = Math.max(0.0, event.getFinalDamage());
        // Wake up first: a player still in spectator mode ignores damage.
        end(owner, true);
        if (damage > 0.0) {
            if (event instanceof EntityDamageByEntityEvent byEntity) owner.damage(damage, byEntity.getDamager());
            else owner.damage(damage);
        }
        return true;
    }

    public void enforce(Player player) {
        Session session = active.get(player.getUniqueId());
        if (session == null) return;
        Entity target = player.getSpectatorTarget();
        if (target == null || !target.getUniqueId().equals(session.camera().getUniqueId())) {
            player.setSpectatorTarget(session.camera());
        }
    }

    public void end(Player player, boolean restorePosition) {
        Session session = active.remove(player.getUniqueId());
        if (session == null) return;
        try {
            // Disconnect may already report offline. Keep the recovery snapshot for the next join.
            if (player.isOnline()) {
                boolean restored = restore(player, Snapshot.of(session), restorePosition);
                if (restored) player.getPersistentDataContainer().remove(SESSION_KEY);
                for (Player other : Bukkit.getOnlinePlayers()) visibility.show(other, player);
            }
        } catch (RuntimeException failure) { core.high("Microsleep recovery deferred for " + player.getName() + ": " + failure.getMessage()); }
        finally { removeEntities(session); }
    }

    /** Restoration token remains until game mode, flight and any requested return teleport succeeded. */
    private boolean restore(Player player, Snapshot snapshot, boolean position) {
        try { player.setSpectatorTarget(null); } catch (RuntimeException ignored) { }
        player.setGameMode(snapshot.gameMode());
        player.setAllowFlight(snapshot.allowFlight());
        if (snapshot.allowFlight()) player.setFlying(snapshot.flying());
        if (!position) return true;
        World world = Bukkit.getWorld(snapshot.world());
        if (world == null) {
            core.high("Microsleep return world unavailable for " + player.getName() + "; recovery snapshot retained");
            return false;
        }
        return player.teleport(new Location(world, snapshot.x(), snapshot.y(), snapshot.z(), snapshot.yaw(), snapshot.pitch()));
    }

    /** Reconnect/crash repair. A cancelled return is retried on the next join, never every tick. */
    public boolean restoreAfterCrash(Player player) {
        if (active.containsKey(player.getUniqueId())) return false;
        var pdc = player.getPersistentDataContainer();
        String raw = pdc.get(SESSION_KEY, PersistentDataType.STRING);
        recoveryGrace(player, raw);
        if (raw == null) return false;
        Snapshot snapshot = Snapshot.decode(raw);
        try {
            if (snapshot == null) {
                try { player.setSpectatorTarget(null); } catch (RuntimeException ignored) { }
                if (player.getGameMode() == GameMode.SPECTATOR) {
                    player.setGameMode(GameMode.SURVIVAL); player.setAllowFlight(false);
                }
                pdc.remove(SESSION_KEY);
                core.high("Corrupt microsleep session of " + player.getName() + " reset to survival");
                return true;
            }
            boolean restored = restore(player, snapshot, true);
            for (UUID id : List.of(snapshot.body(), snapshot.camera())) {
                Entity entity = Bukkit.getEntity(id);
                if (entity != null) entity.remove();
            }
            if (restored) {
                pdc.remove(SESSION_KEY);
                core.mid("Restored " + player.getName() + " from an interrupted microsleep");
            }
        } catch (RuntimeException failure) { core.high("Microsleep recovery deferred for " + player.getName() + ": " + failure.getMessage()); }
        return true;
    }

    /** Persisted form of a running session; world by UUID so renamed worlds still resolve. */
    record Snapshot(GameMode gameMode, boolean allowFlight, boolean flying, UUID world, double x, double y, double z,
                    float yaw, float pitch, UUID body, UUID camera) {
        private static final String VERSION = "1";
        Snapshot {
            if ((gameMode != GameMode.SURVIVAL && gameMode != GameMode.ADVENTURE) || world == null || body == null || camera == null
                    || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || !Float.isFinite(yaw) || !Float.isFinite(pitch)
                    || Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000 || Math.abs(y) > 30_000_000)
                throw new IllegalArgumentException("Invalid microsleep snapshot");
        }

        static Snapshot of(Session session) {
            Location at = session.location();
            return new Snapshot(session.gameMode(), session.allowFlight(), session.flying(), at.getWorld().getUID(),
                    at.getX(), at.getY(), at.getZ(), at.getYaw(), at.getPitch(),
                    session.body().getUniqueId(), session.camera().getUniqueId());
        }

        String encode() {
            return String.join(";", VERSION, gameMode.name(), Boolean.toString(allowFlight), Boolean.toString(flying),
                    world.toString(), Double.toString(x), Double.toString(y), Double.toString(z),
                    Float.toString(yaw), Float.toString(pitch), body.toString(), camera.toString());
        }

        static Snapshot decode(String raw) {
            String[] parts = raw.split(";");
            if (parts.length != 12 || !VERSION.equals(parts[0])) return null;
            try {
                return new Snapshot(GameMode.valueOf(parts[1]), Boolean.parseBoolean(parts[2]), Boolean.parseBoolean(parts[3]),
                        UUID.fromString(parts[4]), Double.parseDouble(parts[5]), Double.parseDouble(parts[6]),
                        Double.parseDouble(parts[7]), Float.parseFloat(parts[8]), Float.parseFloat(parts[9]),
                        UUID.fromString(parts[10]), UUID.fromString(parts[11]));
            } catch (IllegalArgumentException invalid) {
                return null;
            }
        }
    }

    private void removeEntities(Session session) {
        if (session.body().isValid()) session.body().remove();
        if (session.camera().isValid()) session.camera().remove();
        bodyOwners.remove(session.body().getUniqueId());
    }

    public void shutdown() {
        for (UUID uuid : active.keySet().toArray(UUID[]::new)) {
            Player player = Bukkit.getPlayer(uuid);
            Session session = active.get(uuid);
            if (player != null) end(player, true);
            else if (session != null) removeEntities(session);
        }
        active.clear();
        bodyOwners.clear();
        nextCheck.clear();
        if (tasks != null) tasks.close();
    }
}
