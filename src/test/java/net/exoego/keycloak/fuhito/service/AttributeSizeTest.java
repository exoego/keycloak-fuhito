package net.exoego.keycloak.fuhito.service;

import java.util.Map;

import net.exoego.keycloak.fuhito.domain.AttributeDefinition.Target;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AttributeSizeTest {

    // {"x":"..."} has 8 bytes around the text.
    private static Map<String, Object> ofBytes(int bytes) {
        return Map.of("x", "a".repeat(bytes - 8));
    }

    @Test
    void attributesMayHave64KbAsJson() {
        AttributeRules defined = new AttributeDefinitionService(new MemoryAttributeDefinitionStore(),
                new MemoryRefItemStore(), false);
        for (AttributeRules rules : new AttributeRules[] {AttributeRules.NONE, defined}) {
            assertEquals(ofBytes(65_536), rules.check(Target.PERSON, ofBytes(65_536)));
            FuhitoException e = assertThrows(FuhitoException.class, () -> rules.check(Target.PERSON,
                    ofBytes(65_537)));
            assertEquals("attribute.too_large", e.code());
            assertEquals(Map.of("size", "65537", "max", "65536"), e.params());
        }
        // The limit counts bytes. A Japanese letter has three.
        assertEquals("attribute.too_large", assertThrows(FuhitoException.class,
                () -> AttributeRules.NONE.check(Target.ORG_UNIT, Map.of("x", "あ".repeat(21_843)))).code());
        assertEquals(1, AttributeRules.NONE.check(Target.ORG_UNIT, Map.of("x", "あ".repeat(21_842))).size());
    }
}
