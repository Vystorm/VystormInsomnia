package at.vystorm.insomnia;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InsomniaConfigEditorTest {
    private static YamlConfiguration resource(String name) {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(Objects.requireNonNull(
                InsomniaConfigEditorTest.class.getClassLoader().getResourceAsStream(name), name), StandardCharsets.UTF_8));
    }

    @Test
    void labelledKeysExistInTheDefaultConfigAndBothLanguages() {
        YamlConfiguration config = resource("config.yml");
        List<YamlConfiguration> langs = List.of(resource("lang/de.yml"), resource("lang/en.yml"));
        assertFalse(InsomniaConfigEditor.ENTRIES.isEmpty());
        for (InsomniaConfigEditor.Entry entry : InsomniaConfigEditor.ENTRIES) {
            String path = entry.path();
            assertTrue(config.contains(path), "missing in config.yml: " + path);
            assertFalse(config.isConfigurationSection(path), "not a value: " + path);
            if (entry.ranged()) {
                assertTrue(config.isInt(path) || config.isLong(path) || config.isDouble(path), "range on a non-number: " + path);
                double value = config.getDouble(path);
                assertTrue(value >= entry.min() && value <= entry.max(), "default outside range: " + path);
                if (Math.abs(entry.step() - Math.rint(entry.step())) > 1e-9)
                    assertTrue(config.isDouble(path), "fractional step needs a decimal default (write 1.0): " + path);
            }
            for (YamlConfiguration lang : langs) {
                assertTrue(lang.isString(entry.labelKey()), "missing label " + entry.labelKey());
                assertTrue(lang.isString(entry.descriptionKey()), "missing description " + entry.descriptionKey());
            }
        }
    }
}
