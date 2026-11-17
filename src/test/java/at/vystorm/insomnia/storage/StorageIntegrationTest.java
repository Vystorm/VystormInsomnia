package at.vystorm.insomnia.storage;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core._coreMethods.dataStorage.DataStorageInterface;
import Vystorm.vystorm_core._coreMethods.dataStorage.methods.DataEncoder;
import Vystorm.vystorm_core._coreMethods.dataStorage.methods.Loader;
import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugInterface;
import at.vystorm.insomnia.core.CoreBridge;
import at.vystorm.insomnia.model.PlayerFatigueState;
import at.vystorm.insomnia.service.PersistenceService;
import at.vystorm.insomnia.testutil.TestDatabase;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Against a local MariaDB (see {@link TestDatabase}); skipped without VYSTORM_TEST_DB_* variables. */
class StorageIntegrationTest {
    private TestDatabase db;
    private String prefix;

    @BeforeEach
    void setUp() {
        db = TestDatabase.fromEnvironment();
        assumeTrue(db != null, "VYSTORM_TEST_DB_* not set");
        prefix = TestDatabase.randomPrefix();
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (db != null) db.drop(prefix, "players", "imports");
    }

    private SqlPlayerStore store(String server) throws SQLException {
        SqlPlayerStore store = new SqlPlayerStore(mock(Vystorm.vystorm_core._coreMethods.dataBaseHandler.DataBaseInterface.class, org.mockito.Mockito.CALLS_REAL_METHODS), db, prefix, server, UUID.randomUUID().toString());
        store.migrate();
        return store;
    }

    private static PlayerRecord record(UUID uuid, double fatigue) {
        return new PlayerRecord(uuid, fatigue, 12.5, 3.0, 1.5, 2, 9_000L, 2_000L, 3_000L, 4_000L, 5_000L);
    }

    @Test
    void importsYamlOnceIntoAnEmptyTable() throws SQLException {
        SqlPlayerStore store = store("a");
        UUID one = UUID.randomUUID();
        UUID two = UUID.randomUUID();
        assertEquals(SqlPlayerStore.ImportResult.IMPORTED, store.importOnce(List.of(record(one, 40), record(two, 60)), 1L));
        assertEquals(SqlPlayerStore.ImportResult.ALREADY_IMPORTED, store.importOnce(List.of(record(UUID.randomUUID(), 1)), 2L));
        assertEquals(2, count("SELECT COUNT(*) FROM " + prefix + "_players"));
        assertEquals(2, count("SELECT rows_imported FROM " + prefix + "_imports WHERE source = '" + PlayerFileStore.FILE + "'"));

        SqlPlayerStore.Claim claim = store.claim(one, 10L, false);
        assertEquals(record(one, 40), claim.record());
    }

    @Test
    void doesNotImportIntoATableThatAlreadyHasPlayers() throws SQLException {
        SqlPlayerStore store = store("a");
        store.claim(UUID.randomUUID(), 1L, false);
        assertEquals(SqlPlayerStore.ImportResult.TABLE_NOT_EMPTY, store.importOnce(List.of(record(UUID.randomUUID(), 5)), 2L));
        assertEquals(0, count("SELECT COUNT(*) FROM " + prefix + "_imports"));
    }

    @Test
    void newPlayerGetsAFreshRowAndWritesRoundTrip() throws SQLException {
        SqlPlayerStore store = store("a");
        UUID uuid = UUID.randomUUID();
        PlayerRecord fresh = store.claim(uuid, 7_000L, false).record();
        assertEquals(0.0, fresh.fatigue());
        assertEquals(7_000L, fresh.awakeSince());

        assertTrue(store.write(List.of(record(uuid, 77.25)), Set.of(), 8_000L).isEmpty());
        assertEquals(record(uuid, 77.25), store.claim(uuid, 9_000L, false).record());
    }

    @Test
    void secondServerWaitsForTheClaimAndCannotOverwrite() throws SQLException {
        SqlPlayerStore a = store("a");
        SqlPlayerStore b = store("b");
        UUID uuid = UUID.randomUUID();
        a.claim(uuid, 1L, false);

        SqlPlayerStore.Claim busy = b.claim(uuid, 2L, false);
        assertTrue(busy.busy());
        assertEquals("a", busy.heldBy());
        assertEquals(List.of(uuid), b.write(List.of(record(uuid, 99)), Set.of(), 3L), "b must not write while a holds the player");

        assertTrue(a.write(List.of(record(uuid, 50)), Set.of(uuid), 4L).isEmpty()); // quit on a
        SqlPlayerStore.Claim claimed = b.claim(uuid, 5L, false);
        assertFalse(claimed.busy());
        assertEquals(50.0, claimed.record().fatigue());
        assertEquals(List.of(uuid), a.write(List.of(record(uuid, 10)), Set.of(), 6L), "a lost the player");
    }

