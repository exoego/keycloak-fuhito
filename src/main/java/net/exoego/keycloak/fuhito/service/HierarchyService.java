package net.exoego.keycloak.fuhito.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.Hierarchy;
import net.exoego.keycloak.fuhito.store.HierarchyStore;

/**
 * Operations on the hierarchies of one realm.
 */
public class HierarchyService {

    private final HierarchyStore store;

    /**
     * Creates the service.
     *
     * @param store the storage of the realm
     */
    public HierarchyService(HierarchyStore store) {
        this.store = store;
    }

    /**
     * Lists a page of the hierarchies, ordered by code. The default hierarchy is always among them.
     *
     * @param page the part of the list to return
     * @return the hierarchies of the page
     */
    public List<Hierarchy> list(Page page) {
        store.defaultId();
        return store.list(page.first(), page.max());
    }

    /**
     * Counts the hierarchies that {@link #list} lists on all pages together.
     *
     * @return the number of hierarchies
     */
    public long count() {
        store.defaultId();
        return store.count();
    }

    /**
     * Returns a hierarchy.
     *
     * @param id the ID
     * @return the hierarchy
     * @throws FuhitoException when the realm has no such hierarchy
     */
    public Hierarchy get(String id) {
        return store.find(id).orElseThrow(() -> FuhitoException.notFound("hierarchy.not_found", Map.of("id", id)));
    }

    /**
     * Finds a hierarchy by code. The default hierarchy is always there.
     *
     * @param code the code
     * @return the hierarchy, or empty when the realm has none with the code
     */
    public Optional<Hierarchy> find(String code) {
        store.defaultId();
        return store.findByCode(code);
    }

    /**
     * Creates a hierarchy.
     *
     * @param code the code
     * @param name the name
     * @return the new hierarchy
     * @throws FuhitoException when a value is bad or the code is taken
     */
    public Hierarchy create(String code, String name) {
        Checks.code("code", code);
        // The default hierarchy exists from here on, so its code is taken too.
        store.defaultId();
        if (store.findByCode(code).isPresent()) {
            throw FuhitoException.conflict("hierarchy.code_taken", Map.of("code", code));
        }
        Hierarchy hierarchy = new Hierarchy(UUID.randomUUID().toString(), code, Checks.name("name", name), 1);
        store.insert(hierarchy);
        return hierarchy;
    }

    /**
     * Renames a hierarchy. The code never changes.
     *
     * @param id the ID
     * @param name the new name
     * @return the changed hierarchy
     * @throws FuhitoException when the hierarchy does not exist or the name is bad
     */
    public Hierarchy rename(String id, String name) {
        Hierarchy current = get(id);
        store.update(new Hierarchy(id, current.code(), Checks.name("name", name), current.version()));
        return get(id);
    }

    /**
     * Deletes a hierarchy. The default hierarchy and a hierarchy with parent links cannot be deleted.
     *
     * @param id the ID
     * @throws FuhitoException when the hierarchy does not exist, is the default one, or has parent links
     */
    public void delete(String id) {
        Hierarchy hierarchy = get(id);
        if (hierarchy.isDefault()) {
            throw FuhitoException.conflict("hierarchy.default", Map.of("id", id));
        }
        if (store.hasLinks(id)) {
            throw FuhitoException.conflict("hierarchy.in_use", Map.of("id", id));
        }
        store.delete(id);
    }
}
