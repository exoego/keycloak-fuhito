package net.exoego.keycloak.fuhito.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.exoego.keycloak.fuhito.domain.AttributeDefinition;
import net.exoego.keycloak.fuhito.domain.AttributeDefinition.Target;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.domain.RefKind;
import net.exoego.keycloak.fuhito.service.ImportReport.Write;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttributeDefinitionServiceTest {

    private final MemoryAttributeDefinitionStore store = new MemoryAttributeDefinitionStore();
    private final MemoryRefItemStore refItems = new MemoryRefItemStore();
    private final MemoryAttributeValueStore values = new MemoryAttributeValueStore();
    private final AttributeDefinitionService service = new AttributeDefinitionService(store, values, refItems);

    private static String code(Runnable call) {
        return assertThrows(FuhitoException.class, call::run).code();
    }

    @Test
    void definesChecksAndChangesAttributes() {
        refItems.insert(new RefItem("site-1", RefKind.OCCUPATION, "nurse", ItemNames.of("Nurse"), 0, Map.of(), true));
        AttributeDefinition shift = service.create("ASSIGNMENT", "shift", "STRING", null, null, true, null).definition();
        service.create("ASSIGNMENT", "beds", "NUMBER", null, null, null, null);
        service.create("ASSIGNMENT", "night", "BOOLEAN", null, null, null, null);
        service.create("ASSIGNMENT", "since", "DATE", null, null, null, null);
        service.create("ASSIGNMENT", "also", "REF_ITEM", "OCCUPATION", null, null, null);
        service.create("PERSON", "shift", "NUMBER", null, null, null, null);

        assertTrue(shift.required());
        assertEquals(List.of("also", "beds", "night", "shift", "since"), service.list("ASSIGNMENT").stream()
                .map(AttributeDefinition::name).toList());
        Map<String, Object> good = Map.of("shift", "early", "beds", 3, "night", true, "since", "2026-04-01",
                "also", "nurse");
        assertEquals(good, service.check(Target.ASSIGNMENT, good));
        assertEquals("attribute.required", code(() -> service.check(Target.ASSIGNMENT, Map.of("beds", 1))));
        Map<String, Object> missing = new HashMap<>(Map.of("beds", 1));
        missing.put("shift", null);
        assertEquals("attribute.required", code(() -> service.check(Target.ASSIGNMENT, missing)));
        for (Map<String, Object> bad : List.<Map<String, Object>>of(Map.of("shift", 1), Map.of("shift", "a", "beds", "3"),
                Map.of("shift", "a", "night", "yes"), Map.of("shift", "a", "since", "April"),
                Map.of("shift", "a", "since", "0000-12-31"), Map.of("shift", "a", "since", "+10000-01-01"),
                Map.of("shift", "a", "also", "clerk"), Map.of("shift", "a", "beds", new BigDecimal("1e400")),
                Map.of("shift", "a", "beds", new BigDecimal("50.000000000000001")),
                Map.of("shift", "a", "beds", 9007199254740993L), Map.of("shift", "a", "beds", Double.NaN))) {
            assertEquals("attribute.bad_value", code(() -> service.check(Target.ASSIGNMENT, bad)), bad.toString());
        }
        assertEquals(Map.of("shift", new BigDecimal("1.5")), service.check(Target.PERSON,
                Map.of("shift", new BigDecimal("1.5"))));
        assertEquals("attribute.unknown", code(() -> service.check(Target.ASSIGNMENT,
                Map.of("shift", "early", "free", 1))));
        assertEquals("attribute.unknown", code(() -> service.check(Target.ORG_UNIT, Map.of("shift", "early"))));
        assertEquals(Map.of(), service.check(Target.ORG_UNIT, null));

        assertEquals("invalid.value_fixed", code(() -> service.replace(shift.id(), "NUMBER", null, null, false, null)));
        service.replace(shift.id(), "STRING", null, false, false, null);
        assertEquals(Map.of("beds", 1), service.check(Target.ASSIGNMENT, Map.of("beds", 1)));
        service.delete(shift.id(), false);
        assertEquals("attribute.not_found", code(() -> service.get(shift.id())));
    }

    @Test
    void checksEachElementOfAList() {
        refItems.insert(new RefItem("site-1", RefKind.OCCUPATION, "nurse", ItemNames.of("Nurse"), 0, Map.of(), true));
        refItems.insert(new RefItem("site-2", RefKind.OCCUPATION, "clerk", ItemNames.of("Clerk"), 1, Map.of(), true));
        AttributeDefinition sites = service.create("ASSIGNMENT", "sites", "REF_ITEM", "OCCUPATION", true, true, null)
                .definition();
        service.create("ASSIGNMENT", "days", "NUMBER", null, true, null, null);
        service.create("ASSIGNMENT", "note", "STRING", null, false, null, null);

        assertTrue(sites.multiple());
        Map<String, Object> good = Map.of("sites", List.of("nurse", "clerk"), "days", List.of(1, 3, 5));
        assertEquals(good, service.check(Target.ASSIGNMENT, good));
        FuhitoException noSites = assertThrows(FuhitoException.class,
                () -> service.check(Target.ASSIGNMENT, Map.of("sites", List.of())));
        assertEquals("invalid.blank", noSites.code());
        assertEquals(Map.of("field", "attributes.sites"), noSites.params());
        assertEquals(Map.of("field", "attributes.days"), assertThrows(FuhitoException.class,
                () -> service.check(Target.ASSIGNMENT, Map.of("sites", List.of("nurse"), "days", List.of()))).params());
        assertEquals("attribute.required", code(() -> service.check(Target.ASSIGNMENT, Map.of("days", List.of(1)))));
        List<Object> withNull = new ArrayList<>(List.of("nurse"));
        withNull.add(null);
        for (Map<String, Object> bad : List.<Map<String, Object>>of(Map.of("sites", "nurse"),
                Map.of("sites", List.of("nurse", "driver")), Map.of("sites", withNull),
                Map.of("sites", List.of("nurse"), "days", List.of("1")),
                Map.of("sites", List.of("nurse"), "days", List.of(1, new BigDecimal("1e-400"))),
                Map.of("sites", List.of("nurse"), "note", List.of("a")))) {
            assertEquals("attribute.bad_value", code(() -> service.check(Target.ASSIGNMENT, bad)), bad.toString());
        }
        assertEquals(Map.of("name", "sites", "type", "REF_ITEM[] OCCUPATION"), assertThrows(FuhitoException.class,
                () -> service.check(Target.ASSIGNMENT, Map.of("sites", "nurse"))).params());
        assertEquals(Map.of("name", "days", "type", "NUMBER[]"), assertThrows(FuhitoException.class,
                () -> service.check(Target.ASSIGNMENT, Map.of("sites", List.of("nurse"), "days", 1))).params());

        assertEquals(Map.of("field", "multiple"), assertThrows(FuhitoException.class,
                () -> service.replace(sites.id(), "REF_ITEM", "OCCUPATION", false, true, null)).params());
        assertTrue(service.get(sites.id()).multiple());
    }

    @Test
    void anAttributeRejectsAnEmptyText() {
        service.create("ASSIGNMENT", "shifts", "STRING", null, true, null, null);
        service.create("ASSIGNMENT", "note", "STRING", null, false, null, null);
        service.create("ASSIGNMENT", "since", "DATE", null, false, null, null);

        Map<String, Object> good = Map.of("shifts", List.of("night", " day"), "note", " a ");
        assertEquals(good, service.check(Target.ASSIGNMENT, good));
        for (List<String> bad : List.of(List.of("night", ""), List.of(" "), List.of("\t", "day"))) {
            FuhitoException e = assertThrows(FuhitoException.class,
                    () -> service.check(Target.ASSIGNMENT, Map.of("shifts", bad)));
            assertEquals("invalid.blank", e.code(), bad.toString());
            assertEquals(Map.of("field", "attributes.shifts"), e.params());
        }
        for (String bad : List.of("", " ", "\u3000")) {
            assertEquals(Map.of("field", "attributes.note"), assertThrows(FuhitoException.class,
                    () -> service.check(Target.ASSIGNMENT, Map.of("note", bad))).params());
        }
        assertEquals("invalid.blank", code(() -> service.check(Target.ASSIGNMENT, Map.of("since", ""))));
    }

    @Test
    void rejectsABadDefinition() {
        service.create("ORG_UNIT", "beds", "NUMBER", null, null, null, null);

        assertEquals("attribute.name_taken", code(() -> service.create("ORG_UNIT", "beds", "STRING", null, null, null, null)));
        assertEquals("attribute.bad_name", code(() -> service.create("ORG_UNIT", " beds", "STRING", null, null, null, null)));
        assertEquals("attribute.bad_name", code(() -> service.create("ORG_UNIT", "x".repeat(65), "STRING", null, null,
                null, null)));
        assertEquals("attribute.ref_kind", code(() -> service.create("ORG_UNIT", "kind", "REF_ITEM", null, null, null, null)));
        assertEquals("attribute.ref_kind", code(() -> service.create("ORG_UNIT", "kind", "STRING", "GRADE", null,
                null, null)));
        assertEquals("invalid.choice", code(() -> service.create("ROOM", "x", "STRING", null, null, null, null)));
        assertEquals("invalid.choice", code(() -> service.create("ORG_UNIT", "x", "LIST", null, null, null, null)));
        assertEquals("invalid.required", code(() -> service.create("ORG_UNIT", "x", null, null, null, null, null)));
        assertEquals(1, store.definitions.size());
    }

    @Test
    void aNewRequiredAttributeFillsEveryRowWithNoValue() {
        values.add(Target.ORG_UNIT, "u-1", Map.of());
        values.add(Target.ORG_UNIT, "u-1", Map.of("note", "x"));
        values.add(Target.ORG_UNIT, "u-2", Map.of());
        values.add(Target.PERSON, "p-1", Map.of());

        FuhitoException missing = assertThrows(FuhitoException.class,
                () -> service.create("ORG_UNIT", "beds", "NUMBER", null, null, true, null));
        assertEquals("attribute.fill_value_required", missing.code());
        assertEquals(Map.of("target", "ORG_UNIT", "name", "beds", "count", "3"), missing.params());
        assertEquals(List.of(), store.definitions);

        AttributeDefinitionService.Written written = service.create("ORG_UNIT", "beds", "NUMBER", null, null, true,
                30);
        assertTrue(written.definition().required());
        assertEquals(List.of(new Write.OfAttributeValue(Target.ORG_UNIT, "u-1"),
                new Write.OfAttributeValue(Target.ORG_UNIT, "u-2")), written.values());
        assertEquals(List.of(Map.of("beds", 30), Map.of("note", "x", "beds", 30), Map.of("beds", 30), Map.of()),
                values.rows.stream().map(MemoryAttributeValueStore.Row::attributes).toList());
        // Each resource counts up once, however many of its versions got the value.
        assertEquals(Map.of("u-1", 2L, "u-2", 2L, "p-1", 1L), values.versions);
    }

    @Test
    void aChangeToRequiredFillsOnlyTheRowsWithNoValue() {
        refItems.insert(new RefItem("site-1", RefKind.OCCUPATION, "nurse", ItemNames.of("Nurse"), 0, Map.of(), true));
        AttributeDefinition sites = service.create("PERSON", "sites", "REF_ITEM", "OCCUPATION", true, null, null)
                .definition();
        values.add(Target.PERSON, "p-1", Map.of("sites", List.of("nurse")));
        Map<String, Object> cleared = new HashMap<>();
        cleared.put("sites", null);
        values.add(Target.PERSON, "p-2", cleared);
        values.add(Target.PERSON, "p-3", Map.of());

        assertEquals("attribute.fill_value_required", code(() -> service.replace(sites.id(), null, null, null, true,
                null)));
        assertFalse(service.get(sites.id()).required());
        AttributeDefinitionService.Written written = service.replace(sites.id(), "REF_ITEM", "OCCUPATION", true,
                true, List.of("nurse"));
        assertEquals(List.of("p-2", "p-3"), written.values().stream().map(Write::resourceId).toList());
        assertEquals(List.of(Map.of("sites", List.of("nurse"))), values.rows.stream()
                .map(MemoryAttributeValueStore.Row::attributes).distinct().toList());
        assertEquals(Map.of("p-1", 1L, "p-2", 2L, "p-3", 2L), values.versions);

        // A read of a required definition is a body of a write. Every row has a value, so nothing is filled.
        assertEquals(List.of(), service.replace(sites.id(), "REF_ITEM", "OCCUPATION", true, true, null).values());
        assertEquals(List.of(), service.replace(sites.id(), null, null, null, true, List.of("nurse")).values());
        assertEquals(Map.of("p-1", 1L, "p-2", 2L, "p-3", 2L), values.versions);
    }

    @Test
    void aFillWithNoRowWithoutAValueWritesNothing() {
        values.add(Target.PERSON, "p-1", Map.of("note", "x"));
        AttributeDefinition note = service.create("PERSON", "note", "STRING", null, null, null, null).definition();

        assertEquals(List.of(), service.replace(note.id(), null, null, null, true, "none").values());
        assertEquals(List.of(), service.create("ORG_UNIT", "beds", "NUMBER", null, null, true, 1).values());
        assertEquals(Map.of("p-1", 1L), values.versions);
    }

    @Test
    void theValueToFillMustFitTheDefinition() {
        refItems.insert(new RefItem("site-1", RefKind.OCCUPATION, "nurse", ItemNames.of("Nurse"), 0, Map.of(), true));
        values.add(Target.PERSON, "p-1", Map.of());

        FuhitoException notRequired = assertThrows(FuhitoException.class,
                () -> service.create("PERSON", "beds", "NUMBER", null, null, false, 3));
        assertEquals("invalid.not_together", notRequired.code());
        assertEquals(Map.of("field", "fillValue", "otherField", "required"), notRequired.params());
        assertEquals("invalid.not_together", code(() -> service.create("PERSON", "beds", "NUMBER", null, null, null,
                3)));
        assertEquals("attribute.bad_value", code(() -> service.create("PERSON", "beds", "NUMBER", null, null, true,
                "3")));
        assertEquals("attribute.bad_value", code(() -> service.create("PERSON", "beds", "NUMBER", null, null, true,
                new BigDecimal("1e400"))));
        assertEquals("attribute.bad_value", code(() -> service.create("PERSON", "beds", "NUMBER", null, true, true,
                3)));
        assertEquals("attribute.bad_value", code(() -> service.create("PERSON", "beds", "NUMBER", null, null, true,
                List.of(3))));
        assertEquals("attribute.bad_value", code(() -> service.create("PERSON", "job", "REF_ITEM", "OCCUPATION", null,
                true, "clerk")));
        assertEquals("invalid.blank", code(() -> service.create("PERSON", "jobs", "REF_ITEM", "OCCUPATION", true,
                true, List.of())));
        assertEquals("invalid.blank", code(() -> service.create("PERSON", "note", "STRING", null, null, true, " ")));
        assertEquals("attribute.too_large", code(() -> service.create("PERSON", "note", "STRING", null, null, true,
                "a".repeat(65_536))));
        assertEquals(List.of(), store.definitions);
        assertEquals(List.of(Map.of()), values.rows.stream().map(MemoryAttributeValueStore.Row::attributes).toList());

        AttributeDefinition note = service.create("PERSON", "note", "STRING", null, null, true, "x").definition();
        assertEquals("invalid.not_together", code(() -> service.replace(note.id(), null, null, null, false, "y")));
        assertEquals(List.of("p-1"), service.create("PERSON", "jobs", "REF_ITEM", "OCCUPATION", true, true,
                List.of("nurse")).values().stream().map(Write::resourceId).toList());
    }

    @Test
    void aFillThatMakesARowTooLargeFails() {
        values.add(Target.PERSON, "p-1", Map.of("note", "a".repeat(65_500)));

        FuhitoException large = assertThrows(FuhitoException.class,
                () -> service.create("PERSON", "memo", "STRING", null, null, true, "b".repeat(100)));
        assertEquals("attribute.too_large", large.code());
        assertEquals(Map.of("size", "65621", "max", "65536"), large.params());
        assertEquals(List.of(), store.definitions);
    }

    @Test
    void aDeleteFailsWhileARowHasTheNameOrClearsIt() {
        AttributeDefinition note = service.create("ASSIGNMENT", "note", "STRING", null, null, null, null)
                .definition();
        values.add(Target.ASSIGNMENT, "a-1", Map.of("note", "x"));
        values.add(Target.ASSIGNMENT, "a-1", Map.of("note", "y", "beds", 1));
        Map<String, Object> withNull = new HashMap<>();
        withNull.put("note", null);
        values.add(Target.ASSIGNMENT, "a-2", withNull);
        values.add(Target.ASSIGNMENT, "a-3", Map.of("beds", 2));
        values.add(Target.PERSON, "p-1", Map.of("note", "z"));

        FuhitoException inUse = assertThrows(FuhitoException.class, () -> service.delete(note.id(), false));
        assertEquals("attribute.in_use", inUse.code());
        assertEquals(Map.of("id", note.id(), "target", "ASSIGNMENT", "name", "note", "count", "3"), inUse.params());
        assertEquals(1, store.definitions.size());

        assertEquals(List.of(new Write.OfAttributeValue(Target.ASSIGNMENT, "a-1"),
                new Write.OfAttributeValue(Target.ASSIGNMENT, "a-2")), service.delete(note.id(), true));
        assertEquals(List.of(Map.of(), Map.of("beds", 1), Map.of(), Map.of("beds", 2), Map.of("note", "z")),
                values.rows.stream().map(MemoryAttributeValueStore.Row::attributes).toList());
        assertEquals(Map.of("a-1", 2L, "a-2", 2L, "a-3", 1L, "p-1", 1L), values.versions);
        assertEquals(List.of(), store.definitions);

        AttributeDefinition unused = service.create("ASSIGNMENT", "note", "STRING", null, null, null, null)
                .definition();
        assertEquals(List.of(), service.delete(unused.id(), false));
        assertEquals("attribute.not_found", code(() -> service.delete(unused.id(), true)));
    }

    @Test
    void rulesOfGivenDefinitionsAndItemsReadNothingOfTheRealm() {
        service.create("PERSON", "note", "NUMBER", null, null, null, null);
        refItems.insert(new RefItem("site-1", RefKind.OCCUPATION, "clerk", ItemNames.of("Clerk"), 0, Map.of(), true));
        AttributeRules rules = AttributeDefinitionService.of(List.of(
                new AttributeDefinition("d-1", Target.PERSON, "note", AttributeDefinition.Type.STRING, null, false,
                        false, 1),
                new AttributeDefinition("d-2", Target.PERSON, "jobs", AttributeDefinition.Type.REF_ITEM, "OCCUPATION",
                        true, true, 1),
                new AttributeDefinition("d-3", Target.ASSIGNMENT, "beds", AttributeDefinition.Type.NUMBER, null,
                        false, true, 1)),
                (kind, code) -> kind.equals("OCCUPATION") && code.equals("nurse"));

        Map<String, Object> good = Map.of("note", "a", "jobs", List.of("nurse"));
        assertEquals(good, rules.check(Target.PERSON, good));
        assertEquals(Map.of("beds", 1), rules.check(Target.ASSIGNMENT, Map.of("beds", 1)));
        assertEquals(Map.of("name", "jobs", "type", "REF_ITEM[] OCCUPATION"), assertThrows(FuhitoException.class,
                () -> rules.check(Target.PERSON, Map.of("jobs", List.of("clerk")))).params());
        assertEquals("attribute.bad_value", code(() -> rules.check(Target.PERSON, Map.of("note", 1,
                "jobs", List.of("nurse")))));
        assertEquals("attribute.bad_value", code(() -> rules.check(Target.PERSON, Map.of("jobs", "nurse"))));
        assertEquals(Map.of("target", "PERSON", "name", "jobs"), assertThrows(FuhitoException.class,
                () -> rules.check(Target.PERSON, Map.of("note", "a"))).params());
        assertEquals("attribute.unknown", code(() -> rules.check(Target.ORG_UNIT, Map.of("note", "a"))));
        assertEquals("attribute.too_large", code(() -> rules.check(Target.ORG_UNIT,
                Map.of("note", "x".repeat(70_000)))));
    }
}
