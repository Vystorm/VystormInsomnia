package at.vystorm.insomnia.service;

import at.vystorm.insomnia.core.CoreBridge;
import at.vystorm.insomnia.model.PlayerFatigueState;
import at.vystorm.insomnia.storage.PlayerRecord;
import at.vystorm.insomnia.storage.SqlPlayerStore;
import at.vystorm.insomnia.storage.StorageSettings;
import at.vystorm.insomnia.storage.PlayerFileStore;
import org.bukkit.plugin.Plugin;

import javax.sql.DataSource;
import java.util.concurrent.Callable;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Player fatigue persistence. File mode ({@code storage.type: yaml}, Core {@code VersionedStore} in
 * {@code data/fatigue.yml}, see {@link PlayerFileStore}) reads and writes synchronously.
 * MySQL mode uses Core's shared pool: every database call runs on one storage thread (so writes and loads keep
 * their order), results come back through {@code mainThread}.
 */
public final class PersistenceService {
    private static final int WRITE_ATTEMPTS = 3;
    private static final long LOAD_RETRY_MILLIS = 500L;
    private static final int LOAD_FAILURE_ATTEMPTS = 5;
    /** File mode: how long the record of a player who left may wait in memory before data/fatigue.yml is written. */
    static final long QUIT_WRITE_DELAY_MILLIS = 5_000L;
    private long pendingSince;

    private final Plugin plugin;
    private final CoreBridge core;
    /** Opens the file store (file mode) or the one to import from (MySQL mode, only if the file exists). */
    private final Callable<PlayerFileStore> files;
    private PlayerFileStore yaml;

    // MySQL mode only
    private final StorageSettings settings;
    private final CompletableFuture<Optional<DataSource>> pool;
    private final Executor mainThread;
    private final Consumer<String> onFailure;
    private final String token = UUID.randomUUID().toString();
    private ScheduledExecutorService io;
    private CompletableFuture<SqlPlayerStore> ready;

    /** File mode. */
    public PersistenceService(Plugin plugin, CoreBridge core, Callable<PlayerFileStore> files) {
        this(plugin, core, files, null, null, null, null);
    }

    private PersistenceService(Plugin plugin, CoreBridge core, Callable<PlayerFileStore> files, StorageSettings settings,
                               CompletableFuture<Optional<DataSource>> pool, Executor mainThread, Consumer<String> onFailure) {
        this.plugin = plugin;
        this.core = core;
        this.files = files;
        this.settings = settings;
        this.pool = pool;
        this.mainThread = mainThread;
        this.onFailure = onFailure;
    }

    /**
     * MySQL mode on Core's shared pool.
     *
     * @param onFailure runs on the main thread with a reason when the database cannot be used; nothing is written then
     */
    public static PersistenceService mysql(Plugin plugin, CoreBridge core, Callable<PlayerFileStore> files, StorageSettings settings,
                                           CompletableFuture<Optional<DataSource>> pool, Executor mainThread,
                                           Consumer<String> onFailure) {
        return new PersistenceService(plugin, core, files, settings, pool, mainThread, onFailure);
    }

    /** true = YAML: {@link #load} works on the calling thread. false = MySQL: use {@link #loadAsync}. */
    public boolean synchronous() { return settings == null; }

