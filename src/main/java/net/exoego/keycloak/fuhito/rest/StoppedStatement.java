package net.exoego.keycloak.fuhito.rest;

import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

import jakarta.ws.rs.core.Response;

/**
 * A statement of a request that PostgreSQL stopped. The same request can succeed when it is sent again later.
 *
 * <p>A statement that lost a deadlock, that waited for a lock past {@code lock_timeout}, or that could not be
 * serialized fails with 409 and {@code write.aborted}. A statement that ran past {@code statement_timeout}, also
 * while it waited for a lock, or that an administrator stopped, fails with 503 and {@code request.timed_out}.
 *
 * @param status the status of the response
 * @param code the error code
 * @param sqlState the SQL state of PostgreSQL
 * @param message the first line of the message of PostgreSQL, without its severity
 */
public record StoppedStatement(Response.Status status, String code, String sqlState, String message) {

    /**
     * Finds the statement that PostgreSQL stopped, in the causes of an error.
     *
     * @param error the error of the request
     * @return the stopped statement, or empty when PostgreSQL stopped none
     */
    public static Optional<StoppedStatement> of(Throwable error) {
        // Hibernate turns each state into another exception, so only the state tells them apart.
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null) {
                String state = sql.getSQLState();
                switch (state) {
                    case "40P01", "55P03", "40001" -> {
                        return Optional.of(new StoppedStatement(Response.Status.CONFLICT, "write.aborted", state,
                                message(sql)));
                    }
                    case "57014" -> {
                        return Optional.of(new StoppedStatement(Response.Status.SERVICE_UNAVAILABLE,
                                "request.timed_out", state, message(sql)));
                    }
                    default -> {
                    }
                }
            }
        }
        return Optional.empty();
    }

    // PgJDBC starts the text with the severity, as in "ERROR: deadlock detected", and puts the details on more lines.
    // A batch has the error of PostgreSQL in the next exception.
    private static String message(SQLException sql) {
        String text = String.valueOf(Objects.requireNonNullElse(sql.getNextException(), sql).getMessage());
        String line = text.lines().findFirst().orElse("");
        int severity = line.indexOf(": ");
        return (severity < 0 ? line : line.substring(severity + 2)).strip();
    }
}
