package net.exoego.keycloak.fuhito.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class FuhitoExceptionTest {

    @Test
    void carriesTheReasonTheCodeAndTheValues() {
        FuhitoException notFound = FuhitoException.notFound("x.not_found", Map.of("id", "1"));
        assertEquals(FuhitoException.Reason.NOT_FOUND, notFound.reason());
        assertEquals("x.not_found", notFound.code());
        assertEquals(Map.of("id", "1"), notFound.params());
        assertEquals("x.not_found {id=1}", notFound.getMessage());

        assertEquals(FuhitoException.Reason.CONFLICT, FuhitoException.conflict("x", Map.of()).reason());
        assertEquals(FuhitoException.Reason.INVALID, FuhitoException.invalid("x", Map.of()).reason());
    }

    @Test
    void namesEveryReasonAndKind() {
        assertEquals(3, FuhitoException.Reason.values().length);
        assertEquals(List.of("UNIT_TYPE", "JOB_TITLE", "OCCUPATION", "EMPLOYMENT_TYPE", "GRADE", "END_REASON",
                "ASSIGNMENT_KIND", "QUALIFICATION"), Arrays.stream(RefKind.values()).map(RefKind::name).toList());
    }

    @Test
    void keepsWhatLedToIt() {
        var cause = new IllegalArgumentException("bad");

        FuhitoException e = FuhitoException.invalid("invalid.choice", Map.of()).causedBy(cause);

        assertSame(cause, e.getCause());
        assertEquals("invalid.choice", e.code());
    }
}
