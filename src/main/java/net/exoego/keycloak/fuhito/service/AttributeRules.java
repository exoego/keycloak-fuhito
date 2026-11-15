package net.exoego.keycloak.fuhito.service;

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
     * @throws FuhitoException when the attributes are larger than 64 KB as JSON, a value does not fit its
     *         definition, a required one is missing, or the realm rejects a name that has no definition
     */
    Map<String, Object> check(AttributeDefinition.Target target, Map<String, Object> attributes);
}
