package at.vystorm.insomnia.ui;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MessagesTest {
    private static InputStream resource(String name) {
        return MessagesTest.class.getClassLoader().getResourceAsStream(name);
    }

    private static YamlConfiguration bundled(String code) {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
                Objects.requireNonNull(resource("lang/" + code + ".yml"), code), StandardCharsets.UTF_8));
    }

    @Test
    void germanAndEnglishHaveTheSameKeys() {
        Set<String> de = bundled("de").getKeys(true);
        Set<String> en = bundled("en").getKeys(true);
        assertEquals(de, en);
        for (String key : List.of("prefix", "status", "config_invalid", "admin.set_fatigue", "admin.mob_refused",
                "stage_name.rested", "stage_name.sleep_deprivation", "hint.rested", "hint.sleep_deprivation",
                "rested_left.none", "rested_left.minutes", "menu.title", "menu.chat_button", "dialog.state",
                "dialog.close", "settings.title", "settings.description")) {
            assertTrue(de.contains(key), "de.yml misses " + key);
        }
    }

    @Test
    void everySettingLabelHasATextInBothLanguages() {
        org.bukkit.plugin.Plugin plugin = org.mockito.Mockito.mock(org.bukkit.plugin.Plugin.class);
        java.util.List<String> keys = new java.util.ArrayList<>();
        InsomniaMenu.settings(plugin, key -> { keys.add(key); return key; });
        assertEquals(64, keys.size());
        YamlConfiguration de = bundled("de");
        YamlConfiguration en = bundled("en");
        for (String key : keys) {
            assertTrue(de.isString(key), "de.yml misses " + key);
            assertTrue(en.isString(key), "en.yml misses " + key);
        }
    }

    @Test
    void sleepTextsUseTheSamePlaceholdersInBothLanguagesAndNoRawArgTags() {
        YamlConfiguration de = bundled("de");
        YamlConfiguration en = bundled("en");
        java.util.regex.Pattern placeholder = java.util.regex.Pattern.compile("[{]([a-z_]+)[}]");
        for (String key : en.getKeys(true)) {
            if (!en.isString(key)) continue;
            String english = en.getString(key);
            String german = de.getString(key);
            assertFalse(english.contains("<arg") || german.contains("<arg"), "raw <arg> tag in " + key);
            if (!key.startsWith("sleep_choice.")) continue;
            Set<String> enNames = new java.util.TreeSet<>();
            Set<String> deNames = new java.util.TreeSet<>();
            placeholder.matcher(english).results().forEach(m -> enNames.add(m.group(1)));
            placeholder.matcher(german).results().forEach(m -> deNames.add(m.group(1)));
            assertEquals(enNames, deNames, "placeholders differ in " + key);
        }
        for (String key : List.of("sleep_choice.action_bar", "sleep_choice.button.morning", "sleep_choice.settings.mode_auto",
                "sleep_choice.settings.choice_dream", "sleep_choice.remember", "sleep_choice.woke")) {
            assertTrue(en.isString(key) && de.isString(key), "missing " + key);
        }
        assertTrue(de.getString("sleep_choice.action_bar").contains("{speed}× schneller"));
    }

    @Test
    void chineseHasTheSameKeysAndPlaceholdersAndNoRawArgTags() {
        YamlConfiguration en = bundled("en");
        YamlConfiguration zh = bundled("zh_cn");
        assertEquals(en.getKeys(true), zh.getKeys(true));
        java.util.regex.Pattern placeholder = java.util.regex.Pattern.compile("[{]([a-z_]+)[}]");
        for (String key : en.getKeys(true)) {
            if (!en.isString(key)) continue;
            assertTrue(zh.isString(key), "zh_cn.yml misses " + key);
            String english = en.getString(key);
            String chinese = zh.getString(key);
            assertFalse(chinese.contains("<arg"), "raw <arg> tag in zh_cn " + key);
            Set<String> enNames = new java.util.TreeSet<>();
            Set<String> zhNames = new java.util.TreeSet<>();
            placeholder.matcher(english).results().forEach(m -> enNames.add(m.group(1)));
            placeholder.matcher(chinese).results().forEach(m -> zhNames.add(m.group(1)));
            assertEquals(enNames, zhNames, "placeholders differ in zh_cn " + key);
        }
    }
}
