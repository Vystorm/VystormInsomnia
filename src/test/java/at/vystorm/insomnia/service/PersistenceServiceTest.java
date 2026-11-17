package at.vystorm.insomnia.service;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugInterface;
import Vystorm.vystorm_core.storage.VersionedStore;
import at.vystorm.insomnia.core.CoreBridge;
import at.vystorm.insomnia.model.PlayerFatigueState;
import at.vystorm.insomnia.storage.PlayerFileStore;
import java.io.File;
import java.util.Map;
import java.util.UUID;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PersistenceServiceTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"null-map", "null-record", "missing-uuid", "wrong-uuid"})
    void malformedPlayerLedgerStopsLoadingWithoutOverwriting(String corruption) {
        MemoryBackend backend = new MemoryBackend();
        PersistenceService writer = storage(backend);
        writer.loadStorage();
        UUID id = UUID.randomUUID();
        writer.save(new PlayerFatigueState(id, 1_000L), true);
        var json = com.google.gson.JsonParser.parseString(backend.value).getAsJsonObject();
        var data = json.getAsJsonObject("data");
        var players = data.getAsJsonObject("players");
        switch (corruption) {
            case "null-map" -> data.add("players", com.google.gson.JsonNull.INSTANCE);
            case "null-record" -> players.add(id.toString(), com.google.gson.JsonNull.INSTANCE);
            case "missing-uuid" -> players.getAsJsonObject(id.toString()).remove("uuid");
            case "wrong-uuid" -> players.getAsJsonObject(id.toString()).addProperty("uuid", UUID.randomUUID().toString());
            default -> throw new AssertionError(corruption);
        }
        backend.value = json.toString();
        String original = backend.value;
        int writes = backend.writes;
        assertThrows(IllegalStateException.class, () -> storage(backend).loadStorage());
        assertEquals(original, backend.value);
        assertEquals(writes, backend.writes);
    }

    /** In-memory stand-in for the Core data entry behind {@link VersionedStore}. */
    private static final class MemoryBackend implements VersionedStore.Backend {
        private String value;
        private int writes;
        @Override public String read() { return value; }
        @Override public void write(String value) { this.value = value; writes++; }
    }

    @Test
    void roundTripsPersistentPlayerFieldsThroughTheVersionedStore() {
        MemoryBackend backend = new MemoryBackend();
        PersistenceService writer = storage(backend);
        writer.loadStorage();
        UUID player = UUID.randomUUID();
        PlayerFatigueState saved = new PlayerFatigueState(player, 1_000L);
        saved.fatigue(91.5);
        saved.sleepDebt(72.25);
        saved.stimulantSuppression(12.0);
        saved.stimulantCrashPending(4.5);
        saved.stimulantUses(3);
        saved.stimulantUntilEpochMillis(9_000L);
        saved.awakeSinceEpochMillis(2_000L);
        saved.lastSleepEpochMillis(3_000L);
        saved.lastMicrosleepEpochMillis(4_000L);
        saved.restedUntilEpochMillis(5_000L);

        int before = backend.writes;
        writer.save(saved, true);
        assertEquals(before + 1, backend.writes, "one save writes the document once");
        assertTrue(backend.value.contains("\"schema\":" + PlayerFileStore.SCHEMA));

        PersistenceService reader = storage(backend);
        reader.loadStorage();
        PlayerFatigueState loaded = reader.load(player, 99_000L);

        assertEquals(91.5, loaded.rawFatigue());
        assertEquals(72.25, loaded.sleepDebt());
        assertEquals(12.0, loaded.stimulantSuppression());
        assertEquals(4.5, loaded.stimulantCrashPending());
        assertEquals(3, loaded.stimulantUses());
        assertEquals(9_000L, loaded.stimulantUntilEpochMillis());
        assertEquals(2_000L, loaded.awakeSinceEpochMillis());
        assertEquals(99_000L, loaded.lastUpdateEpochMillis());
        assertEquals(3_000L, loaded.lastSleepEpochMillis());
        assertEquals(4_000L, loaded.lastMicrosleepEpochMillis());
        assertEquals(5_000L, loaded.restedUntilEpochMillis());
        assertFalse(loaded.dirty());
    }

    @Test
    void unknownPlayerGetsACleanFreshState() {
        PersistenceService persistence = storage(new MemoryBackend());
        persistence.loadStorage();
        PlayerFatigueState loaded = persistence.load(UUID.randomUUID(), 123_000L);
        assertEquals(0.0, loaded.rawFatigue());
        assertEquals(123_000L, loaded.awakeSinceEpochMillis());
        assertFalse(loaded.dirty());
    }

    @Test
    void unreadableStoreStopsTheStartInsteadOfStartingEmpty() {
        MemoryBackend backend = new MemoryBackend();
        backend.value = "{not json";
        PersistenceService persistence = storage(backend);
        assertThrows(IllegalStateException.class, persistence::loadStorage);
        assertEquals("{not json", backend.value, "the unreadable content is never overwritten");
    }

    private static PersistenceService storage(MemoryBackend backend) {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getDataFolder()).thenReturn(new File("build/test-persistence"));
        API api = mock(API.class);
        when(api.debug()).thenReturn(mock(DebugInterface.class));
        return new PersistenceService(plugin, new CoreBridge(api), () -> new PlayerFileStore(new VersionedStore<>(backend,
                PlayerFileStore.PlayerFile.class, PlayerFileStore.SCHEMA, Map.of(), PlayerFileStore.PlayerFile::new, null)));
    }
}
