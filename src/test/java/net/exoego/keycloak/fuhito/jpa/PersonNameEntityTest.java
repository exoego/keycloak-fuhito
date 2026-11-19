package net.exoego.keycloak.fuhito.jpa;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class PersonNameEntityTest {

    private static PersonNameEntity.Key key(String personId, String language) {
        PersonNameEntity.Key key = new PersonNameEntity.Key();
        key.personId = personId;
        key.language = language;
        return key;
    }

    @Test
    void aKeyIsThePersonAndTheLanguage() {
        assertEquals(key("p1", "ja"), key("p1", "ja"));
        assertEquals(key("p1", "ja").hashCode(), key("p1", "ja").hashCode());
        assertNotEquals(key("p1", "ja"), key("p1", "en"));
        assertNotEquals(key("p1", "ja"), key("p2", "ja"));
        assertNotEquals(key("p1", "ja"), (Object) "p1");
    }
}
