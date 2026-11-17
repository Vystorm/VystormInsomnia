package at.vystorm.insomnia.service;

import at.vystorm.insomnia.config.InsomniaConfig;
import at.vystorm.insomnia.core.CoreBridge;
import at.vystorm.insomnia.model.PlayerFatigueState;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

public final class StimulantService {
    public record Definition(String id, Material material, String nbtKey, String nbtValue,
                             double fatigueReduction, long durationMillis, double toleranceStep,
                             double crashFatigue, int maxUses) {}

    private final InsomniaConfig config;
    private final CoreBridge core;
    private final FatigueService fatigue;
    private List<Definition> definitions = List.of();
    private List<String> warnings = List.of();

    public StimulantService(InsomniaConfig config, CoreBridge core, FatigueService fatigue) {
        this.config = config;
        this.core = core;
        this.fatigue = fatigue;
        reload();
    }

    public void reload() {
        ConfigurationSection root = config.stimulants();
        List<Definition> loaded = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        if (root != null) {
            for (String id : root.getKeys(false)) {
                ConfigurationSection section = root.getConfigurationSection(id);
                if (section == null) {
                    problems.add("stimulants.yml: '" + id + "' is not a section and was skipped");
                    continue;
                }
                Material material = Material.matchMaterial(section.getString("material", ""));
                if (material == null) {
                    problems.add("stimulants.yml: '" + id + "' has unknown material '" + section.getString("material", "") + "' and was skipped");
                    continue;
                }
                loaded.add(new Definition(
                        id,
                        material,
                        blankToNull(section.getString("nbt_key")),
                        blankToNull(section.getString("nbt_value")),
                        Math.max(0.0, section.getDouble("fatigue_reduction", 0.0)),
                        Math.max(1L, section.getLong("duration_seconds", 60L)) * 1000L,
                        Math.max(0.0, section.getDouble("tolerance_step", 0.25)),
                        Math.max(0.0, section.getDouble("crash_fatigue", 0.0)),
                        Math.max(1, section.getInt("max_effective_uses_before_sleep", 4))));
            }
        }
        definitions = List.copyOf(loaded);
        warnings = List.copyOf(problems);
    }

    /** Entries skipped by the last reload, for the log. */
    public List<String> warnings() { return warnings; }

    public boolean consume(Player player, ItemStack item, long now) {
        if (!config.stimulantsEnabled() || item == null) return false;
        Definition definition = match(item);
        if (definition == null || !fatigue.ready(player.getUniqueId())) return false;

        PlayerFatigueState state = fatigue.require(player.getUniqueId());
        int uses = state.stimulantUses();
        double effectiveness = Math.max(0.0, 1.0 - uses * definition.toleranceStep());
        if (uses >= definition.maxUses()) effectiveness = 0.0;
        double suppression = definition.fatigueReduction() * effectiveness;

        state.stimulantUses(uses + 1);
        state.stimulantSuppression(Math.min(100.0, state.stimulantSuppression() + suppression));
        state.stimulantCrashPending(state.stimulantCrashPending() + definition.crashFatigue() * Math.max(0.25, effectiveness));
        state.stimulantUntilEpochMillis(Math.max(state.stimulantUntilEpochMillis(), now + definition.durationMillis()));
        fatigue.refreshStage(player.getUniqueId());
        fatigue.markHistory(player.getUniqueId(), now, "stimulant:" + definition.id());
        player.sendMessage(config.message(player, effectiveness >= 0.75 ? "stimulant" : "stimulant_weak"));
        return true;
    }

    public void tick(long now) {
        for (PlayerFatigueState state : fatigue.states()) {
            if (state.stimulantUntilEpochMillis() <= 0L || now < state.stimulantUntilEpochMillis()) continue;
            boolean hadEffect = state.stimulantSuppression() > 0.0 || state.stimulantCrashPending() > 0.0;
            double crash = state.stimulantCrashPending();
            state.stimulantSuppression(0.0);
            state.stimulantUntilEpochMillis(0L);
            state.stimulantCrashPending(0.0);
            if (crash > 0.0) state.addFatigue(crash);
            fatigue.refreshStage(state.playerUuid());
            Player player = org.bukkit.Bukkit.getPlayer(state.playerUuid());
            if (hadEffect && player != null) player.sendMessage(config.message(player, "stimulant_crash"));
        }
    }

    public void resetAfterDeepSleep(PlayerFatigueState state) {
        state.resetStimulants();
    }

    private Definition match(ItemStack item) {
        for (Definition definition : definitions) {
            if (item.getType() != definition.material()) continue;
            if (definition.nbtKey() == null) return definition;
            String actual = core.getNbt(item, definition.nbtKey());
            if (definition.nbtValue() == null ? actual != null : definition.nbtValue().equalsIgnoreCase(actual)) return definition;
        }
        return null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
