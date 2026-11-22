package net.exoego.keycloak.fuhito.service;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.RefKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RestoreKindsTest {

    // Each fixed kind has an item whose ID is the name of the kind.
    private static List<RealmExport.Item> items(String... realmKinds) {
        return Stream.concat(Arrays.stream(RefKind.values()).map(RefKind::name), Arrays.stream(realmKinds))
                .map(kind -> new RealmExport.Item(kind, 1L, kind, kind.toLowerCase(Locale.ROOT), 0, Map.of(), true,
                        false, false, false, false, List.of()))
                .toList();
    }

    private static RealmExport document(List<RealmExport.Item> items, List<RealmExport.Definition> definitions,
            String gradeId) {
        RealmExport.Unit unit = new RealmExport.Unit("u-1", 1L, null, null, null, List.of(new RealmExport.Version(
                "v-1", "2020-04-01", null, null, "ja", Map.of(), "UNIT_TYPE", "WORK_LOCATION", "COST_CENTER", 0,
                Map.of())), List.of(), List.of(), null);
        RealmExport.Terms terms = new RealmExport.Terms("t-1", "2020-04-01", null, "EMPLOYMENT_TYPE", gradeId,
                "ON_LEAVE", "LEAVE_REASON", null, null, Map.of());
        RealmExport.EmploymentRow inside = new RealmExport.EmploymentRow("e-1", 1L, "p-1", "u-1", null, "2020-04-01",
                "2021-04-01", null, "END_REASON", null, null, List.of(terms));
        RealmExport.EmploymentRow outside = new RealmExport.EmploymentRow("e-2", 1L, "p-1", null,
                "EXTERNAL_EMPLOYER", "2020-04-01", null, null, null, null, null, List.of());
        RealmExport.AssignmentRows assignment = new RealmExport.AssignmentRows("a-1", 1L, List.of(
                new RealmExport.Period("r-1", "e-1", "u-1", "2020-04-01", null, "JOB_TITLE", "OCCUPATION",
                        "ASSIGNMENT_KIND", "WORK_LOCATION", "COST_CENTER", false, true, null, 0, Map.of(),
                        null)));
        return new RealmExport(RealmExport.FORMAT, List.of(new RealmExport.Kind("k-1", 1L, "site", "ja", Map.of())),
                items, definitions, null, List.of(unit), null, List.of(inside, outside), List.of(assignment), null,
                List.of(new RealmExport.Role("ur-1", 1L, "e-1", "u-1", "UNIT_ROLE", false, "2020-04-01", null)),
                List.of(new RealmExport.QualificationRow("q-1", 1L, "p-1", "QUALIFICATION", null, "2020-04-01", null,
                        Map.of(), null)), null, null, null);
    }

    private static RealmExport.Definition definition(String type, String refKind) {
        return new RealmExport.Definition("d-1", 1L, "PERSON", "place", type, refKind, false, false);
    }

    private static void assertBroken(RealmExport document, String list, String id, String field, String rule) {
        FuhitoException e = assertThrows(FuhitoException.class, () -> RestoreKinds.require(document));
        assertEquals("restore.bad_value", e.code());
        assertEquals(Map.of("list", list, "id", id, "field", field, "rule", rule), e.params());
    }

    @Test
    void eachFieldNamesAnItemOfItsKind() {
        assertDoesNotThrow(() -> RestoreKinds.require(document(items("site"), List.of(definition("REF_ITEM", "site"),
                new RealmExport.Definition("d-2", 1L, "PERSON", "note", "STRING", null, false, false)), "GRADE")));
        assertBroken(document(items(), List.of(), "UNIT_ROLE"), "employment-terms", "t-1", "gradeId",
                "ref_item.unknown");
    }

    @Test
    void aKindOfTheExportIsNotAFixedKindInAnyCase() {
        for (String code : List.of("grade", "GRADE", "Grade")) {
            assertBroken(new RealmExport(RealmExport.FORMAT, List.of(new RealmExport.Kind("k-1", 1L, code, "ja",
                    Map.of())), null, null, null, null, null, null, null, null, null, null, null, null, null),
                    "ref-kinds", "k-1", "code", "ref_kind.code_taken");
        }
    }

    @Test
    void anItemOrADefinitionHasAKindOfTheExport() {
        assertBroken(document(items("SITE"), List.of(), null), "ref-items", "SITE", "kind", "invalid.kind");
        assertBroken(document(items(), List.of(definition("REF_ITEM", "building")), null), "attribute-definitions",
                "d-1", "refKind", "invalid.choice");
        assertBroken(document(items(), List.of(definition("REF_ITEM", null)), null), "attribute-definitions", "d-1",
                "refKind", "attribute.ref_kind");
        assertBroken(document(items(), List.of(definition("DATE", "GRADE")), null), "attribute-definitions", "d-1",
                "refKind", "attribute.ref_kind");
    }
}
