package net.exoego.keycloak.fuhito.service;

import java.util.List;
import java.util.Map;

import net.exoego.keycloak.fuhito.domain.FuhitoException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RestoreRulesTest {

    private static RealmExport.Unit unit(String id, RealmExport.Parent... parents) {
        return new RealmExport.Unit(id, 1L, null, null, null, List.of(new RealmExport.Version("v-" + id,
                "2020-04-01", null, null, "ja", Map.of(), null, null, null, 0, Map.of())), List.of(parents),
                List.of(), null);
    }

    private static RealmExport.Parent parent(String id, String parentId, String from, String to) {
        return new RealmExport.Parent(id, "default", parentId, from, to);
    }

    private static RealmExport units(RealmExport.Unit... units) {
        return new RealmExport(RealmExport.FORMAT, null, null, null, null, List.of(units), null, null, null, null,
                null, null, null, null, null);
    }

    @Test
    void aCycleAboveALinkFailsAtALinkOfTheCycle() {
        FuhitoException e = assertThrows(FuhitoException.class, () -> RestoreRules.require(units(
                unit("a", parent("l-a", "b", "2020-04-01", null)),
                unit("b", parent("l-b", "c", "2020-04-01", null)),
                unit("c", parent("l-c", "b", "2020-04-01", null)))));

        assertEquals("restore.bad_value", e.code());
        assertEquals(Map.of("list", "org-unit-parents", "id", "l-b", "field", "parentId", "rule", "org_unit.cycle"),
                e.params());
    }

    @Test
    void twoLinksWithNoSharedDateMakeNoCycle() {
        assertDoesNotThrow(() -> RestoreRules.require(units(
                unit("a", parent("l-a", "b", "2020-04-01", "2021-04-01")),
                unit("b", parent("l-b", "a", "2021-04-01", null)))));
    }
}
