package at.vystorm.insomnia.testutil;

import org.bukkit.Bukkit;
import org.bukkit.Server;

import java.lang.reflect.Field;
import java.util.List;
import java.util.logging.Logger;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/** One mocked Bukkit server per test JVM; {@link #fresh()} resets its stubs for the next test. */
public final class TestServer {
    private static Server server;

    private TestServer() {}

    public static synchronized Server fresh() {
        if (server == null) {
            server = mock(Server.class);
            try {
                Field field = Bukkit.class.getDeclaredField("server");
                field.setAccessible(true);
                field.set(null, server);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(failure);
            }
        }
        reset(server);
        when(server.getLogger()).thenReturn(Logger.getLogger("insomnia-test"));
        when(server.getOnlinePlayers()).thenAnswer(ignored -> List.of());
        return server;
    }
}
