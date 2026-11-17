package at.vystorm.insomnia.service;

import at.vystorm.insomnia.config.InsomniaConfig;
import at.vystorm.insomnia.model.PlayerFatigueState;
import at.vystorm.insomnia.ui.Messages;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Lightable;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Resting by a lit campfire ({@code rest.campfire.*}): a player who stands still next to one slowly loses fatigue,
 * never below {@code min_fatigue}. No sleep debt recovery and no rested bonus, so a bed stays the real cure. Runs in
 * the master runtime tick once per second; the block scan only happens for players who already stand still.
 */
public final class CampfireRestService {
    private final InsomniaConfig config;
    private final FatigueService fatigue;
    private final SleepService sleep;
    private final ActivityService activity;
    private final Vystorm.vystorm_core.compat.PlatformCompatInterface platform;
    private final RestTracker tracker = new RestTracker();
    /** Players that must not rest right now (microsleep body); set by the plugin. */
    private Predicate<UUID> busy = ignored -> false;
    private long nextTickAt;

    /** @param platform Core's platform part, {@code api.compat().platform()} */
    public CampfireRestService(InsomniaConfig config, FatigueService fatigue, SleepService sleep, ActivityService activity,
                               Vystorm.vystorm_core.compat.PlatformCompatInterface platform) {
        this.platform = platform;
        this.config = config;
        this.fatigue = fatigue;
        this.sleep = sleep;
        this.activity = activity;
    }

    public void busyWhen(Predicate<UUID> busy) { this.busy = busy == null ? ignored -> false : busy; }

    public boolean resting(UUID player) { return tracker.resting(player); }

    public void tick(long now) {
        if (now < nextTickAt) return;
        // The runtime timer runs every 20 ticks, so a tick can come a few milliseconds early; do not skip it.
        nextTickAt = now + 900L;
        if (!config.campfireRestEnabled()) {
            tracker.clear();
            return;
        }
        List<UUID> online = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            online.add(player.getUniqueId());
            tick(player, now);
        }
        tracker.retain(online);
    }

    private void tick(Player player, long now) {
        UUID id = player.getUniqueId();
        Location at = player.getLocation();
        World world = at.getWorld();
        if (world == null) return;
        PlayerFatigueState state = fatigue.state(id).orElse(null);
        double floor = config.campfireMinFatigue();
        boolean eligible = state != null && state.fatigue() > floor && eligible(player, now);
        RestTracker.Step step = tracker.update(id, world.getUID(), at.getX(), at.getY(), at.getZ(), now, eligible,
                config.campfireSettleMillis(), () -> campfireNear(at, config.campfireRadius()));
        switch (step) {
            case STARTED -> {
                send(player, "rest.started", "floor", fmt(floor));
                fatigue.markHistory(id, now, "rest_started");
            }
            case RESTING -> recover(player, state, floor, tracker.elapsedSeconds(id, now));
            case STOPPED -> actionBar(player, "rest.stopped");
            case IDLE -> { }
        }
    }

    private boolean eligible(Player player, long now) {
        GameMode mode = player.getGameMode();
        if (mode != GameMode.SURVIVAL && mode != GameMode.ADVENTURE) return false;
        if (player.isDead() || player.isSleeping()) return false;
        UUID id = player.getUniqueId();
        return !sleep.isSleeping(id) && !busy.test(id) && !activity.inCombat(id, now);
    }

    private void recover(Player player, PlayerFatigueState state, double floor, double seconds) {
        if (state == null) return;
        double before = state.fatigue();
        double after = RestTracker.recovered(before, floor, config.campfireFatiguePerSecond(), seconds);
        if (after < before) fatigue.setFatigue(player.getUniqueId(), state.rawFatigue() - (before - after));
        if (after <= floor) {
            tracker.complete(player.getUniqueId());
            send(player, "rest.floor", "floor", fmt(floor));
            return;
        }
        actionBar(player, "rest.bar", "fatigue", fmt(after), "floor", fmt(floor));
    }

    /** A lit campfire or soul campfire within {@code radius} blocks (two below to one above the feet). */
    static boolean campfireNear(Location at, int radius) {
        World world = at.getWorld();
        if (world == null) return false;
        int bx = at.getBlockX(), by = at.getBlockY(), bz = at.getBlockZ();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -2; dy <= 1; dy++) {
                    Block block = world.getBlockAt(bx + dx, by + dy, bz + dz);
                    if (litCampfire(block.getType(), block.getBlockData())) return true;
                }
            }
        }
        return false;
    }

    static boolean litCampfire(Material type, Object data) {
        return (type == Material.CAMPFIRE || type == Material.SOUL_CAMPFIRE) && data instanceof Lightable lightable && lightable.isLit();
    }

    private void send(Player player, String key, Object... placeholders) {
        player.sendMessage(config.message(player, key, placeholders));
    }

    private void actionBar(Player player, String key, Object... placeholders) {
        Messages texts = config.messages();
        if (texts != null) platform.sendActionBar(player, texts.component(player, key, placeholders));
    }

    private static String fmt(double value) { return String.format(Locale.ROOT, "%.0f", value); }
}
