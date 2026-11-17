package at.vystorm.insomnia.compat;

import Vystorm.vystorm_core.compat.ServerCompat;
import at.vystorm.insomnia.sleep.DialogSupport;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

class PaperCompatibilityTest {
    private URLClassLoader legacyLoader() throws Exception {
        var urls = new ArrayList<URL>();
        for (String property : new String[]{"compat1206.mainClasses", "compat1206.classpath"})
            for (String entry : System.getProperty(property).split(File.pathSeparator))
                urls.add(Path.of(entry).toUri().toURL());
        return new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
    }

    @Test void currentPaperSelectsNativeDialogsWithoutFloodgate() {
        assertTrue(ServerCompat.hasDialogs());
        assertTrue(DialogSupport.supported(Vystorm.vystorm_core.compat.ServerCompatInterface.DEFAULT, null));
    }

    @Test void oldPaperSelectsChatAndLegacyAttributes() throws Exception {
        try (var loader = legacyLoader()) {
            var compat = loader.loadClass("Vystorm.vystorm_core.compat.ServerCompat");
            assertEquals(false, compat.getMethod("hasDialogs").invoke(null));
            var support = loader.loadClass("at.vystorm.insomnia.sleep.DialogSupport");
            var serverPart = loader.loadClass("Vystorm.vystorm_core.compat.ServerCompatInterface");
            assertEquals(false, support.getMethod("supported", serverPart, loader.loadClass("org.bukkit.entity.Player"))
                    .invoke(null, serverPart.getField("DEFAULT").get(null), null));
            for (String name : new String[]{"MAX_HEALTH", "ATTACK_DAMAGE"}) {
                Object attribute = compat.getMethod("attribute", String.class, String.class).invoke(null, name, "GENERIC_" + name);
                assertEquals("GENERIC_" + name, ((Enum<?>) attribute).name());
            }
        }
    }

    @Test void shippedClassesHaveJava21BytecodeAndLoadWithOldPaper() throws Exception {
        try (var loader = legacyLoader()) {
            for (String directory : System.getProperty("compat1206.mainClasses").split(File.pathSeparator)) {
                Path root = Path.of(directory);
                try (var files = Files.walk(root)) {
                    for (Path file : files.filter(p -> p.toString().endsWith(".class")).toList()) {
                        byte[] bytes = Files.readAllBytes(file);
                        assertEquals(65, (bytes[6] & 255) * 256 + (bytes[7] & 255), file.toString());
                        String name = root.relativize(file).toString().replace(File.separatorChar, '.').replaceAll("\\.class$", "");
                        Class<?> type = Class.forName(name, false, loader);
                        assertDoesNotThrow(() -> {
                            type.getDeclaredConstructors();
                            type.getDeclaredMethods();
                            type.getDeclaredFields();
                        }, name);
                    }
                }
            }
        }
    }
}
