package net.exoego.keycloak.fuhito.store;

import java.util.List;
import java.util.Optional;

import net.exoego.keycloak.fuhito.domain.Hierarchy;

/**
 * Storage of the hierarchies of one realm.
 */
public interface HierarchyStore {

    /**
     * Returns the ID of the default hierarchy, and creates that hierarchy when the realm has none yet.
     *
     * @return the ID
     */
    String defaultId();

    /**
     * Lists a page of the hierarchies, ordered by code.
     *
     * @param first the number of hierarchies to skip
     * @param max the largest number of hierarchies to return
     * @return the hierarchies of the page
     */
    List<Hierarchy> list(int first, int max);

    /**
     * Finds a hierarchy by its ID.
     *
     * @param id the ID
     * @return the hierarchy, or empty when the realm has none with this ID
     */
    Optional<Hierarchy> find(String id);

    /**
     * Finds a hierarchy by its code.
     *
     * @param code the code
     * @return the hierarchy, or empty when the realm has none with this code
     */
    Optional<Hierarchy> findByCode(String code);

    /**
     * Stores a new hierarchy.
     *
     * @param hierarchy the hierarchy
     */
    void insert(Hierarchy hierarchy);

    /**
     * Stores the new name of an existing hierarchy.
     *
     * @param hierarchy the hierarchy
     */
    void update(Hierarchy hierarchy);

    /**
     * Deletes a hierarchy.
     *
     * @param id the ID
     */
    void delete(String id);

    /**
     * Tells whether any parent link belongs to a hierarchy, on any date.
     *
     * @param id the ID of the hierarchy
     * @return true when the hierarchy has links
     */
    boolean hasLinks(String id);
}
