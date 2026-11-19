package net.exoego.keycloak.fuhito.jpa;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class OrgUnitVersionNameEntityTest {

    private static OrgUnitVersionNameEntity.Key key(String versionId, String language) {
        OrgUnitVersionNameEntity.Key key = new OrgUnitVersionNameEntity.Key();
        key.versionId = versionId;
        key.language = language;
        return key;
    }

    @Test
    void aKeyIsTheVersionAndTheLanguage() {
        assertEquals(key("v1", "ja"), key("v1", "ja"));
        assertEquals(key("v1", "ja").hashCode(), key("v1", "ja").hashCode());
        assertNotEquals(key("v1", "ja"), key("v1", "en"));
        assertNotEquals(key("v1", "ja"), key("v2", "ja"));
        assertNotEquals(key("v1", "ja"), (Object) "v1");
    }
}
