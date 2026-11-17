package at.vystorm.insomnia.storage;

import Vystorm.vystorm_core.database.SchemaMigrator;
import Vystorm.vystorm_core._coreMethods.dataBaseHandler.DataBaseInterface;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Player fatigue in the shared Vystorm database ({@code <prefix>_players}), plus a record of the one-time YAML import
 * ({@code <prefix>_imports}). Blocking JDBC: call only from the storage thread.
 *
 * <p>A server that loads a player <em>claims</em> the row (random token per plugin start). Other servers wait until
 * the claim is released on quit, so a player switching servers never starts from data that the previous server has
 * not written yet. Writes only succeed while this server holds the claim (or nobody does).
 */
public final class SqlPlayerStore {
    public static final String IMPORT_SOURCE = PlayerFileStore.FILE;

    private final DataSource dataSource;
    private final String prefix;
    private final String players;
    private final String imports;
    private final String serverId;
    private final String token;

    /** @param database Core's database manager, {@code api.database()}; it builds the shared table names */
    public SqlPlayerStore(DataBaseInterface database, DataSource dataSource, String prefix, String serverId, String token) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.prefix = prefix;
        this.players = database.sharedTableName(prefix, "players");
        this.imports = database.sharedTableName(prefix, "imports");
        this.serverId = serverId;
        this.token = token;
    }

    public String playersTable() { return players; }
    public String token() { return token; }

    public int migrate() throws SQLException {
        return new SchemaMigrator(prefix)
                .step(1, "CREATE TABLE IF NOT EXISTS " + players + " ("
                        + "uuid CHAR(36) NOT NULL PRIMARY KEY,"
                        + "fatigue DOUBLE NOT NULL DEFAULT 0,"
                        + "sleep_debt DOUBLE NOT NULL DEFAULT 0,"
                        + "stimulant_suppression DOUBLE NOT NULL DEFAULT 0,"
                        + "stimulant_crash_pending DOUBLE NOT NULL DEFAULT 0,"
                        + "stimulant_uses INT NOT NULL DEFAULT 0,"
                        + "stimulant_until BIGINT NOT NULL DEFAULT 0,"
                        + "awake_since BIGINT NOT NULL,"
                        + "last_sleep BIGINT NOT NULL DEFAULT 0,"
                        + "last_microsleep BIGINT NOT NULL DEFAULT 0,"
                        + "rested_until BIGINT NOT NULL DEFAULT 0,"
                        + "claim_token CHAR(36) NULL,"
                        + "claim_server VARCHAR(64) NULL,"
                        + "claim_at BIGINT NOT NULL DEFAULT 0,"
                        + "updated_at BIGINT NOT NULL,"
                        + "KEY idx_claim_server (claim_server)"
                        + ") ENGINE=InnoDB")
                .step(2, "CREATE TABLE IF NOT EXISTS " + imports + " ("
                        + "source VARCHAR(64) NOT NULL PRIMARY KEY,"
                        + "server_id VARCHAR(64) NOT NULL,"
                        + "rows_imported INT NOT NULL,"
                        + "imported_at BIGINT NOT NULL"
                        + ") ENGINE=InnoDB")
                .migrate(dataSource);
    }

    public enum ImportResult { IMPORTED, ALREADY_IMPORTED, TABLE_NOT_EMPTY, NOTHING_TO_IMPORT }

    /**
     * Copies the YAML records once: only if no import is recorded and the players table is empty. The rows and the
     * import record are written in one transaction.
     */
    public ImportResult importOnce(Collection<PlayerRecord> records, long now) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                ImportResult result = importIn(connection, records, now);
                if (result == ImportResult.IMPORTED) connection.commit();
                else connection.rollback();
                return result;
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private ImportResult importIn(Connection connection, Collection<PlayerRecord> records, long now) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT 1 FROM " + imports + " WHERE source = ? FOR UPDATE")) {
            ps.setString(1, IMPORT_SOURCE);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return ImportResult.ALREADY_IMPORTED;
            }
        }
        try (PreparedStatement ps = connection.prepareStatement("SELECT 1 FROM " + players + " LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) return ImportResult.TABLE_NOT_EMPTY;
        }
        if (records.isEmpty()) return ImportResult.NOTHING_TO_IMPORT;
        try (PreparedStatement ps = connection.prepareStatement("INSERT IGNORE INTO " + players
                + " (uuid, fatigue, sleep_debt, stimulant_suppression, stimulant_crash_pending, stimulant_uses,"
                + " stimulant_until, awake_since, last_sleep, last_microsleep, rested_until, updated_at)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
            for (PlayerRecord record : records) {
                ps.setString(1, record.uuid().toString());
                bindValues(ps, 2, record);
                ps.setLong(12, now);
                ps.addBatch();
            }
            ps.executeBatch();
        }
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO " + imports
                + " (source, server_id, rows_imported, imported_at) VALUES (?,?,?,?)")) {
            ps.setString(1, IMPORT_SOURCE);
            ps.setString(2, serverId);
            ps.setInt(3, records.size());
            ps.setLong(4, now);
            ps.executeUpdate();
        }
        return ImportResult.IMPORTED;
    }

    /** Result of {@link #claim}: the record, or the server that still holds the player. */
    public record Claim(PlayerRecord record, String heldBy) {
        public boolean busy() { return record == null; }
    }

    /**
     * Claims the player's row for this server and reads it; a missing row is created with a fresh state.
     * Without {@code force}, a claim held by another server is left alone and reported as busy.
     */
    public Claim claim(UUID uuid, long now, boolean force) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT IGNORE INTO " + players + " (uuid, awake_since, updated_at) VALUES (?,?,?)")) {
                    ps.setString(1, uuid.toString());
                    ps.setLong(2, now);
                    ps.setLong(3, now);
                    ps.executeUpdate();
                }
                PlayerRecord record;
                String heldToken;
                String heldBy;
                try (PreparedStatement ps = connection.prepareStatement("SELECT fatigue, sleep_debt, stimulant_suppression,"
                        + " stimulant_crash_pending, stimulant_uses, stimulant_until, awake_since, last_sleep, last_microsleep,"
                        + " rested_until, claim_token, claim_server FROM " + players + " WHERE uuid = ? FOR UPDATE")) {
                    ps.setString(1, uuid.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) throw new SQLException("row for " + uuid + " vanished");
                        record = new PlayerRecord(uuid, rs.getDouble(1), rs.getDouble(2), rs.getDouble(3), rs.getDouble(4),
                                rs.getInt(5), rs.getLong(6), rs.getLong(7), rs.getLong(8), rs.getLong(9), rs.getLong(10));
                        heldToken = rs.getString(11);
                        heldBy = rs.getString(12);
                    }
                }
                if (heldToken != null && !heldToken.equals(token) && !force) {
                    connection.rollback();
                    return new Claim(null, heldBy == null ? "?" : heldBy);
                }
                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE " + players + " SET claim_token = ?, claim_server = ?, claim_at = ? WHERE uuid = ?")) {
                    ps.setString(1, token);
                    ps.setString(2, serverId);
                    ps.setLong(3, now);
                    ps.setString(4, uuid.toString());
                    ps.executeUpdate();
                }
                connection.commit();
                return new Claim(record, null);
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    /**
     * Writes all records in one transaction; players in {@code release} lose this server's claim at the same time.
     *
     * @return players whose row is claimed by another server; their data was not written
     */
    public List<UUID> write(Collection<PlayerRecord> records, Set<UUID> release, long now) throws SQLException {
        List<UUID> rejected = new ArrayList<>();
        if (records.isEmpty()) return rejected;
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement ps = connection.prepareStatement("UPDATE " + players + " SET fatigue = ?, sleep_debt = ?,"
                    + " stimulant_suppression = ?, stimulant_crash_pending = ?, stimulant_uses = ?, stimulant_until = ?,"
                    + " awake_since = ?, last_sleep = ?, last_microsleep = ?, rested_until = ?, updated_at = ?,"
                    + " claim_token = IF(?, NULL, claim_token), claim_server = IF(?, NULL, claim_server)"
                    + " WHERE uuid = ? AND (claim_token IS NULL OR claim_token = ?)")) {
                for (PlayerRecord record : records) {
                    boolean releaseIt = release.contains(record.uuid());
                    bindValues(ps, 1, record);
                    ps.setLong(11, now);
                    ps.setBoolean(12, releaseIt);
                    ps.setBoolean(13, releaseIt);
                    ps.setString(14, record.uuid().toString());
                    ps.setString(15, token);
                    if (ps.executeUpdate() == 0) rejected.add(record.uuid());
                }
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        }
        return rejected;
    }

    /** Drops this server's claim without writing (player left before the load finished). */
    public void release(UUID uuid) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement("UPDATE " + players
                     + " SET claim_token = NULL, claim_server = NULL WHERE uuid = ? AND claim_token = ?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, token);
            ps.executeUpdate();
        }
    }

    /** Frees claims left behind by an earlier run (crash) of this server id. @return rows freed */
    public int releaseStale() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement("UPDATE " + players
                     + " SET claim_token = NULL, claim_server = NULL WHERE claim_server = ? AND claim_token <> ?")) {
            ps.setString(1, serverId);
            ps.setString(2, token);
            return ps.executeUpdate();
        }
    }

    private static void bindValues(PreparedStatement ps, int start, PlayerRecord record) throws SQLException {
        ps.setDouble(start, record.fatigue());
        ps.setDouble(start + 1, record.sleepDebt());
        ps.setDouble(start + 2, record.stimulantSuppression());
        ps.setDouble(start + 3, record.stimulantCrashPending());
        ps.setInt(start + 4, record.stimulantUses());
        ps.setLong(start + 5, record.stimulantUntil());
        ps.setLong(start + 6, record.awakeSince());
        ps.setLong(start + 7, record.lastSleep());
        ps.setLong(start + 8, record.lastMicrosleep());
        ps.setLong(start + 9, record.restedUntil());
    }
}
