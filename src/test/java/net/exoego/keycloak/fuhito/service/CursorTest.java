package net.exoego.keycloak.fuhito.service;

import java.util.List;

import net.exoego.keycloak.fuhito.domain.FuhitoException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CursorTest {

    @Test
    void aCursorIsTheNameOfItsFormatAndTheValues() {
        assertEquals("members.v1__u-1__3__2026-04-01__a-9", Cursor.encode(Cursor.MEMBERS, "u-1", "3", "2026-04-01", "a-9"));
        assertEquals("people.v1__p-1", Cursor.encode(Cursor.PEOPLE, "p-1"));

        assertEquals(List.of("u-1", "3", "2026-04-01", "a-9"),
                Cursor.decode(Cursor.MEMBERS, "members.v1__u-1__3__2026-04-01__a-9"));
        assertEquals(List.of("p-1"), Cursor.decode(Cursor.PEOPLE, "people.v1__p-1"));
    }

    @Test
    void rejectsACursorOfAnotherListAnOldVersionAndABrokenOne() {
        for (String bad : List.of("members.v1__u-1__3__2026-04-01__a-9", "people.v0__p-1", "people.v1", "p-1", "",
                "people.v1__p-1__more")) {
            FuhitoException e = assertThrows(FuhitoException.class, () -> Cursor.decode(Cursor.PEOPLE, bad), bad);
            assertEquals(FuhitoException.Reason.INVALID, e.reason());
            assertEquals("invalid.cursor", e.code());
            assertEquals("people.v1", e.params().get("format"));
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
