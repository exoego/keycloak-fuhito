package net.exoego.keycloak.fuhito.service;

import java.util.List;
import java.util.Map;

import net.exoego.keycloak.fuhito.domain.AttributeDefinition;
import net.exoego.keycloak.fuhito.domain.FuhitoException;

/**
 * Checks the free attributes of a write against the definitions of the realm.
 */
public interface AttributeRules {

    /** Rules that accept every value that is not too large. */
    AttributeRules NONE = (target, attributes) -> AttributeSize.require(attributes == null ? Map.of() : attributes);

    /**
     * Checks the attributes of one write.
     *
     * @param target the kind of resource
     * @param attributes the attributes of the write, or null for none
     * @return the attributes to store, never null
     * @throws FuhitoException when the attributes are larger than 64 KB as JSON, a text is empty or only spaces, a
     *         name has no definition, a value does not fit its definition, or a required one is missing
     */
    Map<String, Object> check(AttributeDefinition.Target target, Map<String, Object> attributes);

    /**
     * Lists the definitions of the type {@code REF_ITEM} whose values are codes of items of a kind.
     *
     * @param refKind the code of the kind
     * @return the definitions, by kind of resource and name
     */
    default List<AttributeDefinition> ofRefKind(String refKind) {
        return List.of();
    }

    /**
     * Returns rules that read the definitions of each kind of resource once. They suit a list of writes that
     * changes no definition.
     *
     * @return the rules
     */
    default AttributeRules remembering() {
        return this;
    }
}
