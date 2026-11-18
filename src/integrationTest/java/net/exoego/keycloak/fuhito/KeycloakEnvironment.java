package net.exoego.keycloak.fuhito;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertThrows;

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
                    .withEnv("KC_SPI_ADMIN_REALM_RESTAPI_EXTENSION__FUHITO__PROJECTION_TIMER_ENABLED", "false");

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
