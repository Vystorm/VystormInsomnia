package at.vystorm.insomnia.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InsomniaConfigValidationTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"fatigue.base_per_minute", "rested.regeneration_per_5_seconds",
            "activity.sprint", "sleep.quality.base", "rest.campfire.min_fatigue", "microsleep.chance_per_check",
            "sleep.acceleration.curve", "dream.duration.maximum_fatigue"})
    void rejectsNonFiniteRuntimeNumbers(String key) {
        for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            var yaml = bundled();
            yaml.set(key, value);
            assertTrue(InsomniaConfig.validate(yaml).stream().anyMatch(error -> error.startsWith(key)), key + "=" + value);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"stimulants.yml,coffee.fatigue_reduction", "mobs.yml,tiers.1.health_multiplier"})
    void nonFiniteAuxiliaryConfigCannotReplaceTheActiveSnapshot(String file, String key) throws IOException {
        InsomniaConfig config = new InsomniaConfig(plugin);
        var stimulants = config.stimulants();
        var mobs = config.mobTiers();
        var yaml = YamlConfiguration.loadConfiguration(folder.resolve(file).toFile());
        yaml.set(key, Double.NaN);
        yaml.save(folder.resolve(file).toFile());
        assertThrows(IllegalArgumentException.class, config::reload);
        assertSame(stimulants, config.stimulants());
        assertSame(mobs, config.mobTiers());
    }

    @TempDir Path folder;
    private JavaPlugin plugin;

    private static InputStream resource(String name) {
        return Objects.requireNonNull(InsomniaConfigValidationTest.class.getClassLoader().getResourceAsStream(name), name);
    }

    private static YamlConfiguration bundled() {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(resource("config.yml"), StandardCharsets.UTF_8));
    }

    @BeforeEach
    void setUp() throws IOException {
        for (String name : List.of("config.yml", "stimulants.yml", "mobs.yml")) {
            Files.copy(resource(name), folder.resolve(name), StandardCopyOption.REPLACE_EXISTING);
        }
        plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(folder.toFile());
        when(plugin.getResource("config.yml")).thenAnswer(ignored -> resource("config.yml"));
    }

    @Test
    void bundledConfigurationIsValid() {
        assertEquals(List.of(), InsomniaConfig.validate(bundled()));
    }

    @Test void rejectsInvalidReminderSettingsIncludingNonFiniteNumbers() {
        for (String key : List.of("debt_reminder.threshold", "debt_reminder.interval_minutes")) {
            for (Object value : List.of(0, -1, 1441, Double.NaN, Double.POSITIVE_INFINITY, "invalid")) {
                var yaml = bundled();
                yaml.set(key, value);
                assertTrue(InsomniaConfig.validate(yaml).stream().anyMatch(e -> e.startsWith(key)), key + "=" + value);
            }
        }
        var yaml = bundled();
        yaml.set("debt_reminder.enabled", "invalid");
        assertTrue(InsomniaConfig.validate(yaml).stream().anyMatch(e -> e.startsWith("debt_reminder.enabled")));
    }

    @Test void reminderReloadRejectsInvalidValuesAndKeepsPreviousSnapshot() throws IOException {
        var config = new InsomniaConfig(plugin);
        assertTrue(config.debtReminderEnabled());
        assertEquals(50.0, config.debtReminderThreshold());
        assertEquals(900_000L, config.debtReminderIntervalMillis());
        var yaml = bundled();
        yaml.set("debt_reminder.threshold", 101);
        yaml.save(folder.resolve("config.yml").toFile());
        assertThrows(IllegalArgumentException.class, config::reload);
        assertEquals(50.0, config.debtReminderThreshold());
        yaml.set("debt_reminder.threshold", 75);
        yaml.set("debt_reminder.interval_minutes", 2);
        yaml.save(folder.resolve("config.yml").toFile());
        config.reload();
        assertEquals(75.0, config.debtReminderThreshold());
        assertEquals(120_000L, config.debtReminderIntervalMillis());
    }

    @Test
    void rejectsOutOfRangeDreamGenerationAndTowerSettings() {
        YamlConfiguration config = bundled();
        config.set("dream.world.decoration_density", 101);
        config.set("dream.world.sky_island_density", -1);
        config.set("dream.world.natural_accent_density", 101);
        config.set("dream.world.natural_object_density", -1);
        config.set("dream.towers.transition_millis", -1);
        config.set("dream.towers.cooldown_seconds", 0);
        String errors = String.join("\n", InsomniaConfig.validate(config));
        assertTrue(errors.contains("decoration_density"));
        assertTrue(errors.contains("sky_island_density"));
        assertTrue(errors.contains("natural_accent_density"));
        assertTrue(errors.contains("natural_object_density"));
        assertTrue(errors.contains("transition_millis"));
        assertTrue(errors.contains("cooldown_seconds"));
    }

    @Test
    void reportsEveryInvalidValue() {
        YamlConfiguration config = bundled();
        config.set("microsleep.chance_per_check", 1.5);
        config.set("insomnia_mobs.base_spawn_chance", -0.1);
        config.set("insomnia_mobs.max_per_player", -1);
        config.set("fatigue.dirty_flush_seconds", 0);
        config.set("rested.duration_minutes", -5);
        config.set("microsleep.duration_max_seconds", 0);
        config.set("sleep.deep_sleep_seconds", "long");
        config.set("fatigue.stages", List.of(20.0, 10.0, 60.0, 80.0));
        config.set("insomnia_mobs.shards_per_window", -1);
        config.set("insomnia_mobs.shard_window_seconds", 0);
        config.set("fatigue.pause-afk.threshold_seconds", 59);

        String errors = String.join("\n", InsomniaConfig.validate(config));

        for (String key : List.of("microsleep.chance_per_check", "insomnia_mobs.base_spawn_chance",
                "insomnia_mobs.max_per_player", "fatigue.dirty_flush_seconds", "rested.duration_minutes",
                "microsleep.duration_max_seconds", "sleep.deep_sleep_seconds", "fatigue.stages",
                "insomnia_mobs.shards_per_window", "insomnia_mobs.shard_window_seconds")) {
            assertTrue(errors.contains(key), "missing error for " + key + " in:\n" + errors);
        }
        assertTrue(errors.contains("fatigue.pause-afk.threshold_seconds"), errors);
    }

    @Test
    void rejectsInvalidCampfireRestSettings() {
        YamlConfiguration config = bundled();
        config.set("rest.campfire.radius", 9);
        config.set("rest.campfire.settle_seconds", -1);
        config.set("rest.campfire.fatigue_per_second", "fast");
        config.set("rest.campfire.min_fatigue", 120.0);
        String errors = String.join("\n", InsomniaConfig.validate(config));
        for (String key : List.of("rest.campfire.radius", "rest.campfire.settle_seconds",
                "rest.campfire.fatigue_per_second", "rest.campfire.min_fatigue")) {
            assertTrue(errors.contains(key), "missing error for " + key + " in:\n" + errors);
        }
    }

    @Test
    void configWithoutTheNewSectionsUsesTheBundledDefaults() throws IOException {
        YamlConfiguration old = YamlConfiguration.loadConfiguration(folder.resolve("config.yml").toFile());
        old.set("rest", null);
        old.set("player_menu", null);
        old.save(folder.resolve("config.yml").toFile());

        InsomniaConfig config = new InsomniaConfig(plugin);
        assertTrue(config.campfireRestEnabled());
        assertEquals(3, config.campfireRadius());
        assertEquals(5_000L, config.campfireSettleMillis());
        assertEquals(0.2, config.campfireFatiguePerSecond());
        assertEquals(30.0, config.campfireMinFatigue());
        assertTrue(config.playerMenuEnabled());
        assertTrue(config.playerMenuShowsStage());
    }

    @Test
    void invalidReloadKeepsThePreviousValuesActive() throws IOException {
        InsomniaConfig config = new InsomniaConfig(plugin);
        assertEquals(0.08, config.microsleepChance());

        YamlConfiguration edited = YamlConfiguration.loadConfiguration(folder.resolve("config.yml").toFile());
        edited.set("microsleep.chance_per_check", 0.5);
        edited.set("insomnia_mobs.max_global", -3);
        edited.save(folder.resolve("config.yml").toFile());

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, config::reload);
        assertTrue(failure.getMessage().contains("insomnia_mobs.max_global"));
        assertEquals(0.08, config.microsleepChance(), "half of the broken file became active");
        assertEquals(20, config.maxMobsGlobal());

        edited.set("insomnia_mobs.max_global", 30);
        edited.save(folder.resolve("config.yml").toFile());
        config.reload();
        assertEquals(0.5, config.microsleepChance());
        assertEquals(30, config.maxMobsGlobal());
    }

    @Test
    void yamlSyntaxErrorsAreRejectedInsteadOfLoadingAnEmptyFile() throws IOException {
        InsomniaConfig config = new InsomniaConfig(plugin);
        Files.writeString(folder.resolve("mobs.yml"), "tiers:\n  1: [unclosed\n");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, config::reload);
        assertTrue(failure.getMessage().contains("mobs.yml"));
        assertNotNull(config.mobTiers(), "previous mob tiers must stay active");
    }

    @Test
    void sleepDialogAndAccelerationDefaults() throws IOException {
        YamlConfiguration old = YamlConfiguration.loadConfiguration(folder.resolve("config.yml").toFile());
        old.set("sleep.dialog", null);
        old.set("sleep.acceleration", null);
        old.save(folder.resolve("config.yml").toFile());

        InsomniaConfig config = new InsomniaConfig(plugin);
        assertTrue(config.sleepDialogEnabled());
        assertTrue(config.sleepDialogDayNaps());
        assertTrue(config.sleepDialogCustomTime());
        assertEquals(0, config.sleepPreset("morning"));
        assertEquals(6000, config.sleepPreset("noon"));
        assertEquals(12000, config.sleepPreset("evening"));
        assertEquals(16000, config.sleepPreset("night"));
        assertTrue(config.accelerationEnabled());
        assertEquals(2.0, config.accelerationMinSpeed());
        assertEquals(10.0, config.accelerationMaxSpeed());
        assertEquals(1.0, config.accelerationCurve());
        assertEquals(List.of(), config.accelerationWorlds());
        assertTrue(config.accelerationActionBar());
    }

    @Test
    void bundledSleepKeysHaveDefaultsAndNoBareYesNoKeys() throws IOException {
        YamlConfiguration config = bundled();
        for (String key : List.of("sleep.dialog.enabled", "sleep.dialog.day_naps", "sleep.dialog.custom_time",
                "sleep.dialog.presets.morning", "sleep.dialog.presets.noon", "sleep.dialog.presets.evening",
                "sleep.dialog.presets.night", "sleep.acceleration.enabled", "sleep.acceleration.min_speed",
                "sleep.acceleration.max_speed", "sleep.acceleration.curve", "sleep.acceleration.worlds",
                "sleep.acceleration.action_bar")) {
            assertTrue(config.contains(key), "missing default " + key);
        }
        assertTrue(config.isDouble("sleep.acceleration.min_speed") && config.isDouble("sleep.acceleration.max_speed")
                && config.isDouble("sleep.acceleration.curve"), "decimal keys need decimal defaults for the editor");
        String text = new String(resource("config.yml").readAllBytes(), StandardCharsets.UTF_8);
        for (String line : text.split("\n")) {
            String key = line.strip();
            if (key.startsWith("#") || !key.contains(":")) continue;
            String name = key.substring(0, key.indexOf(':')).strip().toLowerCase(java.util.Locale.ROOT);
            assertFalse(List.of("on", "off", "yes", "no", "y", "n").contains(name), "bare boolean-like key: " + line);
        }
    }

    @Test
    void rejectsInvalidSleepSettings() {
        YamlConfiguration config = bundled();
        config.set("sleep.dialog.presets.noon", "25:00");
        config.set("sleep.acceleration.min_speed", 0.5);
        config.set("sleep.acceleration.max_speed", 150.0);
        config.set("sleep.acceleration.curve", 0.0);
        String errors = String.join("\n", InsomniaConfig.validate(config));
        for (String key : List.of("sleep.dialog.presets.noon", "sleep.acceleration.min_speed",
                "sleep.acceleration.max_speed", "sleep.acceleration.curve")) {
            assertTrue(errors.contains(key), "missing error for " + key + " in:\n" + errors);
        }
        YamlConfiguration hidden = bundled();
        hidden.set("sleep.dialog.presets.night", "");
        hidden.set("sleep.acceleration.min_speed", 8.0);
        hidden.set("sleep.acceleration.max_speed", 4.0);
        String more = String.join("\n", InsomniaConfig.validate(hidden));
        assertFalse(more.contains("presets.night"), "'' hides a preset");
        assertTrue(more.contains("max_speed must be >= min_speed"), more);
    }
}
