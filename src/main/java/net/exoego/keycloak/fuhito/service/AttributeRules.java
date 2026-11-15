package net.exoego.keycloak.fuhito.service;

import java.util.Map;

import net.exoego.keycloak.fuhito.domain.AttributeDefinition;
import net.exoego.keycloak.fuhito.domain.FuhitoException;

/**
 * Checks the free attributes of a write against the definitions of the realm.
 */
public interface AttributeRules {

    /** Rules that accept every value. */
    AttributeRules NONE = (target, attributes) -> attributes == null ? Map.of() : attributes;

    /**
     * Checks the attributes of one write.
     *
     * @param target the kind of resource
     * @param attributes the attributes of the write, or null for none
     * @return the attributes to store, never null
     * @throws FuhitoException when a value does not fit its definition, a required one is missing, or the realm
     *         rejects a name that has no definition
     */
    Map<String, Object> check(AttributeDefinition.Target target, Map<String, Object> attributes);
}
