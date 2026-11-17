package at.vystorm.insomnia.testutil;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

/**
 * Local MariaDB/MySQL for integration tests, like Core's SharedDatabaseIntegrationTest: only when
 * VYSTORM_TEST_DB_HOST (127.0.0.1/localhost only), _PORT, _NAME, _USER and _PASSWORD are set.
 */
public final class TestDatabase implements DataSource {
    private final String url;
    private final String user;
    private final String password;

    private TestDatabase(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
    }

    /** null when the variables are missing (the test is then skipped). */
    public static TestDatabase fromEnvironment() {
        String host = System.getenv("VYSTORM_TEST_DB_HOST");
        String port = System.getenv("VYSTORM_TEST_DB_PORT");
        String name = System.getenv("VYSTORM_TEST_DB_NAME");
        String user = System.getenv("VYSTORM_TEST_DB_USER");
        String password = System.getenv("VYSTORM_TEST_DB_PASSWORD");
        if (host == null || port == null || name == null || user == null || password == null) return null;
        if (!host.equals("127.0.0.1") && !host.equalsIgnoreCase("localhost")) {
            throw new IllegalStateException("Integration tests only run against a local database, not " + host);
        }
        return new TestDatabase("jdbc:mysql://" + host + ":" + port + "/" + name + "?sslMode=DISABLED&allowPublicKeyRetrieval=true",
                user, password);
    }

    /** A random table prefix per test so parallel runs never share tables. */
    public static String randomPrefix() {
        StringBuilder out = new StringBuilder("it");
        for (int i = 0; i < 6; i++) out.append((char) ('a' + ThreadLocalRandom.current().nextInt(26)));
        return out.toString().toLowerCase(Locale.ROOT);
    }

    /** Drops the tables of {@code prefix} and its schema version row. */
    public void drop(String prefix, String... tables) throws SQLException {
        try (Connection connection = getConnection(); Statement statement = connection.createStatement()) {
            for (String table : tables) statement.execute("DROP TABLE IF EXISTS " + prefix + "_" + table);
            statement.execute("DELETE FROM vystorm_schema_versions WHERE owner = '" + prefix + "'");
        }
    }

    @Override public Connection getConnection() throws SQLException { return DriverManager.getConnection(url, user, password); }
    @Override public Connection getConnection(String u, String p) throws SQLException { return DriverManager.getConnection(url, u, p); }
    @Override public PrintWriter getLogWriter() { return null; }
    @Override public void setLogWriter(PrintWriter out) { }
    @Override public void setLoginTimeout(int seconds) { }
    @Override public int getLoginTimeout() { return 0; }
    @Override public Logger getParentLogger() { return Logger.getGlobal(); }
    @Override public <T> T unwrap(Class<T> type) throws SQLException { throw new SQLException("not a wrapper"); }
    @Override public boolean isWrapperFor(Class<?> type) { return false; }
}