    @Test
    void forceTakesOverAndReleaseStaleFreesOldRuns() throws SQLException {
        SqlPlayerStore crashed = store("survival");
        UUID uuid = UUID.randomUUID();
        crashed.claim(uuid, 1L, false);
        SqlPlayerStore restarted = store("survival");
        assertTrue(restarted.claim(uuid, 2L, false).busy());
        assertEquals(1, restarted.releaseStale());
        assertFalse(restarted.claim(uuid, 3L, false).busy());

        SqlPlayerStore other = store("lobby");
        assertTrue(other.claim(uuid, 4L, false).busy());
        assertFalse(other.claim(uuid, 5L, true).busy());
        other.release(uuid);
        assertEquals(0, count("SELECT COUNT(*) FROM " + prefix + "_players WHERE claim_token IS NOT NULL"));
    }

    @Test
    void persistenceServiceHandsPlayersOverBetweenServers() throws Exception {
        PersistenceService a = service("a", CompletableFuture.completedFuture(Optional.of(db)));
        PersistenceService b = service("b", CompletableFuture.completedFuture(Optional.of(db)));
        try {
            UUID uuid = UUID.randomUUID();
            PlayerFatigueState onA = load(a, uuid);
            onA.fatigue(66.0);
            a.save(onA, true);

            CompletableFuture<PlayerFatigueState> onB = new CompletableFuture<>();
            b.loadAsync(uuid, onB::complete); // player switches: b connects before a saw the quit
            TimeUnit.MILLISECONDS.sleep(800);
            assertFalse(onB.isDone(), "b must wait while a still holds the player");
            onA.sleepDebt(33.0);
            a.saveAndRelease(onA);

            PlayerFatigueState loaded = onB.get(5, TimeUnit.SECONDS);
            assertEquals(66.0, loaded.rawFatigue());
            assertEquals(33.0, loaded.sleepDebt(), "b sees the quit save, not the older flush");
        } finally {
            a.shutdown();
            b.shutdown();
        }
    }

    @Test
    void mysqlModeReportsAMissingPoolInsteadOfFallingBack() throws Exception {
        CompletableFuture<String> failure = new CompletableFuture<>();
        PersistenceService service = PersistenceService.mysql(plugin(), core(), NO_FILES, settings("a"),
                CompletableFuture.completedFuture(Optional.empty()), Runnable::run, failure::complete);
        service.loadStorage();
        assertTrue(failure.get(5, TimeUnit.SECONDS).contains("shared database"));
        service.shutdown();
    }

    private PersistenceService service(String server, CompletableFuture<Optional<DataSource>> pool) {
        PersistenceService service = PersistenceService.mysql(plugin(), core(), NO_FILES, settings(server), pool, Runnable::run,
                reason -> fail("storage failed: " + reason));
        service.loadStorage();
        return service;
    }

    private static PlayerFatigueState load(PersistenceService service, UUID uuid) throws Exception {
        CompletableFuture<PlayerFatigueState> loaded = new CompletableFuture<>();
        service.loadAsync(uuid, loaded::complete);
        return loaded.get(5, TimeUnit.SECONDS);
    }

    /** The test data folders never contain a file store, so it must not be opened. */
    private static final java.util.concurrent.Callable<PlayerFileStore> NO_FILES = () -> {
        throw new AssertionError("file store must not be opened");
    };

    private StorageSettings settings(String server) {
        return new StorageSettings(StorageSettings.Type.MYSQL, prefix, server, 10);
    }

    private static Plugin plugin() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getDataFolder()).thenReturn(new File("build/test-storage-" + UUID.randomUUID()));
        return plugin;
    }

    private static CoreBridge core() {
        API api = mock(API.class);
        DataStorageInterface data = mock(DataStorageInterface.class);
        Loader loader = mock(Loader.class);
        when(api.data()).thenReturn(data);
        when(api.debug()).thenReturn(mock(DebugInterface.class));
        when(api.database()).thenReturn(mock(Vystorm.vystorm_core._coreMethods.dataBaseHandler.DataBaseInterface.class, org.mockito.Mockito.CALLS_REAL_METHODS));
        when(data.dataEncoder()).thenReturn(new DataEncoder());
        when(data.loader()).thenReturn(loader);
        when(loader.fileExists(any(Plugin.class), any(Path.class))).thenReturn(false);
        return new CoreBridge(api);
    }

    private long count(String sql) throws SQLException {
        try (Connection connection = db.getConnection(); Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
