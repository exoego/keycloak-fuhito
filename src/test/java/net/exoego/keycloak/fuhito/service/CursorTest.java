package net.exoego.keycloak.fuhito.service;

import java.util.List;

import net.exoego.keycloak.fuhito.domain.FuhitoException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CursorTest {

    @Test
    void aCursorGivesBackItsValues() {
        String cursor = Cursor.encode("u-1", "3", "2026-04-01", "a-9");

        assertEquals(List.of("u-1", "3", "2026-04-01", "a-9"), Cursor.decode(cursor, 4));
        assertEquals(List.of("only"), Cursor.decode(Cursor.encode("only"), 1));
        assertEquals(List.of("", "x"), Cursor.decode(Cursor.encode("", "x"), 2));
        // It is safe in a URL as it is.
        assertFalse(cursor.matches(".*[+/=].*"));
    }

    @Test
    void rejectsACursorThatTheListDidNotGiveOut() {
        for (String bad : List.of("not base64 !", Cursor.encode("one"), Cursor.encode("a", "b", "c"), "")) {
            FuhitoException e = assertThrows(FuhitoException.class, () -> Cursor.decode(bad, 2), bad);
            assertEquals(FuhitoException.Reason.INVALID, e.reason());
            assertEquals("invalid.cursor", e.code());
        }
    }

    @Test
    void aCursorDoesNotGoWithANumberToSkip() {
        Cursor.requireAlone(null, null);
        Cursor.requireAlone("x", null);
        Cursor.requireAlone(null, 5);
        assertEquals("invalid.cursor_with_first",
                assertThrows(FuhitoException.class, () -> Cursor.requireAlone("x", 0)).code());
    }
}
