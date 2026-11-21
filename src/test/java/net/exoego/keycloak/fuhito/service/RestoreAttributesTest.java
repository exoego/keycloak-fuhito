package net.exoego.keycloak.fuhito.service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.exoego.keycloak.fuhito.domain.FuhitoException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RestoreAttributesTest {

    private static RealmExport.Definition definition(String target, String name, String type, String refKind,
            boolean multiple, boolean required) {
        return new RealmExport.Definition("d-" + target + "-" + name, 1L, target, name, type, refKind, multiple,
                required);
    }

    private static RealmExport.Item item(String id, String kind, String code, Map<String, Object> attributes) {
        return new RealmExport.Item(id, 1L, kind, code, 0, attributes, true, false, false, false, false, List.of());
    }

    // Each list has one row, whose ID gives its attributes. The org unit has the past version v-0 and the version v-1.
    private static RealmExport document(List<RealmExport.Definition> definitions,
            Map<String, Map<String, Object>> attributes) {
        Map<String, Map<String, Object>> of = new HashMap<>(attributes);
        List<String> ids = List.of("g1", "v-0", "v-1", "p-1", "t-1", "r-1", "q-1");
        ids.forEach(id -> of.putIfAbsent(id, Map.of()));
        RealmExport.Unit unit = new RealmExport.Unit("u-1", 1L, null, null, null, List.of(
                new RealmExport.Version("v-0", "2019-04-01", "2020-04-01", null, "ja", Map.of(), null, null, null, 0,
                        of.get("v-0")),
                new RealmExport.Version("v-1", "2020-04-01", null, null, "ja", Map.of(), null, null, null, 0,
                        of.get("v-1"))), List.of(), List.of(), null);
        RealmExport.Terms terms = new RealmExport.Terms("t-1", "2020-04-01", null, null, null, "ACTIVE", null, null,
                null, of.get("t-1"));
        RealmExport.EmploymentRow employment = new RealmExport.EmploymentRow("e-1", 1L, "p-1", "u-1", null,
                "2020-04-01", null, null, null, null, null, List.of(terms));
        RealmExport.AssignmentRows assignment = new RealmExport.AssignmentRows("a-1", 1L, List.of(
                new RealmExport.Period("r-1", "e-1", "u-1", "2020-04-01", null, null, null, null, null, null, false,
                        true, null, 0, of.get("r-1"), null, null)));
        return new RealmExport(RealmExport.FORMAT, null, List.of(item("g1", "GRADE", "g1", of.get("g1")),
                item("approver", "UNIT_ROLE", "approver", Map.of())), definitions, null, List.of(unit),
                List.of(new RealmExport.PersonRow("p-1", 1L, null, null, null, of.get("p-1"), null, null)),
                List.of(employment), List.of(assignment), null, null,
                List.of(new RealmExport.QualificationRow("q-1", 1L, "p-1", "lic", null, "2020-04-01", null,
                        of.get("q-1"), null)), null, null, null);
    }

    private static void assertBroken(RealmExport document, String list, String id, String field, String rule) {
        FuhitoException e = assertThrows(FuhitoException.class, () -> RestoreAttributes.require(document));
        assertEquals("restore.bad_value", e.code());
        assertEquals(Map.of("list", list, "id", id, "field", field, "rule", rule), e.params());
    }

    @Test
    void attributesThatMeetTheDefinitionsOfTheExportPass() {
        assertDoesNotThrow(() -> RestoreAttributes.require(document(List.of(
                definition("REF_ITEM", "rank", "NUMBER", null, false, false),
                definition("ORG_UNIT", "beds", "NUMBER", null, false, true),
                definition("ORG_UNIT", "open", "BOOLEAN", null, false, false),
                definition("PERSON", "grades", "REF_ITEM", "GRADE", true, true),
                definition("EMPLOYMENT_TERMS", "since", "DATE", null, false, false),
                definition("ASSIGNMENT", "shifts", "STRING", null, true, false),
                definition("QUALIFICATION", "note", "STRING", null, false, false)), Map.of(
                "g1", Map.of("rank", 1),
                "v-0", Map.of("beds", 10),
                "v-1", Map.of("beds", new BigDecimal("1.5"), "open", true),
                "p-1", Map.of("grades", List.of("g1")),
                "t-1", Map.of("since", "2020-04-01"),
                "r-1", Map.of("shifts", List.of("night", "day")),
                "q-1", Map.of("note", "a")))));
    }

    @Test
    void aNameWithNoDefinitionFailsInEveryList() {
        List<RealmExport.Definition> definitions = List.of(definition("PERSON", "note", "STRING", null, false, false));
        Map<String, String> lists = Map.of("g1", "ref-items", "v-0", "org-unit-versions", "v-1", "org-unit-versions",
                "p-1", "people", "t-1", "employment-terms", "r-1", "assignments", "q-1", "qualifications");
        lists.forEach((id, list) -> assertBroken(document(definitions, Map.of(id, Map.of("other", "a"))), list, id,
                "attributes.other", "attribute.unknown"));
        // A name of another target has no definition either.
        assertBroken(document(definitions, Map.of("t-1", Map.of("note", "a"))), "employment-terms", "t-1",
                "attributes.note", "attribute.unknown");
    }

    @Test
    void aValueThatBreaksItsDefinitionFails() {
        List<RealmExport.Definition> definitions = List.of(definition("PERSON", "note", "STRING", null, false, false),
                definition("PERSON", "tags", "STRING", null, true, false),
                definition("PERSON", "grade", "REF_ITEM", "GRADE", false, false),
                definition("PERSON", "beds", "NUMBER", null, false, false));
        for (Map<String, Object> bad : List.<Map<String, Object>>of(Map.of("note", 1), Map.of("note", List.of("a")),
                Map.of("tags", "a"), Map.of("grade", "g9"), Map.of("grade", "approver"),
                Map.of("beds", new BigDecimal("1e400")))) {
            String name = bad.keySet().iterator().next();
            assertBroken(document(definitions, Map.of("p-1", bad)), "people", "p-1", "attributes." + name,
                    "attribute.bad_value");
        }
    }

    @Test
    void aRequiredValueIsInEveryRowAndVersion() {
        List<RealmExport.Definition> definitions = List.of(definition("ORG_UNIT", "beds", "NUMBER", null, false, true),
                definition("EMPLOYMENT_TERMS", "rank", "STRING", null, false, true));

        assertBroken(document(definitions, Map.of("v-1", Map.of("beds", 1), "t-1", Map.of("rank", "a"))),
                "org-unit-versions", "v-0", "attributes.beds", "attribute.required");
        assertBroken(document(definitions, Map.of("v-0", Map.of("beds", 1), "v-1", Map.of("beds", 1))),
                "employment-terms", "t-1", "attributes.rank", "attribute.required");
    }

    @Test
    void aPastVersionThatBreaksARuleFails() {
        assertBroken(document(List.of(definition("ORG_UNIT", "beds", "NUMBER", null, false, false)),
                Map.of("v-0", Map.of("beds", "ten"), "v-1", Map.of("beds", 10))), "org-unit-versions", "v-0",
                "attributes.beds", "attribute.bad_value");
    }

    @Test
    void attributesOfMoreThan64KiBNameNoAttribute() {
        assertBroken(document(List.of(definition("PERSON", "note", "STRING", null, false, false)),
                Map.of("p-1", Map.of("note", "x".repeat(70_000)))), "people", "p-1", "attributes",
                "attribute.too_large");
    }
}
