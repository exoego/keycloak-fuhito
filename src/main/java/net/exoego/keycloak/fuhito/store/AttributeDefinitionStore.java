package net.exoego.keycloak.fuhito.store;

import java.util.List;
import java.util.Optional;

import net.exoego.keycloak.fuhito.domain.AttributeDefinition;

/**
 * Storage of the definitions of free attributes of one realm.
 */
public interface AttributeDefinitionStore {

    /**
     * Lists the definitions of one kind of resource, or of all, by kind of resource and name.
     *
     * @param target the kind of resource, or null for all
     * @return the definitions
     */
    List<AttributeDefinition> list(AttributeDefinition.Target target);

    /**
     * Finds a definition by ID.
     *
     * @param id the ID
     * @return the definition, or empty when the realm has none with the ID
     */
    Optional<AttributeDefinition> find(String id);

    /**
     * Stores a new definition.
     *
     * @param definition the definition
     */
    void insert(AttributeDefinition definition);

    /**
     * Stores the changed type and rule of a definition.
     *
     * @param definition the definition
     */
    void update(AttributeDefinition definition);

    /**
     * Deletes a definition.
     *
     * @param id the ID
     */
    void delete(String id);
}
