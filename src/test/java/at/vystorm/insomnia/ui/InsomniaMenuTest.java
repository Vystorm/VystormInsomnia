package at.vystorm.insomnia.ui;

import Vystorm.vystorm_core._uiMethods.settings.Setting;
import Vystorm.vystorm_core._uiMethods.settings.SettingsSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InsomniaMenuTest {
    private static YamlConfiguration defaults() {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
                Objects.requireNonNull(InsomniaMenuTest.class.getClassLoader().getResourceAsStream("config.yml")), StandardCharsets.UTF_8));
    }

    @Test void everySettingReadsAnExistingKeyWhoseDefaultFitsTheSlider() {
        YamlConfiguration config = defaults();
        Plugin plugin = mock(Plugin.class);
        when(plugin.getConfig()).thenReturn(config);
        SettingsSection section = InsomniaMenu.settings(plugin, key -> key).section("server", "t", "", InsomniaMenu.ADMIN, p -> {});
        for (Setting setting : section.settings()) {
            assertTrue(config.isSet(setting.id()), "missing config key " + setting.id());
            if (setting.kind() != Setting.Kind.NUMBER) continue;
            double v = ((Number) setting.getter().apply(null)).doubleValue();
            assertTrue(v >= setting.min() && v <= setting.max(), setting.id() + " outside slider: " + v);
            double steps = (v - setting.min()) / setting.step();
            assertEquals(Math.rint(steps), steps, 1e-6, setting.id() + " default not on a slider step");
        }
    }

    @Test void settingsKeyFollowsCoreNaming() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn("VystormInsomnia");
        assertEquals("vystorminsomnia:server", InsomniaMenu.settingsKey(plugin));
    }

    @Test void barsShowTenSegments() {
        assertEquals("█████░░░░░", InsomniaMenu.bar(50.0));
        assertEquals("░░░░░░░░░░", InsomniaMenu.bar(-3.0));
        assertEquals("██████████", InsomniaMenu.bar(140.0));
    }
}
