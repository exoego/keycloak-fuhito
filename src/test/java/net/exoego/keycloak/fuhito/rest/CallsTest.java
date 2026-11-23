package net.exoego.keycloak.fuhito.rest;

import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PersistenceException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CallsTest {

    // What the request context ends the request with.
    private static final class Failed extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final transient Response response;
        private final transient StoppedStatement stopped;

        Failed(Response response, StoppedStatement stopped) {
            this.response = response;
            this.stopped = stopped;
        }

        ErrorRepresentation body() {
            return (ErrorRepresentation) response.getEntity();
        }
    }

    private static final RequestContext CONTEXT = (RequestContext) Proxy.newProxyInstance(
            CallsTest.class.getClassLoader(), new Class<?>[] {RequestContext.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "pathParts" -> List.of();
                case "locale" -> Locale.ENGLISH;
                case "flush" -> null;
                case "failure" -> args[0] instanceof StoppedStatement stopped ? new Failed(null, stopped)
                        : new Failed((Response) args[0], null);
                default -> throw new UnsupportedOperationException(method.getName());
            });

    // Hibernate wraps the error of PgJDBC, and JPA wraps that again.
    private static RuntimeException fromDatabase(String message, String state) {
        return new PersistenceException(new RuntimeException("could not execute statement",
                new SQLException(message, state)));
    }

    private static Failed failed(RuntimeException error) {
        return assertThrows(Failed.class, () -> Calls.run(CONTEXT, "rule.broken", () -> {
            throw error;
        }));
    }

    @ParameterizedTest
    @CsvSource({
        "40P01, CONFLICT, write.aborted",
        "55P03, CONFLICT, write.aborted",
        "40001, CONFLICT, write.aborted",
        "57014, SERVICE_UNAVAILABLE, request.timed_out",
    })
    void aStatementThatPostgreSqlStoppedFailsWithItsCode(String state, Response.Status status, String code) {
        Failed failed = failed(fromDatabase("ERROR: stopped\n  Detail: why", state));

        assertNull(failed.response);
        assertEquals(new StoppedStatement(status, code, state, "stopped"), failed.stopped);
    }

    @Test
    void aBrokenRuleStillFailsWithTheCodeOfTheRule() {
        Failed failed = failed(fromDatabase(
                "ERROR: duplicate key value violates unique constraint \"uk_fuhito_org_unit_code\"", "23505"));

        assertEquals(409, failed.response.getStatus());
        assertEquals("rule.broken", failed.body().error());
        assertEquals(Map.of("constraint", "UK_FUHITO_ORG_UNIT_CODE"), failed.body().params());
    }

    // The check of the version comes first, also when a stopped statement led to the error.
    @Test
    void anotherWriteThatCameFirstStillFailsWithAVersionConflict() {
        Failed failed = failed(new OptimisticLockException(fromDatabase("ERROR: deadlock detected", "40P01")));

        assertEquals(409, failed.response.getStatus());
        assertEquals("version.conflict", failed.body().error());
        assertEquals(Map.of(), failed.body().params());
    }

    @ParameterizedTest
    @ValueSource(strings = {"22003", "25P02", "53300", "57P01", "XX000"})
    void anyOtherStateEscapesAsBefore(String state) {
        RuntimeException error = fromDatabase("ERROR: other", state);

        assertSame(error, assertThrows(RuntimeException.class, () -> Calls.run(CONTEXT, "rule.broken", () -> {
            throw error;
        })));
    }
}
