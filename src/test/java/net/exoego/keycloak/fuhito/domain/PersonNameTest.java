package net.exoego.keycloak.fuhito.domain;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PersonNameTest {

    @Test
    void aRequestCanHaveNoNames() {
        assertNull(PersonName.check(null, null));
        assertNull(PersonName.check(null, Map.of()));
    }

    @Test
    void checksTheNamesOfARequest() {
        assertEquals(new Localized<>("ja", Map.of("ja", new PersonName("山田 太郎", "やまだ たろう"),
                "en-US", new PersonName("Taro Yamada", null))), PersonName.check("ja", Map.of(
                "ja", new PersonName("山田 太郎", "やまだ たろう"), "en-us", new PersonName("Taro Yamada", null))));
    }

    @Test
    void rejectsBadNames() {
        assertEquals("invalid.required", code(null, Map.of("ja", new PersonName("X", null))));
        assertEquals("invalid.primary_language", code("ja", Map.of()));
        assertEquals("invalid.name", code("ja", Map.of("ja", new PersonName(" ", null))));
        FuhitoException phonetic = assertThrows(FuhitoException.class,
                () -> PersonName.check("ja", Map.of("ja", new PersonName("X", "x".repeat(256)))));
        assertEquals(Map.of("field", "names.ja.phoneticName"), phonetic.params());
    }

    @Test
    void rejectsANameWithSpacesAroundIt() {
        FuhitoException name = assertThrows(FuhitoException.class,
                () -> PersonName.check("ja", Map.of("ja", new PersonName("山田太郎　", null))));
        assertEquals("invalid.surrounding_space", name.code());
        assertEquals(Map.of("field", "names.ja.name"), name.params());
        FuhitoException phonetic = assertThrows(FuhitoException.class,
                () -> PersonName.check("ja", Map.of("ja", new PersonName("山田太郎", " やまだたろう"))));
        assertEquals("invalid.surrounding_space", phonetic.code());
        assertEquals(Map.of("field", "names.ja.phoneticName"), phonetic.params());
    }

    private static String code(String primaryLanguage, Map<String, PersonName> names) {
        return assertThrows(FuhitoException.class, () -> PersonName.check(primaryLanguage, names)).code();
    }
}
