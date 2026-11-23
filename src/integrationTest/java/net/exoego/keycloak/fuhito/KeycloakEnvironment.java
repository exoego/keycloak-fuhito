package net.exoego.keycloak.fuhito;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One Keycloak with the provider jar and one PostgreSQL, shared by all integration tests of a run.
 * Testcontainers removes the containers when the JVM exits.
 */
final class KeycloakEnvironment {

    private static final Network NETWORK = Network.newNetwork();

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18")
            .withNetwork(NETWORK)
            .withNetworkAliases("postgres")
            .withDatabaseName("keycloak")
            .withUsername("keycloak")
            .withPassword("keycloak");

    static final KeycloakContainer KEYCLOAK =
            Coverage.keycloak()
                    .withNetwork(NETWORK)
                    .withProviderLibsFrom(List.of(new File(System.getProperty("provider.jar"))))
                    .withEnv("KC_DB", "postgres")
                    .withEnv("KC_DB_URL", "jdbc:postgresql://postgres:5432/keycloak")
                    .withEnv("KC_DB_USERNAME", "keycloak")
                    .withEnv("KC_DB_PASSWORD", "keycloak")
                    // The timer would run the realms of the tests at times that they cannot know.
                    .withEnv("KC_SPI_ADMIN_REALM_RESTAPI_EXTENSION__FUHITO__PROJECTION_TIMER_ENABLED", "false")
                    // The cleanup of the change log removes only what a test made old, so a test can wait for it.
                    .withEnv("KC_SPI_ADMIN_REALM_RESTAPI_EXTENSION__FUHITO__CHANGE_LOG_CLEANUP_INTERVAL", "2");

    static {
        POSTGRES.start();
        KEYCLOAK.start();
        // Testcontainers kills the container after the JVM exits, so the data is read before that.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> Coverage.save(KEYCLOAK)));
    }

    private KeycloakEnvironment() {
    }

    /** Opens a connection to the database of Keycloak, for a test that holds a lock. */
    static Connection connection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /** Opens a connection to another database of the same server, for a test that makes its own schema. */
    static Connection connection(String database) throws SQLException {
        return DriverManager.getConnection("jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + database,
                POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /** Runs a statement and returns the first column of its first row, or an empty text. */
    static String sql(String sql) throws Exception {
        return run(POSTGRES, sql);
    }

    /** Runs a statement that must fail, and returns the error of the database. */
    static String sqlError(String sql) {
        return assertThrows(SQLException.class, () -> run(POSTGRES, sql)).getMessage();
    }

    // Starts a write, waits until the database makes it wait for the lock of the connection, and returns it.
    static <T> CompletableFuture<T> waitingFor(Connection holder, Callable<T> write) throws Exception {
        return waitingFor(pid(holder), write);
    }

    // The same for a lock of another backend of the database, such as one that waits itself. The cleanup of the
    // change log can wait for the removal of a realm at any time, so it does not count.
    static <T> CompletableFuture<T> waitingFor(String pid, Callable<T> write) throws Exception {
        String waiting = "select count(*) from pg_stat_activity where " + pid + " = any(pg_blocking_pids(pid))"
                + " and query not like '%for key share'";
        long before = Long.parseLong(sql(waiting));
        CompletableFuture<T> started = inBackground(write);
        long now = before;
        for (int i = 0; i < 300 && now == before; i++) {
            Thread.sleep(100);
            now = Long.parseLong(sql(waiting));
        }
        assertNotEquals(before, now, "the write never waited");
        return started;
    }

    // Each call gets a thread of its own. The common pool has one thread less than the machine has CPUs, and the
    // waiting writes of one test can take all of them.
    static <T> CompletableFuture<T> inBackground(Callable<T> call) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return call.call();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, task -> Thread.ofPlatform().daemon().start(task));
    }

    // The backend that waits for the lock of the connection, such as a write that waitingFor started.
    static String waiterOf(Connection holder) throws Exception {
        return sql("select pid from pg_stat_activity where " + pid(holder) + " = any(pg_blocking_pids(pid))");
    }

    private static String pid(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery("select pg_backend_pid()")) {
            row.next();
            return row.getString(1);
        }
    }

    // A connection that holds a row, as a write that has not ended does.
    static Connection holding(String table, String id) throws Exception {
        Connection connection = connection();
        connection.setAutoCommit(false);
        update(connection, "update " + table + " set version = version where id = ?", id);
        return connection;
    }

    // A connection that holds the lock of the writes of a realm, as a write that has not ended does. A bulk write
    // holds it alone.
    static Connection holdingRealm(String realmId, boolean alone) throws Exception {
        Connection connection = connection();
        connection.setAutoCommit(false);
        try (PreparedStatement statement = connection.prepareStatement(alone
                ? "select realm_id from fuhito_realm_write where realm_id = ? for no key update"
                : "select realm_id from fuhito_realm_write where realm_id = ? for share")) {
            statement.setString(1, realmId);
            try (ResultSet row = statement.executeQuery()) {
                assertTrue(row.next());
            }
        }
        return connection;
    }

    // The length of the log of Keycloak, so that a test reads only what comes after it.
    static int logLength() {
        return KEYCLOAK.getLogs().length();
    }

    // The deadlocks that PostgreSQL found since the log had the length. It ends each one with an error of one side.
    static long deadlocksSince(int logLength) {
        return KEYCLOAK.getLogs().substring(logLength).lines()
                .filter(line -> line.contains("deadlock detected"))
                .count();
    }

    static void update(Connection connection, String sql, String id) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            assertEquals(1, statement.executeUpdate());
        }
    }

    // JDBC, not psql in the container. A docker exec sometimes never reports that it ended.
    static String run(PostgreSQLContainer postgres, String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword());
                Statement statement = connection.createStatement()) {
            if (!statement.execute(sql)) {
                return "";
            }
            try (ResultSet rows = statement.getResultSet()) {
                return rows.next() ? String.valueOf(rows.getObject(1)) : "";
            }
        }
    }
}
