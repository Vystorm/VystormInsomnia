package at.vystorm.insomnia.storage;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageSettingsTest {
    @Test
    void defaultsToYamlWithTheInsomniaPrefix() {
        YamlConfiguration config = new YamlConfiguration();
        StorageSettings settings = StorageSettings.from(config);
        assertFalse(settings.mysql());
        assertEquals("vinsom", settings.tablePrefix());
        assertEquals("default", settings.serverId());
        assertEquals(10, settings.claimWaitSeconds());
        assertTrue(StorageSettings.validate(config).isEmpty());
    }

    @Test
    void readsMysqlMode() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("storage.type", "MySQL");
        config.set("storage.table-prefix", "ins2");
        config.set("storage.server-id", "survival-1");
        StorageSettings settings = StorageSettings.from(config);
        assertTrue(settings.mysql());
        assertEquals("ins2", settings.tablePrefix());
        assertEquals("survival-1", settings.serverId());
        assertTrue(StorageSettings.validate(config).isEmpty());
    }

    @Test
    void rejectsUnknownTypeBadPrefixServerIdAndWait() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("storage.type", "sqlite");
        config.set("storage.table-prefix", "9Bad");
        config.set("storage.server-id", "has space");
        config.set("storage.claim-wait-seconds", 0);
        assertEquals(4, StorageSettings.validate(config).size());
    }
}
