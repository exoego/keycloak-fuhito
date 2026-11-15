package net.exoego.keycloak.fuhito.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.exoego.keycloak.fuhito.domain.AttributeDefinition;
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
    private final AttributeRules attributeRules;

    /**
     * Creates the service.
     *
     * @param store the storage of the realm
     */
    public RefItemService(RefItemStore store) {
        this(store, AttributeRules.NONE);
    }

    /**
     * Creates the service with a check of free attributes.
     *
     * @param store the storage of the realm
     * @param attributeRules the check of free attributes against the definitions of the realm
     */
    public RefItemService(RefItemStore store, AttributeRules attributeRules) {
        this.store = store;
        this.attributeRules = attributeRules;
    }

    /**
     * Lists a page of the items of a kind.
     *
     * @param kind the kind
     * @param includeInactive true to also return inactive items
     * @param page the part of the list to return
     * @return the items of the page, ordered by display order and then by code
     */
    public List<RefItem> list(RefKind kind, boolean includeInactive, Page page) {
        return store.list(Checks.required("kind", kind), includeInactive, page.first(), page.max());
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
     * The marks of an item. Each kind of item can have only its own marks.
     *
     * @param legalEntity true for a unit type whose org units are legal entities, or null for false
     * @param actingHead true for an assignment kind whose heads stand in for an absent head, or null for false
     * @param group true for a unit type whose org units are groups outside the line organization, or null for
     *        false
     */
    public record Marks(Boolean legalEntity, Boolean actingHead, Boolean group) {

        /** No mark. */
        public static final Marks NONE = new Marks(null, null, null);

        static Marks of(RefItem item) {
            return new Marks(item.legalEntity(), item.actingHead(), item.group());
        }
    }

    /**
     * Creates an item with no mark.
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
        return create(kind, code, name, names, displayOrder, attributes, Marks.NONE);
    }

    /**
     * Creates an item with its marks.
     *
     * @param kind the kind
     * @param code the code
     * @param name the default name
     * @param names the names in other languages, or null
     * @param displayOrder the position in lists, or null for 0
     * @param attributes free values, or null
     * @param marks the marks
     * @return the new item
     * @throws FuhitoException when a value is bad, the code is taken, or the kind cannot have a mark
     */
    public RefItem create(RefKind kind, String code, String name, Map<String, String> names, Integer displayOrder,
            Map<String, Object> attributes, Marks marks) {
        Checks.required("kind", kind);
        Checks.code("code", code);
        if (store.findByCode(kind, code).isPresent()) {
            throw FuhitoException.conflict("ref_item.code_taken", Map.of("kind", kind.name(), "code", code));
        }
        RefItem item = item(UUID.randomUUID().toString(), kind, code, name, names, displayOrder, attributes, true,
                marks);
        store.insert(item);
        return item;
    }

    /**
     * Replaces the changeable values of an item, and keeps its marks. The kind and the code never change.
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
        return update(id, name, names, displayOrder, attributes, active, Marks.of(get(id)));
    }

    /**
     * Replaces the changeable values of an item, with its marks.
     *
     * @param id the ID
     * @param name the default name
     * @param names the names in other languages, or null for none
     * @param displayOrder the position in lists, or null for 0
     * @param attributes free values, or null for none
     * @param active false to stop the use of the item for new data, or null for true
     * @param marks the marks
     * @return the changed item
     * @throws FuhitoException when the item does not exist, a value is bad, the kind cannot have a mark, or the
     *         mark of a legal entity is in use
     */
    public RefItem update(String id, String name, Map<String, String> names, Integer displayOrder,
            Map<String, Object> attributes, Boolean active, Marks marks) {
        RefItem current = get(id);
        if (current.legalEntity() && !Boolean.TRUE.equals(marks.legalEntity()) && store.isTypeOfAnEmployer(id)) {
            throw FuhitoException.conflict("ref_item.legal_entity_in_use", Map.of("id", id, "code", current.code()));
        }
        RefItem changed = item(current.id(), current.kind(), current.code(), name, names, displayOrder, attributes,
                active == null || active, marks);
        // Only org units refer to a unit type, so an item in use is the type of an org unit.
        if (changed.group() != current.group() && store.isInUse(id)) {
            throw FuhitoException.conflict("ref_item.group_in_use", Map.of("id", id, "code", current.code()));
        }
        store.update(changed);
        return changed;
    }

    /**
     * Deletes an item. An item that other data refers to cannot be deleted. Deactivate it instead.
     *
     * @param id the ID
     * @throws FuhitoException when the item does not exist or is in use
     */
    public void delete(String id) {
        get(id);
        if (store.isInUse(id)) {
            throw FuhitoException.conflict("ref_item.in_use", Map.of("id", id));
        }
        store.delete(id);
    }

    private RefItem item(String id, RefKind kind, String code, String name, Map<String, String> names,
            Integer displayOrder, Map<String, Object> attributes, boolean active, Marks marks) {
        boolean legalEntity = mark(kind, RefKind.UNIT_TYPE, marks.legalEntity(), "ref_item.legal_entity_kind");
        boolean actingHead = mark(kind, RefKind.ASSIGNMENT_KIND, marks.actingHead(), "ref_item.acting_head_kind");
        boolean group = mark(kind, RefKind.UNIT_TYPE, marks.group(), "ref_item.group_kind");
        if (legalEntity && group) {
            throw FuhitoException.invalid("ref_item.group_legal_entity", Map.of("code", code));
        }
        return new RefItem(id, kind, code, Checks.name("name", name), Checks.localizedNames("names", names),
                displayOrder == null ? 0 : displayOrder,
                attributeRules.check(AttributeDefinition.Target.REF_ITEM, attributes), active,
                legalEntity, actingHead, group);
    }

    private static boolean mark(RefKind kind, RefKind allowed, Boolean value, String error) {
        boolean marked = Boolean.TRUE.equals(value);
        if (marked && kind != allowed) {
            throw FuhitoException.invalid(error, Map.of("kind", kind.name()));
        }
        return marked;
    }
}
