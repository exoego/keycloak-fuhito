package net.exoego.keycloak.fuhito.store;

import java.util.List;
import java.util.Optional;

import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.domain.RefKind;

/**
 * Storage of the reference items of one realm.
 */
public interface RefItemStore {

    /**
     * Lists every item of a kind, ordered by display order and then by code. This is for code that looks items up.
     *
     * @param kind the kind
     * @param includeInactive true to also return inactive items
     * @return the items
     */
    List<RefItem> list(RefKind kind, boolean includeInactive);

    /**
     * Lists a page of the items of a kind, ordered by display order and then by code.
     *
     * @param kind the kind
     * @param includeInactive true to also return inactive items
     * @param first the number of items to skip
     * @param max the largest number of items to return
     * @return the items of the page
     */
    List<RefItem> list(RefKind kind, boolean includeInactive, int first, int max);

    /**
     * Finds an item by ID.
     *
     * @param id the ID
     * @return the item, or empty when the realm has no such item
     */
    Optional<RefItem> find(String id);

    /**
     * Finds an item by kind and code.
     *
     * @param kind the kind
     * @param code the code
     * @return the item, or empty when the realm has no such item
     */
    Optional<RefItem> findByCode(RefKind kind, String code);

    /**
     * Tells whether other data refers to the item.
     *
     * @param id the ID
     * @return true when the item is in use
     */
    boolean isInUse(String id);

    /**
     * Stores a new item.
     *
     * @param item the item
     */
    void insert(RefItem item);

    /**
     * Stores the new state of an existing item.
     *
     * @param item the item
     */
    void update(RefItem item);

    /**
     * Removes an item.
     *
     * @param id the ID
     */
    void delete(String id);
}
