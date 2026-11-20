package at.vystorm.insomnia.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A config.yml or data file with a YAML error is never saved over; the reload answer names line and column. */
class BrokenFilesTest {
    static final String BROKEN = "fatigue:\n  enabled: true\n  base_per_minute: 0.05\n broken here\nsleep:\n  enabled: true\n";

    @TempDir Path folder;
    private JavaPlugin plugin;

    private static InputStream resource(String name) {
        return Objects.requireNonNull(BrokenFilesTest.class.getClassLoader().getResourceAsStream(name), name);
    }

    @BeforeEach void setUp() throws Exception {
        for (String name : List.of("config.yml", "stimulants.yml", "mobs.yml"))
            Files.copy(resource(name), folder.resolve(name), StandardCopyOption.REPLACE_EXISTING);
        plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(folder.toFile());
        when(plugin.getResource("config.yml")).thenAnswer(ignored -> resource("config.yml"));
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
    }

    @Test void givenBrokenConfig_whenReloaded_thenTheFileStaysThePreviousConfigStaysAndTheErrorNamesLineAndColumn() throws Exception {
        InsomniaConfig config = new InsomniaConfig(plugin);
        var before = config.stimulants();
        Path file = folder.resolve("config.yml");
        Files.writeString(file, BROKEN, StandardCharsets.UTF_8);
        byte[] bytes = Files.readAllBytes(file);
        IllegalArgumentException invalid = assertThrows(IllegalArgumentException.class, config::reload);
        assertTrue(invalid.getMessage().matches("config\\.yml has a YAML error at line 4, column \\d+: .+"), invalid.getMessage());
        assertSame(before, config.stimulants(), "the previous configuration stays active");
        assertArrayEquals(bytes, Files.readAllBytes(file));
        // start: the same strict load refuses the file and leaves it as it is
        assertThrows(IllegalArgumentException.class, () -> new InsomniaConfig(plugin));
        assertArrayEquals(bytes, Files.readAllBytes(file));
        verify(plugin, never()).saveConfig();
    }

    @Test void givenBrokenConfig_whenTheSettingsMenuSaves_thenTheFileIsNotOverwritten() throws Exception {
        Path file = folder.resolve("config.yml");
        Files.writeString(file, BROKEN, StandardCharsets.UTF_8);
        assertFalse(YamlCheck.saveConfig(plugin));
        verify(plugin, never()).saveConfig();
        assertEquals(BROKEN, Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test void givenBrokenWeeklyFile_whenLoaded_thenItIsCopiedAsideBeforeItCanBeWritten() throws Exception {
        Path file = folder.resolve("weekly-nightmare.yml");
        Files.writeString(file, BROKEN, StandardCharsets.UTF_8);
        YamlConfiguration loaded = YamlCheck.loadData(file, java.util.logging.Logger.getAnonymousLogger());
        assertTrue(loaded.getKeys(false).isEmpty());
        try (Stream<Path> files = Files.list(folder)) {
            Path copy = files.filter(p -> p.getFileName().toString().startsWith("weekly-nightmare.yml.broken-")).findFirst().orElseThrow(() -> new AssertionError("no copy"));
            assertEquals(BROKEN, Files.readString(copy, StandardCharsets.UTF_8));
        }
    }
}
