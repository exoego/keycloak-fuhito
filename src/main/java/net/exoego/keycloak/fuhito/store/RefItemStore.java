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
     * Lists the items of a kind, ordered by display order and then by code.
     *
     * @param kind the kind
     * @param includeInactive true to also return inactive items
     * @return the items
     */
    List<RefItem> list(RefKind kind, boolean includeInactive);

    /**
     * Finds an item by ID.
     *
     * @param id the ID
     * @return the item, or empty when the realm has no such item
     */
    Optional<RefItem> find(String id);

    /**
     * Tells whether the realm has an item with the kind and the code.
     *
     * @param kind the kind
     * @param code the code
     * @return true when such an item exists
     */
    boolean exists(RefKind kind, String code);

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
