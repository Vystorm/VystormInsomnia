package at.vystorm.insomnia.sleep;

import Vystorm.vystorm_core.schedule.TaskGroup;
import Vystorm.vystorm_core.worlds.ManagedWorlds;
import at.vystorm.insomnia.config.InsomniaConfig;
import at.vystorm.insomnia.ui.Messages;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerBedLeaveEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.TimeSkipEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Multiplayer sleep: instead of the vanilla instant night skip the clock runs faster the more players of a world
 * sleep ({@link SleepSpeed}). Each sleeper has a wake-up target ({@link WakeTimes}); the clock runs towards the
 * earliest one, every sleeper wakes at their own.
 * <p>
 * Vanilla's skip is stopped by cancelling its {@link TimeSkipEvent} ({@code NIGHT_SKIP}); Paper then also does not
 * wake the sleepers. No gamerule is changed, so there is nothing to restore after a reload or crash: raising
 * {@code players_sleeping_percentage} above 100 would make vanilla tell every sleeper "No amount of rest can pass
 * this night". A world whose percentage is already above 100 (skipping switched off by the admin) is left alone,
 * and with {@code advance_time} off nothing is accelerated. One timer per world runs only while someone in it
 * sleeps. Server thread only.
 */
public final class SleepAccelerator implements Listener {
    private final InsomniaConfig config;
    private final Messages texts;
    private final Vystorm.vystorm_core.API api;
    private final TaskGroup tasks;
    private final Map<UUID, Sleeper> sleepers = new HashMap<>();
    private final Map<UUID, Run> runs = new HashMap<>();

    private static final class Sleeper {
        final UUID world;
        int clock;
        long target;
        Sleeper(UUID world, int clock, long target) { this.world = world; this.clock = clock; this.target = target; }
    }

    private static final class Run {
        final SleepSpeed.Carry carry = new SleepSpeed.Carry();
        BukkitTask task;
        long lastTime;
        int ticks;
    }

    public SleepAccelerator(Plugin plugin, Vystorm.vystorm_core.API api, InsomniaConfig config, Messages texts) {
        this.api = api;
        this.config = config;
        this.texts = texts;
        this.tasks = api.tasks().of(plugin, "sleep-acceleration");
    }

    /** An admin who set {@code players_sleeping_percentage} above 100 does not want nights to pass by sleeping. */
    static boolean skippingAllowed(Integer percentage) {
        return percentage == null || percentage <= 100;
    }

    /** Time acceleration runs in this world (players may pick a wake-up time). */
    public boolean active(World world) {
        if (world == null || !config.accelerationEnabled() || world.getEnvironment() != World.Environment.NORMAL) return false;
        try { if (api.worldGenerator().worlds().managed(world)) return false; } catch (RuntimeException ignored) { return false; }
        List<String> listed = config.accelerationWorlds();
        if (!listed.isEmpty() && listed.stream().noneMatch(name -> name.equalsIgnoreCase(world.getName()))) return false;
        return skippingAllowed(world.getGameRuleValue(GameRule.PLAYERS_SLEEPING_PERCENTAGE));
    }

    /** After a reload: stop worlds where acceleration no longer runs (their sleepers sleep on as in vanilla). */
    public void apply() {
        for (World world : Bukkit.getWorlds()) if (!active(world)) release(world.getUID());
    }

    private void release(UUID world) {
        stopRun(world);
        sleepers.values().removeIf(sleeper -> sleeper.world.equals(world));
    }
    /**
     * Lets the player sleep until {@code clockTicks} (ticks after 06:00) of the coming day.
     *
     * @return the absolute wake-up time, or empty when acceleration does not run in the player's world
     */
    public OptionalLong sleepUntil(Player player, int clockTicks) {
        World world = player.getWorld();
        if (!active(world)) return OptionalLong.empty();
        long target = WakeTimes.target(world.getFullTime(), clockTicks);
        sleepers.put(player.getUniqueId(), new Sleeper(world.getUID(), clockTicks, target));
        Run run = runs.get(world.getUID());
        if (run == null) {
            Run created = new Run();
            created.lastTime = world.getFullTime();
            UUID id = world.getUID();
            created.task = tasks.timer(1L, 1L, () -> tick(id));
            runs.put(id, created);
        }
        return OptionalLong.of(target);
    }

    public OptionalLong target(UUID player) {
        Sleeper sleeper = sleepers.get(player);
        return sleeper == null ? OptionalLong.empty() : OptionalLong.of(sleeper.target);
    }

    public void forget(UUID player) { sleepers.remove(player); }

