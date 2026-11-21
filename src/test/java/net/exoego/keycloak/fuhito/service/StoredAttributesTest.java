package net.exoego.keycloak.fuhito.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.exoego.keycloak.fuhito.domain.AttributeDefinition.Target;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StoredAttributesTest {

    private static Map<String, Object> withNull(String name, Map<String, Object> others) {
        Map<String, Object> attributes = new LinkedHashMap<>(others);
        attributes.put(name, null);
        return attributes;
    }

    @Test
    void aNameWithNullIsLeftOut() {
        Map<String, Object> given = new LinkedHashMap<>();
        given.put("b", 1);
        given.put("note", null);
        given.put("a", "x");

        Map<String, Object> stored = StoredAttributes.of(given);

        assertEquals(Map.of("b", 1, "a", "x"), stored);
        assertEquals(List.of("b", "a"), new ArrayList<>(stored.keySet()));
        assertEquals(Map.of(), StoredAttributes.of(withNull("note", Map.of())));
        assertEquals(Map.of(), StoredAttributes.of(null));
        assertEquals(Map.of("a", "x"), StoredAttributes.of(Map.of("a", "x")));
    }

    // Only a name has no value with null. A list with null is a value, and its definition checks it.
    @Test
    void aListWithNullKeepsIt() {
        List<Object> list = Arrays.asList("a", null);

        assertEquals(Map.of("tags", list), StoredAttributes.of(Map.of("tags", list)));
    }

    @Test
    void everyCheckOfAWriteGivesTheStoredForm() {
        AttributeDefinitionService defined = new AttributeDefinitionService(new MemoryAttributeDefinitionStore(),
                new MemoryAttributeValueStore(), new MemoryRefItemStore());
        defined.create("PERSON", "note", "STRING", null, null, null, null);
        defined.create("PERSON", "badge", "STRING", null, null, true, null);
        Map<String, Object> given = withNull("note", Map.of("badge", "a"));

        for (AttributeRules rules : List.of(AttributeRules.NONE, defined, defined.remembering(),
                AttributeDefinitionService.of(defined.list(null), (kind, code) -> false))) {
            assertEquals(Map.of("badge", "a"), rules.check(Target.PERSON, given));
            // A name with null needs no definition, because it has no value.
            assertEquals(Map.of("badge", "a"), rules.check(Target.PERSON, withNull("free", Map.of("badge", "a"))));
        }
        for (AttributeRules rules : List.of(defined, defined.remembering(),
                AttributeDefinitionService.of(defined.list(null), (kind, code) -> false))) {
            assertEquals("attribute.required", assertThrows(FuhitoException.class,
                    () -> rules.check(Target.PERSON, withNull("badge", new HashMap<>()))).code());
        }
    }
}
