package net.exoego.keycloak.fuhito.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import net.exoego.keycloak.fuhito.domain.FuhitoException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RestoreCheckTest {

    private static final Map<String, Integer> LENGTHS = Map.of("id", 36, "code", 64, "name", 255, "source", 64);

    private static RealmExport of(List<RealmExport.HierarchyRow> hierarchies, List<RealmExport.Unit> units,
            List<RealmExport.EmploymentRow> employments, List<RealmExport.ExternalIdRow> externalIds,
            List<RealmExport.Definition> definitions) {
        return new RealmExport(RealmExport.FORMAT, null, null, definitions, hierarchies, units, null, employments,
                null, null, null, null, externalIds, null, null);
    }

    private static RealmExport hierarchy(String code, String name) {
        return of(List.of(new RealmExport.HierarchyRow("h-1", 1L, code, name)), null, null, null, null);
    }

    private static RealmExport unit(String source, String timeZone, String dayStart) {
        return of(null, List.of(new RealmExport.Unit("u-1", 1L, source, timeZone, dayStart, List.of(), List.of(),
                List.of(), null)), null, null, null);
    }

    private static RealmExport workPercent(String percent) {
        RealmExport.Terms terms = new RealmExport.Terms("t-1", "2020-04-01", null, null, null, "ACTIVE", null,
                new BigDecimal(percent), null, Map.of());
        return of(null, null, List.of(new RealmExport.EmploymentRow("e-1", 1L, "p-1", "u-1", null, "2020-04-01",
                null, null, null, null, null, List.of(terms))), null, null);
    }

    private static RealmExport externalId(String value) {
        return of(null, null, null, List.of(new RealmExport.ExternalIdRow("x-1", 1L, "PERSON", "p-1", "hr", value)),
                null);
    }

    private static RealmExport attribute(String name) {
        return of(null, null, null, null, List.of(new RealmExport.Definition("d-1", 1L, "PERSON", name, "STRING",
                null, false, false)));
    }

    private static void check(RealmExport document) {
        RestoreCheck.requireValues(document, list -> LENGTHS);
    }

    private static void assertBadValue(RealmExport document, String list, String id, String field) {
        FuhitoException e = assertThrows(FuhitoException.class, () -> check(document));
        assertEquals("restore.bad_value", e.code());
        assertEquals(Map.of("list", list, "id", id, "field", field), e.params());
    }

    @Test
    void aTextFitsItsColumnByCharacters() {
        assertDoesNotThrow(() -> check(hierarchy("c".repeat(64), "𠮷".repeat(255))));
        assertBadValue(hierarchy("c".repeat(65), "Project"), "hierarchies", "h-1", "code");
        assertBadValue(hierarchy("project", "𠮷".repeat(256)), "hierarchies", "h-1", "name");
        assertBadValue(unit("s".repeat(65), null, null), "org-units", "u-1", "source");
    }

    @Test
    void aValueBreaksTheRuleOfItsWrite() {
        assertBadValue(hierarchy("a b", "Project"), "hierarchies", "h-1", "code");
        assertBadValue(unit("h r", null, null), "org-units", "u-1", "source");
        assertBadValue(unit(null, "Mars/Base", null), "org-units", "u-1", "timeZone");
        assertBadValue(unit(null, null, "9am"), "org-units", "u-1", "dayStart");
        assertBadValue(workPercent("1000"), "employment-terms", "t-1", "workPercent");
        assertBadValue(externalId("x".repeat(256)), "external-ids", "x-1", "externalId");
        assertBadValue(attribute(" beds"), "attribute-definitions", "d-1", "name");
        assertDoesNotThrow(() -> check(unit("hr", "Asia/Tokyo", "04:00")));
        assertDoesNotThrow(() -> check(workPercent("100.00")));
        assertDoesNotThrow(() -> check(externalId("x".repeat(255))));
    }
}
