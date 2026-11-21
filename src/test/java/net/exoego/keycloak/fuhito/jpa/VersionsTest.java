package net.exoego.keycloak.fuhito.jpa;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VersionsTest {

    @Test
    void countsUpByOneAndFailsAtTheLargestLong() {
        assertEquals(2, Versions.next(1));
        assertEquals(Long.MAX_VALUE, Versions.next(Long.MAX_VALUE - 1));
        assertThrows(ArithmeticException.class, () -> Versions.next(Long.MAX_VALUE));
    }
}
