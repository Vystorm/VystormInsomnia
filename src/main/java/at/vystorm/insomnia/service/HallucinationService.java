package at.vystorm.insomnia.service;

import at.vystorm.insomnia.api.InsomniaStage;
import at.vystorm.insomnia.config.InsomniaConfig;
import Vystorm.vystorm_core.schedule.TaskGroup;
import at.vystorm.insomnia.core.CoreBridge;
import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

public final class HallucinationService {
    private static final List<String> EFFECTS = List.of(
            "yawn", "footsteps", "creeper", "door", "darkness", "blindness", "shadow", "sight_loss");

    private final JavaPlugin plugin;
    private final InsomniaConfig config;
    private final CoreBridge core;
    private final FatigueService fatigue;
    private final SleepService sleep;
    private final Random random = new Random();
    private final Map<UUID, Long> nextCheck = new HashMap<>();
    /** One entry per running sight_loss, so each one releases exactly what it hid. */
    private final Map<UUID, List<Set<UUID>>> hiddenPlayers = new HashMap<>();
    private final VisibilityLocks visibility;
    /** Pending sight-loss releases; closed on shutdown (visibility is restored there directly). */
    private final TaskGroup tasks;

    public HallucinationService(JavaPlugin plugin, InsomniaConfig config, CoreBridge core,
                                FatigueService fatigue, SleepService sleep, VisibilityLocks visibility) {
        this.visibility = visibility;
        this.plugin = plugin;
        this.config = config;
        this.core = core;
        this.fatigue = fatigue;
        this.sleep = sleep;
        this.tasks = core.api().tasks().of(plugin, "hallucinations");
    }

    public void tick(long now) {
        if (!config.hallucinationsEnabled()) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (sleep.isSleeping(player.getUniqueId())) continue;
            long due = nextCheck.getOrDefault(player.getUniqueId(), 0L);
            if (now < due) continue;
            nextCheck.put(player.getUniqueId(), now + config.hallucinationCheckMillis());
            InsomniaStage stage = fatigue.stage(player.getUniqueId());
            double chance = switch (stage) {
                case EXHAUSTED -> config.hallucinationChance("chance_exhausted", 0.05);
                case OVERMUEDET -> config.hallucinationChance("chance_overmuedet", 0.18);
                case SLEEP_DEPRIVATION -> config.hallucinationChance("chance_sleep_deprivation", 0.35);
                default -> 0.0;
            };
            if (random.nextDouble() <= chance) {
                String effect = weightedPick();
                apply(player, effect);
                fatigue.markHistory(player.getUniqueId(), now, "hallucination:" + effect + ":" + stage.name().toLowerCase(java.util.Locale.ROOT)
                        + ":fatigue=" + String.format(java.util.Locale.ROOT, "%.1f", fatigue.fatigue(player.getUniqueId()))
                        + ":debt=" + String.format(java.util.Locale.ROOT, "%.1f", fatigue.sleepDebt(player.getUniqueId())));
            }
        }
    }

    public boolean force(Player player, String effect) {
        String normalized = effect == null ? "" : effect.toLowerCase();
        if (!EFFECTS.contains(normalized)) return false;
        apply(player, normalized);
        return true;
    }

    public List<String> effects() { return EFFECTS; }

    private void apply(Player player, String effect) {
        switch (effect) {
            case "yawn" -> player.playSound(player.getLocation(), config.yawnSound(), SoundCategory.PLAYERS, 0.8f, 0.95f);
            case "footsteps" -> player.playSound(player.getLocation(), "minecraft:entity.zombie.step", SoundCategory.HOSTILE, 0.7f, 0.75f);
            case "creeper" -> player.playSound(player.getLocation(), "minecraft:entity.creeper.primed", SoundCategory.HOSTILE, 0.9f, 1.0f);
            case "door" -> player.playSound(player.getLocation(), "minecraft:block.wooden_door.close", SoundCategory.BLOCKS, 0.7f, 0.8f);
            case "darkness" -> player.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 60, 0, false, false, false));
            case "blindness" -> player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 40, 0, false, false, false));
            case "shadow" -> {
                var location = player.getLocation().clone().add(
                        random.nextDouble(-2.5, 2.5), random.nextDouble(0.3, 1.8), random.nextDouble(-2.5, 2.5));
                core.particle(player, Particle.SMOKE, location, 10, null);
            }
            case "sight_loss" -> hideOthersTemporarily(player);
            default -> { }
        }
    }

    private String weightedPick() {
        List<String> pool = new ArrayList<>();
        for (String effect : EFFECTS) {
            int weight = Math.max(0, config.hallucinationWeight(effect, 1));
            for (int i = 0; i < weight; i++) pool.add(effect);
        }
        return pool.isEmpty() ? "yawn" : pool.get(random.nextInt(pool.size()));
    }

    private void hideOthersTemporarily(Player viewer) {
        Set<UUID> hidden = new HashSet<>();
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.getUniqueId().equals(viewer.getUniqueId())) continue;
            visibility.hide(viewer, other);
            hidden.add(other.getUniqueId());
        }
        hiddenPlayers.computeIfAbsent(viewer.getUniqueId(), ignored -> new ArrayList<>()).add(hidden);
        tasks.later(config.sightLossTicks(), () -> release(viewer.getUniqueId(), hidden));
    }

    /** Ends one sight_loss; an overlapping one keeps its own targets hidden until it ends. */
    void release(UUID viewerId, Set<UUID> hidden) {
        List<Set<UUID>> running = hiddenPlayers.get(viewerId);
        if (running == null || !running.removeIf(entry -> entry == hidden)) return;
        if (running.isEmpty()) hiddenPlayers.remove(viewerId);
        show(viewerId, hidden);
    }

    public void restoreVisibility(UUID viewerId) {
        nextCheck.remove(viewerId);
        List<Set<UUID>> running = hiddenPlayers.remove(viewerId);
        if (running == null) return;
        for (Set<UUID> hidden : running) show(viewerId, hidden);
    }

    private void show(UUID viewerId, Set<UUID> hidden) {
        Player viewer = Bukkit.getPlayer(viewerId);
        if (viewer == null) return;
        for (UUID otherId : hidden) {
            Player other = Bukkit.getPlayer(otherId);
            if (other != null) visibility.show(viewer, other);
        }
    }

    public void shutdown() {
        tasks.close();
        for (UUID viewer : hiddenPlayers.keySet().toArray(UUID[]::new)) restoreVisibility(viewer);
        hiddenPlayers.clear();
        nextCheck.clear();
    }
}
