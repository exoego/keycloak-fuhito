package net.exoego.keycloak.fuhito.domain;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
        assertEquals(RefKind.UNIT_TYPE, RefKind.valueOf("UNIT_TYPE"));
    }
}
