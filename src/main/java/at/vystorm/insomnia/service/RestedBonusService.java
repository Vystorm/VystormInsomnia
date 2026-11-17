package at.vystorm.insomnia.service;

import at.vystorm.insomnia.config.InsomniaConfig;
import at.vystorm.insomnia.model.PlayerFatigueState;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;

import java.util.UUID;

public final class RestedBonusService {
    private final InsomniaConfig config;
    private final FatigueService fatigue;
    private final ActivityService activity;
    private long nextRegenAt;

    public RestedBonusService(InsomniaConfig config, FatigueService fatigue, ActivityService activity) {
        this.config = config;
        this.fatigue = fatigue;
        this.activity = activity;
    }

    public void grant(Player player, long now) {
        if (!config.restedEnabled() || !fatigue.ready(player.getUniqueId())) return;
        fatigue.require(player.getUniqueId()).restedUntilEpochMillis(now + config.restedDurationMillis());
    }

    public boolean active(UUID player, long now) {
        return config.restedEnabled() && fatigue.state(player)
                .map(state -> state.restedUntilEpochMillis() > now)
                .orElse(false);
    }

    public void applyXp(PlayerExpChangeEvent event, long now) {
        if (event.getAmount() <= 0 || !active(event.getPlayer().getUniqueId(), now)) return;
        int adjusted = Math.max(event.getAmount(), (int) Math.round(event.getAmount() * config.restedXpMultiplier()));
        event.setAmount(adjusted);
    }

    public void applyHunger(FoodLevelChangeEvent event, long now) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (!active(player.getUniqueId(), now)) return;
        int current = player.getFoodLevel();
        if (event.getFoodLevel() >= current) return;
        int loss = current - event.getFoodLevel();
        double keepFraction = Math.max(0.0, Math.min(1.0, config.restedHungerReduction()));
        double expectedLoss = loss * (1.0 - keepFraction);
        int adjustedLoss = (int) Math.floor(expectedLoss);
        // Vanilla normally removes one point at a time; always rounding down would cancel every loss.
        if (java.util.concurrent.ThreadLocalRandom.current().nextDouble() < expectedLoss - adjustedLoss) adjustedLoss++;
        event.setFoodLevel(Math.max(0, current - adjustedLoss));
    }

    public void tick(long now) {
        if (!config.restedEnabled() || now < nextRegenAt) return;
        nextRegenAt = now + 5000L;
        double amount = config.restedRegenPerFiveSeconds();
        if (amount <= 0) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!active(player.getUniqueId(), now) || activity.inCombat(player.getUniqueId(), now)) continue;
            if (player.isDead() || player.getHealth() <= 0.0) continue;
            double max = player.getMaxHealth();
            if (player.getHealth() < max) player.setHealth(Math.min(max, player.getHealth() + amount));
        }
    }

    /** Whole minutes of rested bonus left (rounded up), 0 = none. */
    public long remainingMinutes(UUID player, long now) {
        PlayerFatigueState state = fatigue.state(player).orElse(null);
        if (state == null || state.restedUntilEpochMillis() <= now) return 0L;
        return Math.max(1L, (state.restedUntilEpochMillis() - now + 59_999L) / 60_000L);
    }

    /** German short form ("nein" / "N min"); player-facing code uses {@code Messages.restedLeft}. */
    public String remaining(UUID player, long now) {
        long minutes = remainingMinutes(player, now);
        return minutes <= 0 ? "nein" : minutes + " min";
    }
}
