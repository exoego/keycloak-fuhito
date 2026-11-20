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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttributeDefinitionServiceTest {

    private final MemoryAttributeDefinitionStore store = new MemoryAttributeDefinitionStore();
    private final MemoryRefItemStore refItems = new MemoryRefItemStore();
    private final AttributeDefinitionService service = new AttributeDefinitionService(store, refItems);

    private static String code(Runnable call) {
        return assertThrows(FuhitoException.class, call::run).code();
    }

    @Test
    void definesChecksAndChangesAttributes() {
        refItems.insert(new RefItem("site-1", RefKind.OCCUPATION, "nurse", ItemNames.of("Nurse"), 0, Map.of(), true));
        AttributeDefinition shift = service.create("ASSIGNMENT", "shift", "STRING", null, null, true);
        service.create("ASSIGNMENT", "beds", "NUMBER", null, null, null);
        service.create("ASSIGNMENT", "night", "BOOLEAN", null, null, null);
        service.create("ASSIGNMENT", "since", "DATE", null, null, null);
        service.create("ASSIGNMENT", "also", "REF_ITEM", "OCCUPATION", null, null);
        service.create("PERSON", "shift", "NUMBER", null, null, null);

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
                Map.of("shift", "a", "also", "clerk"))) {
            assertEquals("attribute.bad_value", code(() -> service.check(Target.ASSIGNMENT, bad)), bad.toString());
        }
        assertEquals(Map.of("shift", new BigDecimal("1.5")), service.check(Target.PERSON,
                Map.of("shift", new BigDecimal("1.5"))));
        assertEquals("attribute.unknown", code(() -> service.check(Target.ASSIGNMENT,
                Map.of("shift", "early", "free", 1))));
        assertEquals("attribute.unknown", code(() -> service.check(Target.ORG_UNIT, Map.of("shift", "early"))));
        assertEquals(Map.of(), service.check(Target.ORG_UNIT, null));

        service.replace(shift.id(), "NUMBER", null, null, false);
        assertEquals(Map.of("shift", 2), service.check(Target.ASSIGNMENT, Map.of("shift", 2)));
        service.delete(shift.id());
        assertEquals("attribute.not_found", code(() -> service.get(shift.id())));
    }

    @Test
    void checksEachElementOfAList() {
        refItems.insert(new RefItem("site-1", RefKind.OCCUPATION, "nurse", ItemNames.of("Nurse"), 0, Map.of(), true));
        refItems.insert(new RefItem("site-2", RefKind.OCCUPATION, "clerk", ItemNames.of("Clerk"), 1, Map.of(), true));
        AttributeDefinition sites = service.create("ASSIGNMENT", "sites", "REF_ITEM", "OCCUPATION", true, true);
        service.create("ASSIGNMENT", "days", "NUMBER", null, true, null);
        service.create("ASSIGNMENT", "note", "STRING", null, false, null);

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
                Map.of("sites", List.of("nurse"), "note", List.of("a")))) {
            assertEquals("attribute.bad_value", code(() -> service.check(Target.ASSIGNMENT, bad)), bad.toString());
        }
        assertEquals(Map.of("name", "sites", "type", "REF_ITEM[] OCCUPATION"), assertThrows(FuhitoException.class,
                () -> service.check(Target.ASSIGNMENT, Map.of("sites", "nurse"))).params());
        assertEquals(Map.of("name", "days", "type", "NUMBER[]"), assertThrows(FuhitoException.class,
                () -> service.check(Target.ASSIGNMENT, Map.of("sites", List.of("nurse"), "days", 1))).params());

        AttributeDefinition single = service.replace(sites.id(), "REF_ITEM", "OCCUPATION", false, true);
        assertFalse(single.multiple());
        assertEquals("attribute.bad_value", code(() -> service.check(Target.ASSIGNMENT,
                Map.of("sites", List.of("nurse")))));
    }

    @Test
    void anAttributeRejectsAnEmptyText() {
        service.create("ASSIGNMENT", "shifts", "STRING", null, true, null);
        service.create("ASSIGNMENT", "note", "STRING", null, false, null);
        service.create("ASSIGNMENT", "since", "DATE", null, false, null);

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
        service.create("ORG_UNIT", "beds", "NUMBER", null, null, null);

        assertEquals("attribute.name_taken", code(() -> service.create("ORG_UNIT", "beds", "STRING", null, null, null)));
        assertEquals("attribute.bad_name", code(() -> service.create("ORG_UNIT", " beds", "STRING", null, null, null)));
        assertEquals("attribute.bad_name", code(() -> service.create("ORG_UNIT", "x".repeat(65), "STRING", null, null,
                null)));
        assertEquals("attribute.ref_kind", code(() -> service.create("ORG_UNIT", "kind", "REF_ITEM", null, null, null)));
        assertEquals("attribute.ref_kind", code(() -> service.create("ORG_UNIT", "kind", "STRING", "GRADE", null,
                null)));
        assertEquals("invalid.choice", code(() -> service.create("ROOM", "x", "STRING", null, null, null)));
        assertEquals("invalid.choice", code(() -> service.create("ORG_UNIT", "x", "LIST", null, null, null)));
        assertEquals("invalid.required", code(() -> service.create("ORG_UNIT", "x", null, null, null, null)));
        assertEquals(1, store.definitions.size());
    }
}
