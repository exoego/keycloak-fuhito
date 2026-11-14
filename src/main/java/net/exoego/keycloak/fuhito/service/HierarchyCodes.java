package net.exoego.keycloak.fuhito.service;

import java.util.Map;

import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.store.OrgUnitStore;

/**
 * Finds the hierarchy that a request names by its code.
 */
final class HierarchyCodes {

    private HierarchyCodes() {
    }

    /**
     * Returns the ID of a hierarchy.
     *
     * @param store the storage of org units of the realm
     * @param code the code of the hierarchy, or null for the default one
     * @return the ID
     * @throws FuhitoException when the realm has no hierarchy with this code
     */
    static String idOf(OrgUnitStore store, String code) {
        String defaultId = store.defaultHierarchyId();
        if (code == null) {
            return defaultId;
        }
        return store.hierarchyCodes().entrySet().stream()
                .filter(entry -> entry.getValue().equals(code))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> FuhitoException.invalid("hierarchy.unknown", Map.of("code", code)));
    }
}
