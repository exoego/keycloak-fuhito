package net.exoego.keycloak.fuhito.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.domain.RefKind;
import net.exoego.keycloak.fuhito.store.RefItemStore;

/**
 * Operations on the reference items of one realm.
 */
public class RefItemService {

    private final RefItemStore store;

    /**
     * Creates the service.
     *
     * @param store the storage of the realm
     */
    public RefItemService(RefItemStore store) {
        this.store = store;
    }

    /**
     * Lists the items of a kind.
     *
     * @param kind the kind
     * @param includeInactive true to also return inactive items
     * @return the items, ordered by display order and then by code
     */
    public List<RefItem> list(RefKind kind, boolean includeInactive) {
        return store.list(Checks.required("kind", kind), includeInactive);
    }

    /**
     * Returns an item.
     *
     * @param id the ID
     * @return the item
     * @throws FuhitoException when the realm has no such item
     */
    public RefItem get(String id) {
        return store.find(id).orElseThrow(() -> FuhitoException.notFound("ref_item.not_found", Map.of("id", id)));
    }

    /**
     * Creates an item.
     *
     * @param kind the kind
     * @param code the code, which must be new for the kind
     * @param name the default name
     * @param names the names in other languages, or null
     * @param displayOrder the position in lists, or null for 0
     * @param attributes free values, or null
     * @return the new item
     * @throws FuhitoException when a value is bad or the code is taken
     */
    public RefItem create(RefKind kind, String code, String name, Map<String, String> names, Integer displayOrder,
            Map<String, Object> attributes) {
        Checks.required("kind", kind);
        Checks.code("code", code);
        if (store.exists(kind, code)) {
            throw FuhitoException.conflict("ref_item.code_taken", Map.of("kind", kind.name(), "code", code));
        }
        RefItem item = new RefItem(UUID.randomUUID().toString(), kind, code, Checks.name("name", name),
                Checks.localizedNames("names", names), displayOrder == null ? 0 : displayOrder,
                attributes == null ? Map.of() : attributes, true);
        store.insert(item);
        return item;
    }

    /**
     * Replaces the changeable values of an item. The kind and the code never change.
     *
     * @param id the ID
     * @param name the default name
     * @param names the names in other languages, or null for none
     * @param displayOrder the position in lists, or null for 0
     * @param attributes free values, or null for none
     * @param active false to stop the use of the item for new data, or null for true
     * @return the changed item
     * @throws FuhitoException when the item does not exist or a value is bad
     */
    public RefItem update(String id, String name, Map<String, String> names, Integer displayOrder,
            Map<String, Object> attributes, Boolean active) {
        RefItem current = get(id);
        RefItem changed = new RefItem(current.id(), current.kind(), current.code(), Checks.name("name", name),
                Checks.localizedNames("names", names), displayOrder == null ? 0 : displayOrder,
                attributes == null ? Map.of() : attributes, active == null || active);
        store.update(changed);
        return changed;
    }

    /**
     * Deletes an item.
     *
     * @param id the ID
     * @throws FuhitoException when the item does not exist
     */
    public void delete(String id) {
        get(id);
        store.delete(id);
    }
}
