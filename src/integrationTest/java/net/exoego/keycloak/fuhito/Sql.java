package net.exoego.keycloak.fuhito;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Runs SQL in a database of the tests. It starts no container, so a test with a database of its own does not
 * wait for the shared one.
 */
final class Sql {

    private Sql() {
    }

    // JDBC, not psql in the container. A docker exec sometimes never reports that it ended.
    static String run(PostgreSQLContainer postgres, String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword())) {
            return value(connection, sql);
        }
    }

    /** Runs a statement on a connection and returns the first column of its first row, or an empty text. */
    static String value(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            if (!statement.execute(sql)) {
                return "";
            }
            try (ResultSet rows = statement.getResultSet()) {
                return rows.next() ? String.valueOf(rows.getObject(1)) : "";
            }
        }
    }
}
