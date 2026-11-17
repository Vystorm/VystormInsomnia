package at.vystorm.insomnia.service;

import at.vystorm.insomnia.config.InsomniaConfig;
import at.vystorm.insomnia.model.PlayerFatigueState;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/** Gentle reminders about debt, which coffee and campfire rest cannot remove. Uses the master runtime task. */
public final class SleepDebtReminderService {
    public static final String PREFERENCE = "sleep_debt_reminders";
    private final InsomniaConfig config;
    private final FatigueService fatigue;
    private final Predicate<Player> optedIn;
    private final Predicate<UUID> busy;
    private final Map<UUID, Long> lastReminder = new HashMap<>();

    public SleepDebtReminderService(InsomniaConfig config, FatigueService fatigue,
                                    Predicate<Player> optedIn, Predicate<UUID> busy) {
        this.config = config;
        this.fatigue = fatigue;
        this.optedIn = optedIn;
        this.busy = busy;
    }

    public void tick(long now) {
        var online = new HashSet<UUID>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            online.add(id);
            if (!config.debtReminderEnabled() || !config.fatigueEnabled()) continue;
            PlayerFatigueState state = fatigue.state(id).orElse(null);
            if (state == null || state.sleepDebt() < config.debtReminderThreshold()) continue;
            if (player.isDead() || player.isSleeping() || fatigue.isSleeping(id) || busy.test(id)
                    || fatigue.pausedReason(player, now) != null || !player.hasPermission("insomnia.use")
                    || !optedIn.test(player)) continue;
            Long last = lastReminder.get(id);
            if (last != null && now - last < config.debtReminderIntervalMillis()) continue;
            player.sendMessage(config.message(player, "debt_reminder.message", "debt", Math.round(state.sleepDebt())));
            lastReminder.put(id, now);
        }
        // Keep cooldowns through recovery, opt-out and reload to avoid reminders around a fluctuating threshold.
        lastReminder.keySet().retainAll(online);
    }
}
