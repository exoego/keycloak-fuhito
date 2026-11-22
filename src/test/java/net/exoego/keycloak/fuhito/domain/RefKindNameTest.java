package net.exoego.keycloak.fuhito.domain;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RefKindNameTest {

    @Test
    void checksTheNamesOfARequest() {
        assertEquals(new Localized<>("ja", Map.of("ja", new RefKindName("勤務地"), "en-US",
                        new RefKindName("Work location"))),
                RefKindName.check("ja", Map.of("ja", new RefKindName("勤務地"), "en-us",
                        new RefKindName("Work location"))));
    }

    @Test
    void rejectsBadNames() {
        assertEquals("invalid.required", code(null, Map.of("ja", new RefKindName("勤務地"))));
        assertEquals("invalid.primary_language", code("ja", Map.of("en", new RefKindName("Work location"))));
        FuhitoException blank = assertThrows(FuhitoException.class,
                () -> RefKindName.check("ja", Map.of("ja", new RefKindName(" "))));
        assertEquals("invalid.name", blank.code());
        assertEquals(Map.of("field", "names.ja.name"), blank.params());
        FuhitoException padded = assertThrows(FuhitoException.class,
                () -> RefKindName.check("ja", Map.of("ja", new RefKindName(" 勤務地"))));
        assertEquals("invalid.surrounding_space", padded.code());
        assertEquals(Map.of("field", "names.ja.name"), padded.params());
    }

    private static String code(String primaryLanguage, Map<String, RefKindName> names) {
        return assertThrows(FuhitoException.class, () -> RefKindName.check(primaryLanguage, names)).code();
    }
}