    private void tick(UUID worldId) {
        World world = Bukkit.getWorld(worldId);
        Run run = runs.get(worldId);
        if (world == null || run == null || !active(world)) { stopRun(worldId); return; }
        long now = world.getFullTime();
        if (now < run.lastTime) {
            // Another plugin or /time turned the clock back: count the chosen times from the new time.
            for (Sleeper sleeper : sleepers.values())
                if (sleeper.world.equals(worldId)) sleeper.target = WakeTimes.target(now, sleeper.clock);
        } else if (Math.floorDiv(now, WakeTimes.DAY) > Math.floorDiv(run.lastTime, WakeTimes.DAY)) {
            nightPassed(world);
        }
        run.lastTime = now;

        int sleeping = 0;
        long earliest = Long.MAX_VALUE;
        List<Player> due = new ArrayList<>();
        for (var iterator = sleepers.entrySet().iterator(); iterator.hasNext(); ) {
            var entry = iterator.next();
            Sleeper sleeper = entry.getValue();
            if (!sleeper.world.equals(worldId)) continue;
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline() || !player.getWorld().equals(world) || !player.isSleeping()) {
                iterator.remove();
                continue;
            }
            if (now >= sleeper.target) {
                due.add(player); // removed and told by onBedLeave
                continue;
            }
            if (player.getGameMode() != GameMode.SPECTATOR && !player.isSleepingIgnored()) sleeping++;
            earliest = Math.min(earliest, sleeper.target);
        }
        for (Player player : due) {
            player.wakeup(false);
            sleepers.remove(player.getUniqueId());
        }
        if (earliest == Long.MAX_VALUE) { stopRun(worldId); return; }

        int players = Math.max(sleeping, countedPlayers(world));
        Boolean advance = world.getGameRuleValue(api.compat().gameplay().booleanGameRule("DO_DAYLIGHT_CYCLE", "ADVANCE_TIME"));
        double speed = Boolean.FALSE.equals(advance) ? 1.0 : SleepSpeed.speed(sleeping, players,
                config.accelerationMinSpeed(), config.accelerationMaxSpeed(), config.accelerationCurve());
        long extra = run.carry.extra(speed, earliest - now - 1);
        if (extra > 0) {
            try {
                world.setFullTime(now + extra);
            } catch (IllegalArgumentException noClock) {
                stopRun(worldId);
                return;
            }
            long after = world.getFullTime();
            if (Math.floorDiv(after, WakeTimes.DAY) > Math.floorDiv(now, WakeTimes.DAY)) nightPassed(world);
            run.lastTime = after;
        }
        if (config.accelerationActionBar() && speed > 1.0 && run.ticks++ % 20 == 0) {
            for (Player viewer : world.getPlayers()) {
                api.compat().platform().sendActionBar(viewer, texts.component(viewer, "sleep_choice.action_bar", "sleeping", sleeping,
                        "players", players, "speed", SleepSpeed.label(speed, locale(viewer))));
            }
        }
    }

    private Locale locale(Player player) {
        try { return api.compat().platform().locale(player); } catch (RuntimeException unknown) { return Locale.ROOT; }
    }

    /** Players who would have to sleep: not spectators and not ignored for sleeping (AFK plugins set that). */
    private static int countedPlayers(World world) {
        int count = 0;
        for (Player player : world.getPlayers())
            if (player.getGameMode() != GameMode.SPECTATOR && !player.isSleepingIgnored()) count++;
        return count;
    }

    /** The morning came while players slept: clear the weather as the vanilla night skip does. */
    private void nightPassed(World world) {
        if (!world.hasStorm() && !world.isThundering()) return;
        Boolean weather = world.getGameRuleValue(api.compat().gameplay().booleanGameRule("DO_WEATHER_CYCLE", "ADVANCE_WEATHER"));
        if (Boolean.FALSE.equals(weather)) return;
        world.setStorm(false);
        world.setWeatherDuration(0);
        world.setThundering(false);
        world.setThunderDuration(0);
    }

    private void stopRun(UUID worldId) {
        Run run = runs.remove(worldId);
        if (run != null && run.task != null) run.task.cancel();
    }

    /** Vanilla's daylight wake would end a sleep that has a later wake-up time; keep the player in bed. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDaylightWake(PlayerBedLeaveEvent event) {
        Player player = event.getPlayer();
        Sleeper sleeper = sleepers.get(player.getUniqueId());
        if (sleeper == null || player.isDead() || !player.getWorld().getUID().equals(sleeper.world)) return;
        if (player.getWorld().getFullTime() < sleeper.target && active(player.getWorld())
                && at.vystorm.insomnia.compat.paper26.BedLeaveOrigin.automaticDaylightWake()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBedLeave(PlayerBedLeaveEvent event) {
        Player player = event.getPlayer();
        Sleeper sleeper = sleepers.remove(player.getUniqueId());
        // Reached the chosen time: woken here or, on the same tick, by vanilla daylight.
        if (sleeper != null && !player.isDead() && player.getWorld().getFullTime() >= sleeper.target)
            player.sendMessage(texts.chat(player, "sleep_choice.woke", "time", WakeTimes.clock(player.getWorld().getFullTime())));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) { forget(event.getPlayer().getUniqueId()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) { forget(event.getPlayer().getUniqueId()); }

    /** Vanilla would skip to the morning at once when enough players sleep; the clock speeds up instead. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onNightSkip(TimeSkipEvent event) {
        if (SkipReasons.isNightSkip(event) && active(event.getWorld())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldUnload(WorldUnloadEvent event) { release(event.getWorld().getUID()); }

    /** Plugin disable: stops every timer; sleeping players simply sleep on. */
    public void shutdown() {
        for (UUID id : List.copyOf(runs.keySet())) stopRun(id);
        tasks.close();
        sleepers.clear();
    }
}
