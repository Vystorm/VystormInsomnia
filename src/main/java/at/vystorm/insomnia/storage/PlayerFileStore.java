package at.vystorm.insomnia.storage;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core.storage.VersionedStore;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * File storage of the player records ({@code storage.type: yaml}): one Core {@link VersionedStore} in
 * {@code data/fatigue.yml} (JSON with schema version, crash-safe write, unreadable content is backed up and stops the
 * start instead of being overwritten). Replaces the per-field {@code data/players.yml} of 0.4.x.
 */
public final class PlayerFileStore {
    public static final String FOLDER = "data";
    public static final String FILE = "fatigue.yml";
    /** Schema of {@link PlayerFile}; raise it and add a migration step when the record changes. */
    public static final int SCHEMA = 1;

    /** Persisted document: player UUID (string) → record. */
    public static final class PlayerFile {
        public Map<String, PlayerRecord> players = new LinkedHashMap<>();
    }

    private final VersionedStore<PlayerFile> store;
    private final PlayerFile state;
    private boolean pending;

    /** Loads the document right away. @throws VersionedStore.CorruptStoreException when it is unreadable */
    public PlayerFileStore(VersionedStore<PlayerFile> store) {
        this.store = Objects.requireNonNull(store, "store");
        PlayerFile loaded = store.load();
        if (loaded == null || loaded.players == null) throw new IllegalStateException("Invalid fatigue player ledger");
        for (Map.Entry<String, PlayerRecord> entry : loaded.players.entrySet()) {
            PlayerRecord record = entry.getValue();
            if (record == null || record.uuid() == null || !record.uuid().toString().equals(entry.getKey()))
                throw new IllegalStateException("Invalid fatigue player record: " + entry.getKey());
        }
        this.state = loaded;
    }

    public static PlayerFileStore open(JavaPlugin plugin, API core) throws Exception {
        return new PlayerFileStore(core.data().versioned().open(plugin, core, FOLDER, FILE, PlayerFile.class, SCHEMA, Map.of(), PlayerFile::new));
    }

    public static boolean exists(Path dataFolder) {
        return Files.isRegularFile(dataFolder.resolve(FOLDER).resolve(FILE));
    }

    /** The stored record, or {@code null} when the player has none. */
    public PlayerRecord read(UUID uuid) {
        PlayerRecord record = state.players.get(uuid.toString());
        return record == null || !uuid.equals(record.uuid()) ? null : record;
    }

    /** Every stored record (for the one-time MySQL import). */
    public List<PlayerRecord> readAll() {
        List<PlayerRecord> out = new ArrayList<>();
        for (Map.Entry<String, PlayerRecord> entry : state.players.entrySet()) {
            PlayerRecord record = entry.getValue();
            if (record != null && record.uuid() != null && record.uuid().toString().equals(entry.getKey())) out.add(record);
        }
        return out;
    }

    /** Puts the records and saves the document once (together with any records only {@link #put} so far). */
    public void write(Collection<PlayerRecord> records) {
        if (records.isEmpty() && !pending) return;
        for (PlayerRecord record : records) state.players.put(record.uuid().toString(), record);
        store.save(state);
        pending = false;
    }

    /**
     * Puts the records in memory without writing the file; {@link #read} sees them at once, the next {@link #write}
     * or {@link #flush} saves them. Used for players who leave, so many quits in a row cost one write.
     */
    public void put(Collection<PlayerRecord> records) {
        if (records.isEmpty()) return;
        for (PlayerRecord record : records) state.players.put(record.uuid().toString(), record);
        pending = true;
    }

    /** True while records given to {@link #put} are not saved yet. */
    public boolean pending() { return pending; }

    /** Saves the document if {@link #put} left records unsaved. */
    public void flush() {
        if (pending) write(List.of());
    }
}
