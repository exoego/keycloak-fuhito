package net.exoego.keycloak.fuhito.service;

import java.math.BigDecimal;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttributeDefinitionServiceTest {

    private final MemoryAttributeDefinitionStore store = new MemoryAttributeDefinitionStore();
    private final MemoryRefItemStore refItems = new MemoryRefItemStore();
    private final AttributeDefinitionService lenient = new AttributeDefinitionService(store, refItems, false);
    private final AttributeDefinitionService strict = new AttributeDefinitionService(store, refItems, true);

    private static String code(Runnable call) {
        return assertThrows(FuhitoException.class, call::run).code();
    }

    @Test
    void definesChecksAndChangesAttributes() {
        refItems.insert(new RefItem("site-1", RefKind.OCCUPATION, "nurse", "Nurse", Map.of(), 0, Map.of(), true));
        AttributeDefinition shift = lenient.create("ASSIGNMENT", "shift", "STRING", null, true);
        lenient.create("ASSIGNMENT", "beds", "NUMBER", null, null);
        lenient.create("ASSIGNMENT", "night", "BOOLEAN", null, null);
        lenient.create("ASSIGNMENT", "since", "DATE", null, null);
        lenient.create("ASSIGNMENT", "also", "REF_ITEM", "OCCUPATION", null);
        lenient.create("PERSON", "shift", "NUMBER", null, null);

        assertTrue(shift.required());
        assertEquals(List.of("also", "beds", "night", "shift", "since"), lenient.list("ASSIGNMENT").stream()
                .map(AttributeDefinition::name).toList());
        Map<String, Object> good = Map.of("shift", "early", "beds", 3, "night", true, "since", "2026-04-01",
                "also", "nurse", "free", List.of(1, 2));
        assertEquals(good, lenient.check(Target.ASSIGNMENT, good));
        assertEquals("attribute.required", code(() -> lenient.check(Target.ASSIGNMENT, Map.of("beds", 1))));
        Map<String, Object> missing = new HashMap<>(Map.of("beds", 1));
        missing.put("shift", null);
        assertEquals("attribute.required", code(() -> lenient.check(Target.ASSIGNMENT, missing)));
        for (Map<String, Object> bad : List.<Map<String, Object>>of(Map.of("shift", 1), Map.of("shift", "a", "beds", "3"),
                Map.of("shift", "a", "night", "yes"), Map.of("shift", "a", "since", "April"),
                Map.of("shift", "a", "also", "clerk"))) {
            assertEquals("attribute.bad_value", code(() -> lenient.check(Target.ASSIGNMENT, bad)), bad.toString());
        }
        assertEquals(Map.of("shift", new BigDecimal("1.5")), lenient.check(Target.PERSON,
                Map.of("shift", new BigDecimal("1.5"))));
        assertEquals("attribute.unknown", code(() -> strict.check(Target.ASSIGNMENT, good)));
        assertEquals(Map.of(), strict.check(Target.ORG_UNIT, null));

        lenient.replace(shift.id(), "NUMBER", null, false);
        assertEquals(Map.of("shift", 2), lenient.check(Target.ASSIGNMENT, Map.of("shift", 2)));
        lenient.delete(shift.id());
        assertEquals("attribute.not_found", code(() -> lenient.get(shift.id())));
    }

    @Test
    void rejectsABadDefinition() {
        lenient.create("ORG_UNIT", "beds", "NUMBER", null, null);

        assertEquals("attribute.name_taken", code(() -> lenient.create("ORG_UNIT", "beds", "STRING", null, null)));
        assertEquals("attribute.bad_name", code(() -> lenient.create("ORG_UNIT", " beds", "STRING", null, null)));
        assertEquals("attribute.bad_name", code(() -> lenient.create("ORG_UNIT", "x".repeat(65), "STRING", null,
                null)));
        assertEquals("attribute.ref_kind", code(() -> lenient.create("ORG_UNIT", "kind", "REF_ITEM", null, null)));
        assertEquals("attribute.ref_kind", code(() -> lenient.create("ORG_UNIT", "kind", "STRING", "GRADE",
                null)));
        assertEquals("invalid.choice", code(() -> lenient.create("ROOM", "x", "STRING", null, null)));
        assertEquals("invalid.choice", code(() -> lenient.create("ORG_UNIT", "x", "LIST", null, null)));
        assertEquals("invalid.required", code(() -> lenient.create("ORG_UNIT", "x", null, null, null)));
        assertEquals(1, store.definitions.size());
    }
}
