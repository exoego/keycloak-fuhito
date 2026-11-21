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
    AttributeRules NONE = (target, attributes) -> AttributeSize.require(StoredAttributes.of(attributes));

    /**
     * Checks the attributes of one write. Each name needs a definition, also a name with null, as
     * {@link #requireDefined} checks. A name with null has no value, so the stored attributes leave it out, and a
     * required name with null is missing.
     *
     * @param target the kind of resource
     * @param attributes the attributes of the write, or null for none
     * @return the attributes to store, without the names with null, never null
     * @throws FuhitoException when the attributes are larger than 64 KB as JSON, a text is empty or only spaces, a
     *         name has no definition, a value does not fit its definition, or a required one is missing
     */
    Map<String, Object> check(AttributeDefinition.Target target, Map<String, Object> attributes);

    /**
     * Requires a definition for each name of the attributes of a write, also for a name with null. An import merges
     * a row into the stored attributes, and a row that changes nothing is not checked again. So the import checks the
     * names of the row first. The default accepts every name, as {@link #NONE} does.
     *
     * @param target the kind of resource
     * @param attributes the attributes of the write, or null for none
     * @throws FuhitoException when a name has no definition
     */
    default void requireDefined(AttributeDefinition.Target target, Map<String, Object> attributes) {
    }

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
