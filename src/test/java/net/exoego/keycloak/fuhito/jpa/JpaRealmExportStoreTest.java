package net.exoego.keycloak.fuhito.jpa;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class JpaRealmExportStoreTest {

    private final JpaRealmExportStore store = new JpaRealmExportStore(null, "realm");

    @Test
    void theLengthOfATextComesFromItsColumn() {
        assertEquals(Map.of("id", 36, "realmId", 36, "code", 64, "name", 255), store.lengths("hierarchies"));
        assertEquals(36, store.lengths("erasures").get("personId"));
    }

    @Test
    void aFieldOfAnExportHasTheLengthOfItsColumnAlsoUnderAnotherName() {
        assertEquals(36, store.lengths("assignments").get("rowId"));
        assertEquals(36, store.lengths("assignments").get("assignmentId"));
        assertEquals(36, store.lengths("ref-item-names").get("id"));
        assertEquals(32, store.lengths("external-ids").get("resourceType"));
    }

    @Test
    void aClobTakesAnyLength() {
        assertFalse(store.lengths("change-orders").containsKey("note"));
        assertEquals(255, store.lengths("change-orders").get("title"));
    }
}
