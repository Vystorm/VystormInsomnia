package at.vystorm.insomnia.config;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;
import at.vystorm.insomnia.ui.Messages;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Snapshot of config.yml, stimulants.yml and mobs.yml (texts live in the Core language bundle, see {@link Messages}). {@link #reload()} loads and validates new
 * copies first and swaps them in only when everything is valid, so a broken edit never becomes half active.
 */
public final class InsomniaConfig {
    private final JavaPlugin plugin;
    private FileConfiguration main;
    private Messages texts;
    private YamlConfiguration stimulants;
    private YamlConfiguration mobs;

    public InsomniaConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        ensureResource("stimulants.yml");
        ensureResource("mobs.yml");
        reload();
    }

    /** Version 2 slows fatigue and sleep debt to a quarter, so every stage lasts four times as long. */
    public static final int CONFIG_VERSION = 2;
    static final double SLOWDOWN = 4.0;

    /**
     * Loads all files strictly (YAML syntax errors fail), validates them and only then makes them active.
     *
     * @throws IllegalArgumentException with every problem found; the previous configuration stays active
     */
    public void reload() {
        File file = new File(plugin.getDataFolder(), "config.yml");
        YamlConfiguration candidate = strictLoad(file);
        YamlConfiguration defaults = bundledDefaults();
        if (defaults != null) candidate.setDefaults(defaults);
        boolean migrated = migrate(candidate);
        YamlConfiguration newStimulants = strictLoad(new File(plugin.getDataFolder(), "stimulants.yml"));
        YamlConfiguration newMobs = strictLoad(new File(plugin.getDataFolder(), "mobs.yml"));

        List<String> errors = validate(candidate);
        validateFiniteNumbers(newStimulants, "stimulants.yml: ", errors);
        validateFiniteNumbers(newMobs, "mobs.yml: ", errors);
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("; ", errors));

        if (migrated) {
            try {
                candidate.save(file);
                DebugMessages.mid("[VystormInsomnia/InsomniaConfig] configuration updated to version " + CONFIG_VERSION + ".");
            } catch (IOException failure) {
                DebugMessages.high("[VystormInsomnia/InsomniaConfig] Could not save the migrated configuration: " + failure.getMessage());
            }
        }
        plugin.reloadConfig(); // keeps getConfig() (Core settings dialog) in step with the file
        main = candidate;
        stimulants = newStimulants;
        mobs = newMobs;
    }

    private static YamlConfiguration strictLoad(File file) {
        YamlConfiguration yaml = new YamlConfiguration();
        if (!file.isFile()) return yaml;
        try {
            yaml.load(file);
        } catch (InvalidConfigurationException failure) {
            // line and column of the YAML error; the file itself is never changed (0.31.1)
            throw new IllegalArgumentException(file.getName() + " has a YAML error at " + YamlCheck.describe(failure));
        } catch (IOException failure) {
            throw new IllegalArgumentException(file.getName() + " cannot be read: " + firstLine(failure.getMessage()));
        }
        return yaml;
    }

    private static String firstLine(String text) {
        if (text == null) return "unknown error";
        int newline = text.indexOf('\n');
        return newline < 0 ? text : text.substring(0, newline);
    }

    private YamlConfiguration bundledDefaults() {
        InputStream stream = plugin.getResource("config.yml");
        if (stream == null) return null;
        return YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
    }

    /** Rescales stored rates once; returns true when the configuration was changed. */
    static boolean migrate(ConfigurationSection config) {
        boolean changed = false;
        for (var entry : java.util.Map.<String, Object>of(
                "debt_reminder.enabled", true, "debt_reminder.threshold", 50.0,
                "debt_reminder.interval_minutes", 15).entrySet()) {
            if (!config.contains(entry.getKey(), true)) {
                config.set(entry.getKey(), entry.getValue());
                changed = true;
            }
        }
        if (config.getInt("config_version", 1) >= CONFIG_VERSION) return changed;
        for (String key : List.of("fatigue.base_per_minute", "fatigue.debt_per_minute")) {
            if (config.isSet(key)) config.set(key, config.getDouble(key) / SLOWDOWN);
        }
        config.set("config_version", CONFIG_VERSION);
        return true;
    }

    private void ensureResource(String name) {
        File target = new File(plugin.getDataFolder(), name);
        if (!target.exists()) plugin.saveResource(name, false);
    }

    /** Every problem in the main configuration; empty when it is usable. */
    static List<String> validate(ConfigurationSection config) {
        List<String> errors = new ArrayList<>();
        validateFiniteNumbers(config, "", errors);
        for (String key : List.of("debt_reminder.threshold", "debt_reminder.interval_minutes")) {
            double value = config.getDouble(key, key.endsWith("threshold") ? 50.0 : 15.0);
            double max = key.endsWith("threshold") ? 100.0 : 1440.0;
            if ((config.contains(key) && !isNumber(config, key)) || !Double.isFinite(value) || value < 1 || value > max)
                errors.add(key + " must be a finite number between 1 and " + max);
        }
        if (config.contains("debt_reminder.enabled") && !config.isBoolean("debt_reminder.enabled"))
            errors.add("debt_reminder.enabled must be a boolean");
        List<Double> stages = stageThresholds(config);
        if (stages.size() != 4 || stages.get(0) < 0 || stages.get(3) > 100
                || !(stages.get(0) < stages.get(1) && stages.get(1) < stages.get(2) && stages.get(2) < stages.get(3))) {
            errors.add("fatigue.stages must contain four ascending values between 0 and 100");
        }
        atLeast(errors, config, "fatigue.update_interval_ticks", 20);
        atLeast(errors, config, "fatigue.base_per_minute", 0);
        atLeast(errors, config, "fatigue.debt_per_minute", 0);
        if (config.isSet("fatigue.pause-afk.threshold_seconds")
                && (!isNumber(config, "fatigue.pause-afk.threshold_seconds") || config.getDouble("fatigue.pause-afk.threshold_seconds") < 60)) {
            errors.add("fatigue.pause-afk.threshold_seconds must be a number >= 60");
        }
        atLeast(errors, config, "insomnia_mobs.shards_per_window", 0);
        positive(errors, config, "insomnia_mobs.shard_window_seconds");
        atLeast(errors, config, "insomnia_mobs.shard_diminishing_after", 0);
        atLeast(errors, config, "sleep.fatigue_recovery_per_second", 0);
        atLeast(errors, config, "sleep.debt_recovery_per_second", 0);
        for (String key : List.of("fatigue.dirty_flush_seconds", "activity.mining_window_seconds",
                "activity.combat_window_seconds", "activity.boss_window_seconds", "sleep.falling_asleep_seconds",
                "sleep.light_sleep_seconds", "sleep.deep_sleep_seconds", "sleep.quality_recheck_seconds",
                "hallucinations.check_interval_seconds", "hallucinations.sight_loss_seconds",
                "microsleep.check_interval_seconds", "microsleep.duration_min_seconds",
                "insomnia_mobs.check_interval_seconds", "insomnia_mobs.lifetime_seconds",
                "insomnia_mobs.min_spawn_distance", "rested.duration_minutes")) {
            positive(errors, config, key);
        }
        atLeast(errors, config, "microsleep.cooldown_seconds", 0);
        if (config.getInt("microsleep.duration_max_seconds") < config.getInt("microsleep.duration_min_seconds")) {
            errors.add("microsleep.duration_max_seconds must be >= duration_min_seconds");
        }
        if (config.getInt("insomnia_mobs.max_spawn_distance") < config.getInt("insomnia_mobs.min_spawn_distance")) {
            errors.add("insomnia_mobs.max_spawn_distance must be >= min_spawn_distance");
        }
        for (String key : List.of("hallucinations.chance_exhausted", "hallucinations.chance_overmuedet",
                "hallucinations.chance_sleep_deprivation", "microsleep.chance_per_check",
                "insomnia_mobs.base_spawn_chance", "rested.hunger_reduction")) {
            probability(errors, config, key);
        }
        errors.addAll(at.vystorm.insomnia.storage.StorageSettings.validate(config));
        for (String key : List.of("microsleep.min_fatigue", "insomnia_mobs.min_fatigue")) {
            double value = config.getDouble(key);
            if (value < 0 || value > 100) errors.add(key + " must be between 0 and 100");
        }
        atLeast(errors, config, "insomnia_mobs.max_per_player", 0);
        atLeast(errors, config, "insomnia_mobs.max_global", 0);
        atLeast(errors, config, "rested.xp_multiplier", 0);
        atLeast(errors, config, "rested.regeneration_per_5_seconds", 0);
        int restRadius = config.getInt("rest.campfire.radius", 3);
        if (restRadius < 1 || restRadius > 8) errors.add("rest.campfire.radius must be 1..8");
        atLeast(errors, config, "rest.campfire.settle_seconds", 0);
        atLeast(errors, config, "rest.campfire.fatigue_per_second", 0);
        double restFloor = config.getDouble("rest.campfire.min_fatigue", 30.0);
        if (restFloor < 0 || restFloor > 100) errors.add("rest.campfire.min_fatigue must be between 0 and 100");
        if (config.getDouble("dream.entry.minimum_fatigue", 40) < 0 || config.getDouble("dream.entry.minimum_fatigue", 40) > 100)
            errors.add("dream.entry.minimum_fatigue must be between 0 and 100");
        positive(errors, config, "dream.duration.minimum_minutes");
        positive(errors, config, "dream.duration.maximum_minutes");
        if (config.getInt("dream.duration.maximum_minutes", 20) < config.getInt("dream.duration.minimum_minutes", 5))
            errors.add("dream.duration.maximum_minutes must be >= minimum_minutes");
        positive(errors, config, "dream.entry.cooldown_seconds");
        int density = config.getInt("dream.world.decoration_density", 100);
        if (density < 0 || density > 100) errors.add("dream.world.decoration_density must be 0..100");
        for (String key : List.of("sky_island_density", "natural_accent_density", "natural_object_density")) {
            int value = config.getInt("dream.world." + key, key.equals("sky_island_density") ? 100 : 65);
            if (value < 0 || value > 100) errors.add("dream.world." + key + " must be 0..100");
        }
        int delay = config.getInt("dream.towers.transition_millis", 750);
        if (delay < 0 || delay > 3000) errors.add("dream.towers.transition_millis must be 0..3000");
        int cooldown = config.getInt("dream.towers.cooldown_seconds", 3);
        if (cooldown < 1 || cooldown > 60) errors.add("dream.towers.cooldown_seconds must be 1..60");
        if (config.isSet("dream.deadline.warning_seconds")
                && config.getIntegerList("dream.deadline.warning_seconds").stream().anyMatch(v -> v <= 0))
            errors.add("dream.deadline.warning_seconds entries must be > 0");
        if (config.isSet("dream.nightmare.wave_mobs")
                && config.getIntegerList("dream.nightmare.wave_mobs").stream().anyMatch(v -> v < 1 || v > 32))
            errors.add("dream.nightmare.wave_mobs entries must be between 1 and 32");
        if (config.getIntegerList("dream.nightmare.wave_mobs").size() > 5)
            errors.add("dream.nightmare.wave_mobs must contain at most five waves");
        if (config.getDouble("dream.duration.maximum_fatigue", 80.0) <= config.getDouble("dream.entry.minimum_fatigue", 40.0))
            errors.add("dream.duration.maximum_fatigue must be greater than dream.entry.minimum_fatigue");
        for (String preset : at.vystorm.insomnia.sleep.SleepPreferences.PRESETS) {
            String key = "sleep.dialog.presets." + preset;
            if (!config.isSet(key)) continue;
            String value = config.getString(key, "");
            if (value != null && !value.isBlank() && at.vystorm.insomnia.sleep.WakeTimes.parseClock(value) < 0)
                errors.add(key + " must be a clock time hh:mm (00:00-23:59) or '' to hide it");
        }
        for (String key : List.of("sleep.acceleration.min_speed", "sleep.acceleration.max_speed")) {
            if (config.isSet(key) && (!isNumber(config, key) || config.getDouble(key) < 1.0 || config.getDouble(key) > 100.0))
                errors.add(key + " must be a number between 1 and 100");
        }
        if (config.getDouble("sleep.acceleration.max_speed", 10.0) < config.getDouble("sleep.acceleration.min_speed", 2.0))
            errors.add("sleep.acceleration.max_speed must be >= min_speed");
        if (config.isSet("sleep.acceleration.curve") && (!isNumber(config, "sleep.acceleration.curve")
                || config.getDouble("sleep.acceleration.curve") < 0.1 || config.getDouble("sleep.acceleration.curve") > 5.0))
            errors.add("sleep.acceleration.curve must be a number between 0.1 and 5");
        return errors;
    }

    private static void validateFiniteNumbers(ConfigurationSection config, String prefix, List<String> errors) {
        config.getValues(true).forEach((key, value) -> validateFiniteValue(prefix + key, value, errors));
    }

    private static void validateFiniteValue(String key, Object value, List<String> errors) {
        if (value instanceof Number number && !Double.isFinite(number.doubleValue())) {
            errors.add(key + " = " + number);
        } else if (value instanceof List<?> values) {
            for (int i = 0; i < values.size(); i++) validateFiniteValue(key + "[" + i + "]", values.get(i), errors);
        } else if (value instanceof java.util.Map<?, ?> values) {
            values.forEach((name, entry) -> validateFiniteValue(key + "." + name, entry, errors));
        }
    }

    private static void atLeast(List<String> errors, ConfigurationSection config, String key, double min) {
        if (!config.isSet(key)) return;
        if (!isNumber(config, key) || config.getDouble(key) < min) errors.add(key + " must be a number >= " + format(min));
    }

    private static void positive(List<String> errors, ConfigurationSection config, String key) {
        if (!config.isSet(key)) return;
        if (!isNumber(config, key) || config.getDouble(key) <= 0) errors.add(key + " must be a number > 0");
    }

    private static void probability(List<String> errors, ConfigurationSection config, String key) {
        if (!config.isSet(key)) return;
        double value = config.getDouble(key);
        if (!isNumber(config, key) || value < 0 || value > 1) errors.add(key + " must be between 0 and 1");
    }

    private static boolean isNumber(ConfigurationSection config, String key) {
        return config.get(key) instanceof Number;
    }

    private static String format(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
    }

    public boolean fatigueEnabled() { return main.getBoolean("fatigue.enabled", true); }
    public boolean debtReminderEnabled() { return main.getBoolean("debt_reminder.enabled", true); }
    public double debtReminderThreshold() { return main.getDouble("debt_reminder.threshold", 50.0); }
    public long debtReminderIntervalMillis() { return (long) (main.getDouble("debt_reminder.interval_minutes", 15) * 60_000L); }
    public long fatigueUpdateIntervalTicks() { return main.getLong("fatigue.update_interval_ticks", 100L); }
    public double baseFatiguePerMinute() { return main.getDouble("fatigue.base_per_minute", 0.125); }
    public double debtPerMinute() { return main.getDouble("fatigue.debt_per_minute", 0.0875); }
    public boolean excludeCreative() { return main.getBoolean("fatigue.exclude_creative", true); }
    public boolean excludeSpectator() { return main.getBoolean("fatigue.exclude_spectator", true); }
    public boolean localAfkPauseEnabled() { return main.getBoolean("fatigue.pause-afk.enabled", false); }
    public int localAfkThresholdSeconds() { return main.getInt("fatigue.pause-afk.threshold_seconds", 300); }
    public long localAfkThresholdMillis() { return localAfkThresholdSeconds() * 1000L; }
    public long dirtyFlushSeconds() { return main.getLong("fatigue.dirty_flush_seconds", 300L); }

    public List<Double> stageThresholds() { return stageThresholds(main); }

    private static List<Double> stageThresholds(ConfigurationSection config) {
        List<?> raw = config.getList("fatigue.stages", List.of(20.0, 40.0, 60.0, 80.0));
        List<Double> out = new ArrayList<>();
        for (Object value : raw) if (value instanceof Number number) out.add(number.doubleValue());
        return out;
    }

    /** {@code activity.enabled} (0.31.0): off = every activity counts as normal play, whatever the factors say. */
    public boolean activityEnabled() { return main.getBoolean("activity.enabled", true); }
    public double activity(String key, double fallback) {
        if (!activityEnabled() && !key.equals("normal")) return activity("normal", 1.0);
        return main.getDouble("activity." + key, fallback);
    }
    public long miningWindowMillis() { return main.getLong("activity.mining_window_seconds", 8L) * 1000L; }
    public long combatWindowMillis() { return main.getLong("activity.combat_window_seconds", 20L) * 1000L; }
    public long bossWindowMillis() { return main.getLong("activity.boss_window_seconds", 30L) * 1000L; }
    public List<String> bossTypes() { return main.getStringList("activity.boss_types"); }

    public long fallingAsleepMillis() { return main.getLong("sleep.falling_asleep_seconds", 10L) * 1000L; }
    public long lightSleepMillis() { return main.getLong("sleep.light_sleep_seconds", 20L) * 1000L; }
    public long deepSleepMillis() { return main.getLong("sleep.deep_sleep_seconds", 30L) * 1000L; }
    public double sleepRecoveryPerSecond() { return main.getDouble("sleep.fatigue_recovery_per_second", 1.0); }
    public double debtRecoveryPerSecond() { return main.getDouble("sleep.debt_recovery_per_second", 0.5); }
    public long qualityRecheckMillis() { return main.getLong("sleep.quality_recheck_seconds", 10L) * 1000L; }
    /**
     * {@code sleep.enabled} (0.31.0), the master switch of the plugin's sleep: off = beds behave as in vanilla, no
     * sleep sessions, no recovery from sleep, no dialog, no time acceleration, no naps and no new rested bonus. The
     * Dream Realm keeps working through {@code /dream}.
     */
    public boolean sleepEnabled() { return main.getBoolean("sleep.enabled", true); }
    public boolean cancelVanillaNightSkip() { return sleepEnabled() && main.getBoolean("sleep.cancel_vanilla_night_skip", false); }
    public double sleepQuality(String key, double fallback) { return main.getDouble("sleep.quality." + key, fallback); }
    /** {@code sleep.quality.enabled} (0.31.0): off = every sleep has the base quality (roof, light, monsters ... ignored). */
    public boolean sleepQualityEnabled() { return main.getBoolean("sleep.quality.enabled", true); }
    /** {@code sleep.quality.cozy.enabled} (0.31.0): off = furnishings add nothing and the coziness line is not shown. */
    public boolean cozyEnabled() { return sleepQualityEnabled() && main.getBoolean("sleep.quality.cozy.enabled", true); }
    /** Quality per furnishing type; 0 while coziness is switched off. */
    public double cozyPerFurnishing() { return cozyEnabled() ? sleepQuality("cozy_per_furnishing", 0.04) : 0.0; }

    /** Sleep dialog when lying down (sleep until a time, dream realm, get up). */
    public boolean sleepDialogEnabled() { return sleepEnabled() && main.getBoolean("sleep.dialog.enabled", true); }
    public boolean sleepDialogDayNaps() { return main.getBoolean("sleep.dialog.day_naps", true); }
    public boolean sleepDialogCustomTime() { return main.getBoolean("sleep.dialog.custom_time", true); }
    /** Default clock times of the wake-up presets. */
    public static final java.util.Map<String, String> PRESET_DEFAULTS = java.util.Map.of(
            "morning", "06:00", "noon", "12:00", "evening", "18:00", "night", "22:00");
    /** Clock ticks after 06:00 of a preset, or -1 when it is hidden ('' in the config). */
    public int sleepPreset(String id) {
        return at.vystorm.insomnia.sleep.WakeTimes.parseClock(main.getString("sleep.dialog.presets." + id, PRESET_DEFAULTS.getOrDefault(id, "")));
    }

    /** Time acceleration instead of the vanilla instant night skip. */
    public boolean accelerationEnabled() { return sleepEnabled() && main.getBoolean("sleep.acceleration.enabled", true); }
    public double accelerationMinSpeed() { return main.getDouble("sleep.acceleration.min_speed", 2.0); }
    public double accelerationMaxSpeed() { return main.getDouble("sleep.acceleration.max_speed", 10.0); }
    public double accelerationCurve() { return main.getDouble("sleep.acceleration.curve", 1.0); }
    public List<String> accelerationWorlds() { return main.getStringList("sleep.acceleration.worlds"); }
    public boolean accelerationActionBar() { return main.getBoolean("sleep.acceleration.action_bar", true); }
    public boolean wakeSummary() { return sleepEnabled() && main.getBoolean("sleep.wake_summary", true); }

    public boolean stimulantsEnabled() { return main.getBoolean("stimulants.enabled", true); }
    public ConfigurationSection stimulants() { return stimulants.getConfigurationSection("stimulants"); }

    public boolean hallucinationsEnabled() { return main.getBoolean("hallucinations.enabled", true); }
    public long hallucinationCheckMillis() { return main.getLong("hallucinations.check_interval_seconds", 10L) * 1000L; }
    public double hallucinationChance(String key, double fallback) { return main.getDouble("hallucinations." + key, fallback); }
    /** {@code hallucinations.effects.<effect>} (0.31.0): false = the effect never comes, whatever its weight. */
    public boolean hallucinationEffectEnabled(String key) { return main.getBoolean("hallucinations.effects." + key, true); }
    public int hallucinationWeight(String key, int fallback) {
        return hallucinationEffectEnabled(key) ? main.getInt("hallucinations.weights." + key, fallback) : 0;
    }
    public long sightLossTicks() { return main.getLong("hallucinations.sight_loss_seconds", 4L) * 20L; }
    public String yawnSound() { return main.getString("hallucinations.custom_yawn_sound", "vystorm:insomnia.yawn"); }

    public boolean microsleepEnabled() { return main.getBoolean("microsleep.enabled", true); }
    public double microsleepMinFatigue() { return main.getDouble("microsleep.min_fatigue", 80.0); }
    public long microsleepCheckMillis() { return main.getLong("microsleep.check_interval_seconds", 10L) * 1000L; }
    public double microsleepChance() { return main.getDouble("microsleep.chance_per_check", 0.08); }
    public int microsleepMinSeconds() { return main.getInt("microsleep.duration_min_seconds", 1); }
    public int microsleepMaxSeconds() { return main.getInt("microsleep.duration_max_seconds", 4); }
    public long microsleepRecoveryGraceMillis() { return Math.clamp(main.getLong("microsleep.recovery_grace_seconds", 30L), 0L, 300L) * 1000L; }
    public long microsleepCooldownMillis() { return main.getLong("microsleep.cooldown_seconds", 120L) * 1000L; }

    public boolean mobsEnabled() { return main.getBoolean("insomnia_mobs.enabled", true); }
    public double mobMinFatigue() { return main.getDouble("insomnia_mobs.min_fatigue", 70.0); }
    public long mobCheckMillis() { return main.getLong("insomnia_mobs.check_interval_seconds", 15L) * 1000L; }
    public double mobSpawnChance() { return main.getDouble("insomnia_mobs.base_spawn_chance", 0.08); }
    public int maxMobsPerPlayer() { return main.getInt("insomnia_mobs.max_per_player", 3); }
    public int maxMobsGlobal() { return main.getInt("insomnia_mobs.max_global", 20); }
    public int mobMinDistance() { return main.getInt("insomnia_mobs.min_spawn_distance", 8); }
    public int mobMaxDistance() { return main.getInt("insomnia_mobs.max_spawn_distance", 18); }
    public long mobLifetimeMillis() { return main.getLong("insomnia_mobs.lifetime_seconds", 120L) * 1000L; }
    public int shardCapPerWindow() { return Math.clamp(main.getInt("insomnia_mobs.shards_per_window", 3), 0, 1000); }
    public long shardWindowMillis() { return Math.clamp(main.getLong("insomnia_mobs.shard_window_seconds", 900L), 1L, 86_400L) * 1000L; }
    public int shardDiminishingAfter() { return main.getInt("insomnia_mobs.shard_diminishing_after", 1); }
    public ConfigurationSection mobTiers() { return mobs.getConfigurationSection("tiers"); }

    public boolean restedEnabled() { return main.getBoolean("rested.enabled", true); }
    public long restedDurationMillis() { return main.getLong("rested.duration_minutes", 45L) * 60_000L; }
    public double restedXpMultiplier() { return main.getDouble("rested.xp_multiplier", 1.05); }
    public double restedRegenPerFiveSeconds() { return main.getDouble("rested.regeneration_per_5_seconds", 0.5); }
    public double restedHungerReduction() { return main.getDouble("rested.hunger_reduction", 0.15); }

    public boolean campfireRestEnabled() { return main.getBoolean("rest.campfire.enabled", true); }
    public int campfireRadius() { return Math.clamp(main.getInt("rest.campfire.radius", 3), 1, 8); }
    public long campfireSettleMillis() { return Math.max(0L, main.getLong("rest.campfire.settle_seconds", 5L)) * 1000L; }
    public double campfireFatiguePerSecond() { return Math.max(0.0, main.getDouble("rest.campfire.fatigue_per_second", 0.2)); }
    public double campfireMinFatigue() { return Math.clamp(main.getDouble("rest.campfire.min_fatigue", 30.0), 0.0, 100.0); }

    /** {@code menu.enabled} (0.31.0): the fatigue menu and panel, {@code /insomnia}, {@code status}, {@code history}, {@code triggers}. */
    public boolean menuEnabled() { return main.getBoolean("menu.enabled", true); }
    /** {@code admin_tools.enabled} (0.31.0): {@code /insomnia admin ...} except {@code reload}. */
    public boolean adminToolsEnabled() { return main.getBoolean("admin_tools.enabled", true); }
    /** {@code client_hud.enabled} (0.31.0): fatigue bars and stage toasts for players with the Vystorm client mod. */
    public boolean clientHudEnabled() { return main.getBoolean("client_hud.enabled", true); }
    /** {@code web_page.enabled} (0.31.0): the page in Core's web UI; read at startup only. */
    public boolean webPageEnabled() { return main.getBoolean("web_page.enabled", true); }
    /** {@code tutorial.enabled} (0.31.0): the tutorial in Core's main menu; read at startup only. */
    public boolean tutorialEnabled() { return main.getBoolean("tutorial.enabled", true); }

    public boolean playerMenuEnabled() { return main.getBoolean("player_menu.enabled", true); }
    public boolean playerMenuShowsStage() { return main.getBoolean("player_menu.show_stage_to_players", true); }

    /** storage.* as currently in config.yml; the plugin only applies it at startup. */
    public at.vystorm.insomnia.storage.StorageSettings storage() { return at.vystorm.insomnia.storage.StorageSettings.from(main); }

    public boolean cityEnabled() { return main.getBoolean("integrations.cityclaim", true); }
    public boolean bloodMoonEnabled() { return main.getBoolean("integrations.bloodmoon", true); }

    public boolean dreamEnabled() { return main.getBoolean("dream.enabled", true); }
    public boolean dreamQuestsEnabled() { return main.getBoolean("dream.quests.enabled", true); }
    public boolean dreamCompanionsEnabled() { return main.getBoolean("dream.companions.enabled", true); }
    public List<String> dreamQuestRewards(String ending) {
        return main.getStringList("dream.quests.rewards." + ending);
    }
    public boolean daytimeBedSleepEnabled() { return sleepEnabled() && main.getBoolean("sleep.daytime.enabled", true); }
    public double dreamMinimumFatigue() { return main.getDouble("dream.entry.minimum_fatigue", 40.0); }
    public List<String> dreamAllowedWorlds() { return main.getStringList("dream.entry.allowed_worlds"); }
    public List<String> dreamBlockedRegionKinds() { return main.getStringList("dream.entry.blocked_region_kinds"); }
    public int dreamCooldownSeconds() { return main.getInt("dream.entry.cooldown_seconds", 300); }
    public long dreamMinDurationMillis() { return main.getLong("dream.duration.minimum_minutes", 5) * 60_000L; }
    public long dreamMaxDurationMillis() { return main.getLong("dream.duration.maximum_minutes", 20) * 60_000L; }
    public double dreamMaxDurationFatigue() { return main.getDouble("dream.duration.maximum_fatigue", 80.0); }
    public List<Integer> dreamWarningSeconds() { return main.getIntegerList("dream.deadline.warning_seconds"); }
    public List<String> dreamRewards(String dungeonId) { return main.getStringList("dream.loot.rewards." + dungeonId); }
    public boolean dreamWeeklyEnabled() { return main.getBoolean("dream.weekly.enabled", true); }
    public int dreamWeeklyModifierCount() { return Math.clamp(main.getInt("dream.weekly.modifiers", 2), 1, 5); }
    public List<String> dreamWeeklyRewards() { return main.getStringList("dream.weekly.reward"); }
    public List<Integer> dreamNightmareWaves() { return main.getIntegerList("dream.nightmare.wave_mobs"); }
    public String dreamWorldId() { return main.getString("dream.world.id", "insomnia:dreamweite"); }
    public String dreamWorldName() { return main.getString("dream.world.name", "InsomniaDream"); }
    public boolean dreamAmbientLight() { return main.getBoolean("dream.world.ambient_light", true); }
    // Landscape layers of the dream world (0.31.0 switches): false = 0, whatever the density says. Restart needed.
    public int dreamSkyIslandDensity() { return layer("sky_islands", main.getInt("dream.world.sky_island_density", 100)); }
    public boolean dreamDungeonsEnabled() { return main.getBoolean("dream.world.dungeons_enabled", true); }
    public int dreamNaturalObjectDensity() { return layer("natural_objects", main.getInt("dream.world.natural_object_density", 65)); }
    public int dreamNaturalAccentDensity() { return layer("natural_accents", main.getInt("dream.world.natural_accent_density", 65)); }
    public int dreamDecorationDensity() { return layer("decorations", main.getInt("dream.world.decoration_density", 100)); }
    private int layer(String name, int density) { return main.getBoolean("dream.world." + name + ".enabled", true) ? density : 0; }
    public long dreamTowerDelayMillis() { return main.getInt("dream.towers.transition_millis", 750); }
    public long dreamTowerCooldownMillis() { return main.getInt("dream.towers.cooldown_seconds", 3) * 1000L; }
    public boolean dreamTowerEffects() { return main.getBoolean("dream.towers.effects", true); }
    public long dreamWorldSeed() { return main.getLong("dream.world.seed", 510231L); }
    public String dreamPouchNamespace() { return main.getString("dream.loot.namespace", "insomnia_dreamweite"); }

    /** Binds the Core language bundle; until then {@link #message} returns the key (unit tests). */
    public void bindMessages(Messages messages) { this.texts = messages; }

    public Messages messages() { return texts; }

    /** Chat message with prefix in the viewer's language (legacy {@code §} string). */
    public String message(CommandSender viewer, String key, Object... placeholders) {
        return texts == null ? key : texts.chat(viewer, key, placeholders);
    }

    /** Language key of the answer for a feature that is off because another switch is off (0.31.1). */
    public static final String NEEDS = "feature_needs";

    /**
     * "This needs <name of the switch> (<path>), which is switched off on this server." for a command whose own switch
     * is on but which depends on the switch {@code path}; the name is the switch's label from {@code setting.<path>.label}.
     */
    public String needs(CommandSender viewer, String path) {
        String name = texts == null ? path : texts.plain(viewer, "setting." + path + ".label");
        return message(viewer, NEEDS, "switch", name, "path", path);
    }
}
