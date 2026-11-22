package net.exoego.keycloak.fuhito.domain;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RefItemNameTest {

    @Test
    void checksTheNamesOfARequest() {
        assertEquals(new Localized<>("ja", Map.of("ja", new RefItemName("部長"), "en-us", new RefItemName("Manager"))),
                RefItemName.check("ja", Map.of("ja", new RefItemName("部長"), "en-us", new RefItemName("Manager"))));
    }

    @Test
    void rejectsBadNames() {
        assertEquals("invalid.required", code(null, Map.of("ja", new RefItemName("部長"))));
        assertEquals("invalid.primary_language", code("ja", Map.of("en", new RefItemName("Manager"))));
        FuhitoException blank = assertThrows(FuhitoException.class,
                () -> RefItemName.check("ja", Map.of("ja", new RefItemName(" "))));
        assertEquals("invalid.name", blank.code());
        assertEquals(Map.of("field", "names.ja.name"), blank.params());
        FuhitoException padded = assertThrows(FuhitoException.class,
                () -> RefItemName.check("ja", Map.of("ja", new RefItemName("部長\t"))));
        assertEquals("invalid.surrounding_space", padded.code());
        assertEquals(Map.of("field", "names.ja.name"), padded.params());
    }

    private static String code(String primaryLanguage, Map<String, RefItemName> names) {
        return assertThrows(FuhitoException.class, () -> RefItemName.check(primaryLanguage, names)).code();
    }
}