    /** @throws IllegalStateException when the file store cannot be opened or is unreadable (start is aborted) */
    public void loadStorage() {
        if (synchronous()) {
            yaml = openFiles();
            return;
        }
        // MySQL: the file store is only read for the one-time import and never written.
        List<PlayerRecord> legacy = new ArrayList<>();
        if (PlayerFileStore.exists(plugin.getDataFolder().toPath())) legacy.addAll(openFiles().readAll());
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "VystormInsomnia-DB");
            thread.setDaemon(true);
            return thread;
        });
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false); // pending load retries die with the plugin
        io = executor;
        ready = pool.thenApplyAsync(source -> open(source, legacy), io);
        ready.whenComplete((store, failure) -> {
            if (failure == null) return;
            Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
            String reason = cause.getClass().getSimpleName() + ": " + cause.getMessage();
            core.high("MySQL storage unavailable (" + reason + "). storage.type is mysql, so nothing is written to "
                    + "the file store instead; fix Core's database.shared and restart.");
            mainThread.execute(() -> onFailure.accept(reason));
        });
    }

    private SqlPlayerStore open(Optional<DataSource> source, List<PlayerRecord> legacy) {
        DataSource dataSource = source.orElseThrow(() -> new IllegalStateException(
                "Core's shared database is disabled or failed (database.shared in plugins/vystorm_core/config.yml)"));
        SqlPlayerStore store = new SqlPlayerStore(core.api().database(), dataSource, settings.tablePrefix(), settings.serverId(), token);
        try {
            store.migrate();
            SqlPlayerStore.ImportResult result = null;
            SQLException last = null;
            for (int attempt = 0; attempt < 3 && result == null; attempt++) {
                try {
                    result = store.importOnce(legacy, System.currentTimeMillis());
                } catch (SQLException failure) { // another server importing at the same time
                    last = failure;
                    pause(1000L);
                }
            }
            if (result == null) throw last;
            switch (result) {
                case IMPORTED -> core.mid("Imported " + legacy.size() + " players from " + PlayerFileStore.FILE + " into "
                        + store.playersTable() + " (the file stays as backup)");
                case TABLE_NOT_EMPTY -> {
                    if (!legacy.isEmpty()) core.mid(PlayerFileStore.FILE + " not imported: " + store.playersTable() + " already has data");
                }
                default -> { }
            }
            if (!StorageSettings.DEFAULT_SERVER_ID.equals(settings.serverId())) {
                int freed = store.releaseStale();
                if (freed > 0) core.mid("Released " + freed + " player claims left by an earlier run of server " + settings.serverId());
            }
            core.mid("MySQL storage ready (" + store.playersTable() + ", server-id " + settings.serverId() + ")");
            return store;
        } catch (SQLException failure) {
            throw new CompletionException(failure);
        }
    }

    private PlayerFileStore openFiles() {
        try {
            return files.call();
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("Insomnia player storage could not be opened: " + failure.getMessage(), failure);
        }
    }

    /** YAML only: reads the player on the calling thread. */
    public PlayerFatigueState load(UUID uuid, long now) {
        if (!synchronous()) throw new IllegalStateException("MySQL storage loads asynchronously");
        requireLoaded();
        PlayerRecord record = yaml.read(uuid);
        return record == null ? PlayerRecord.fresh(uuid, now) : record.toState(now);
    }

    /**
     * MySQL: claims and reads the player off the main thread, then hands the state to {@code loaded} on the main
     * thread. While another server still holds the player (server switch), it retries for
     * {@code storage.claim-wait-seconds} and then takes over. YAML: loads and calls back immediately.
     */
    public void loadAsync(UUID uuid, Consumer<PlayerFatigueState> loaded) {
        if (synchronous()) {
            loaded.accept(load(uuid, System.currentTimeMillis()));
            return;
        }
        long deadline = System.currentTimeMillis() + settings.claimWaitSeconds() * 1000L;
        ready.thenAcceptAsync(store -> attemptLoad(store, uuid, deadline, 0, false, loaded), io);
    }

    private void attemptLoad(SqlPlayerStore store, UUID uuid, long deadline, int failures, boolean waitLogged,
                             Consumer<PlayerFatigueState> loaded) {
        long now = System.currentTimeMillis();
        try {
            SqlPlayerStore.Claim claim = store.claim(uuid, now, now >= deadline);
            if (claim.busy()) {
                if (!waitLogged) core.mid("Player " + uuid + " is still held by server " + claim.heldBy() + ", waiting for its save");
                io.schedule(() -> attemptLoad(store, uuid, deadline, failures, true, loaded), LOAD_RETRY_MILLIS, TimeUnit.MILLISECONDS);
                return;
            }
            if (now >= deadline && waitLogged) core.high("Took over player " + uuid + " from another server after "
                    + settings.claimWaitSeconds() + " s; its last unsaved changes may be lost");
            PlayerRecord record = claim.record();
            mainThread.execute(() -> loaded.accept(record.toState(System.currentTimeMillis())));
        } catch (SQLException | RuntimeException failure) {
            if (failures + 1 >= LOAD_FAILURE_ATTEMPTS) {
                core.high("Loading player " + uuid + " failed, Insomnia stays inactive for them until they rejoin: " + failure.getMessage());
                return;
            }
            io.schedule(() -> attemptLoad(store, uuid, deadline, failures + 1, waitLogged, loaded), 2L, TimeUnit.SECONDS);
        }
    }

    /** Writes the state; with {@code flush} it is persisted right away (MySQL: queued for the storage thread). */
    public void save(PlayerFatigueState state, boolean flush) {
        if (synchronous()) {
            requireLoaded();
            if (flush) {
                yaml.write(List.of(PlayerRecord.of(state)));
                state.clearDirty();
            }
            // Without flush the state stays dirty and the next periodic flush writes it.
            return;
        }
        if (flush) submit(List.of(state), Set.of());
    }

    /** Final save when the player leaves; MySQL also releases this server's claim so the next server can load. */
    public void saveAndRelease(PlayerFatigueState state) {
        if (synchronous()) {
            // File mode: the record is kept in memory and written with the next write, at the latest
            // QUIT_WRITE_DELAY_MILLIS later (flushPending), so a wave of quits rewrites data/fatigue.yml once.
            requireLoaded();
            if (!yaml.pending()) pendingSince = System.currentTimeMillis();
            yaml.put(List.of(PlayerRecord.of(state)));
            state.clearDirty();
            return;
        }
        submit(List.of(state), Set.of(state.playerUuid()));
    }

    /**
     * File mode: writes the records of players who left once the oldest of them waited
     * {@link #QUIT_WRITE_DELAY_MILLIS}. Called every second from the runtime task. MySQL: nothing to do.
     */
    public void flushPending(long now) {
        if (!synchronous() || yaml == null || !yaml.pending()) return;
        if (now - pendingSince >= QUIT_WRITE_DELAY_MILLIS) yaml.flush();
    }

    /** MySQL: drops the claim of a player whose load finished after they left. */
    public void release(UUID uuid) {
        if (synchronous() || ready == null) return;
        ready.thenAcceptAsync(store -> {
            try { store.release(uuid); }
            catch (SQLException failure) { core.high("Releasing player " + uuid + " failed: " + failure.getMessage()); }
        }, io);
    }

    public void flushDirty(Collection<PlayerFatigueState> states) {
        List<PlayerFatigueState> dirty = new ArrayList<>();
        for (PlayerFatigueState state : states) if (state.dirty()) dirty.add(state);
        if (dirty.isEmpty()) return;
        if (synchronous()) {
            requireLoaded();
            yaml.write(dirty.stream().map(PlayerRecord::of).toList());
            dirty.forEach(PlayerFatigueState::clearDirty);
            return;
        }
        submit(dirty, Set.of());
    }

    /** Shutdown save. MySQL: writes and releases every loaded player, then waits for the storage thread. */
    public void saveAll(Collection<PlayerFatigueState> states) {
        if (synchronous()) {
            requireLoaded();
            yaml.write(states.stream().map(PlayerRecord::of).toList());
            states.forEach(PlayerFatigueState::clearDirty);
            return;
        }
        Set<UUID> release = new HashSet<>();
        for (PlayerFatigueState state : states) release.add(state.playerUuid());
        if (!states.isEmpty()) submit(new ArrayList<>(states), release);
        shutdown();
    }

    /** MySQL: finishes queued writes (up to 15 s) and stops the storage thread. */
    public void shutdown() {
        if (io == null) return;
        io.shutdown();
        try {
            if (!io.awaitTermination(15, TimeUnit.SECONDS)) core.high("Storage thread did not finish within 15 s; recent changes may be lost");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Snapshots on the calling (main) thread, clears the dirty flags and writes on the storage thread. */
    private void submit(List<PlayerFatigueState> states, Set<UUID> release) {
        if (ready == null || !ready.isDone() || ready.isCompletedExceptionally()) {
            // Nobody can be loaded before the store is ready, so there is nothing of value to write.
            return;
        }
        List<PlayerRecord> records = states.stream().map(PlayerRecord::of).toList();
        states.forEach(PlayerFatigueState::clearDirty);
        SqlPlayerStore store = ready.join();
        Runnable task = () -> write(store, states, records, release);
        if (io.isShutdown()) return;
        io.execute(task);
    }

    private void write(SqlPlayerStore store, List<PlayerFatigueState> states, List<PlayerRecord> records, Set<UUID> release) {
        SQLException last = null;
        for (int attempt = 1; attempt <= WRITE_ATTEMPTS; attempt++) {
            try {
                List<UUID> rejected = store.write(records, release, System.currentTimeMillis());
                for (UUID uuid : rejected) {
                    core.high("Not saved: player " + uuid + " is now held by another server (claim taken over)");
                }
                return;
            } catch (SQLException failure) {
                last = failure;
                if (attempt < WRITE_ATTEMPTS) pause(1000L * attempt);
            }
        }
        core.high("Saving " + records.size() + " player(s) to MySQL failed: " + last.getMessage()
                + (release.isEmpty() ? "; retried at the next flush" : "; changes since the last save are lost"));
        if (release.isEmpty()) {
            try { mainThread.execute(() -> states.forEach(PlayerFatigueState::markDirty)); }
            catch (RuntimeException ignored) { } // plugin already disabled
        }
    }

    private static void pause(long millis) {
        try { Thread.sleep(millis); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    private void requireLoaded() {
        if (yaml == null) throw new IllegalStateException("Insomnia player storage is not loaded");
    }
}
