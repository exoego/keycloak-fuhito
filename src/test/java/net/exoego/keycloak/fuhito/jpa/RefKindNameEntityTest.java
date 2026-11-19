package net.exoego.keycloak.fuhito.jpa;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class RefKindNameEntityTest {

    private static RefKindNameEntity.Key key(String kindId, String language) {
        RefKindNameEntity.Key key = new RefKindNameEntity.Key();
        key.kindId = kindId;
        key.language = language;
        return key;
    }

    @Test
    void aKeyIsTheKindAndTheLanguage() {
        assertEquals(key("k1", "ja"), key("k1", "ja"));
        assertEquals(key("k1", "ja").hashCode(), key("k1", "ja").hashCode());
        assertNotEquals(key("k1", "ja"), key("k1", "en"));
        assertNotEquals(key("k1", "ja"), key("k2", "ja"));
        assertNotEquals(key("k1", "ja"), (Object) "k1");
    }
}
