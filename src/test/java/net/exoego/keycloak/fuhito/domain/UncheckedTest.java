package net.exoego.keycloak.fuhito.domain;

import java.io.IOException;
import java.io.UncheckedIOException;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UncheckedTest {

    @Test
    void returnsTheResultOfTheAction() {
        assertEquals("ok", Unchecked.io(() -> "ok"));
    }

    @Test
    void turnsAFailureIntoAnUncheckedOneAndKeepsTheCause() {
        var cause = new IOException("broken");

        UncheckedIOException e = assertThrows(UncheckedIOException.class, () -> Unchecked.io(() -> {
            throw cause;
        }));

        assertSame(cause, e.getCause());
    }
}
