package at.vystorm.insomnia.config;

import static org.junit.jupiter.api.Assertions.*;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class InsomniaConfigMigrationTest {
    @Test void addsReminderDefaultsToExistingConfigWithoutChangingCustomValuesOrRates() {
        var yaml = new YamlConfiguration();
        yaml.set("config_version", 2);
        yaml.set("fatigue.base_per_minute", 0.2);
        yaml.set("debt_reminder.enabled", false);
        yaml.set("debt_reminder.threshold", 70.0);
        var defaults = new YamlConfiguration();
        defaults.set("debt_reminder.interval_minutes", 15);
        yaml.setDefaults(defaults);
        assertTrue(migrate(yaml));
        assertEquals(15, yaml.getInt("debt_reminder.interval_minutes"));
        assertTrue(yaml.contains("debt_reminder.interval_minutes", true));
        assertFalse(yaml.getBoolean("debt_reminder.enabled"));
        assertEquals(70.0, yaml.getDouble("debt_reminder.threshold"));
        assertEquals(0.2, yaml.getDouble("fatigue.base_per_minute"));
        assertFalse(migrate(yaml));
    }

    private static boolean migrate(YamlConfiguration yaml) {
        return InsomniaConfig.migrate(yaml);
    }

    @Test void versionOneRatesAreSlowedToAQuarterOnce() {
        var yaml = new YamlConfiguration();
        yaml.set("config_version", 1);
        yaml.set("fatigue.base_per_minute", 0.5);
        yaml.set("fatigue.debt_per_minute", 0.35);
        assertTrue(migrate(yaml));
        assertEquals(0.125, yaml.getDouble("fatigue.base_per_minute"), 1e-9);
        assertEquals(0.0875, yaml.getDouble("fatigue.debt_per_minute"), 1e-9);
        assertEquals(InsomniaConfig.CONFIG_VERSION, yaml.getInt("config_version"));
        assertFalse(migrate(yaml), "a migrated configuration is never slowed twice");
        assertEquals(0.125, yaml.getDouble("fatigue.base_per_minute"), 1e-9);
    }

    @Test void customisedRatesKeepTheirRatio() {
        var yaml = new YamlConfiguration();
        yaml.set("fatigue.base_per_minute", 2.0);
        assertTrue(migrate(yaml));
        assertEquals(0.5, yaml.getDouble("fatigue.base_per_minute"), 1e-9);
        assertFalse(yaml.isSet("fatigue.debt_per_minute"), "missing keys fall back to the new defaults");
    }
}
