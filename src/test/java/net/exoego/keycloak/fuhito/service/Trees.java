package net.exoego.keycloak.fuhito.service;

import java.util.List;

/**
 * Reads every org unit of a date for a test, root by root. The API has no read of the whole tree.
 */
final class Trees {

    private Trees() {
    }

    static List<OrgUnitState> all(OrgUnitService service, String asOf, String hierarchy) {
        Page page = Page.of(null, 1000);
        return service.list(new OrgUnitService.Search(null, null, null, null, null, null, true, hierarchy, asOf), page, null)
                .stream()
                .flatMap(root -> service.subtree(root.unit().id(), asOf, hierarchy, null, page).stream())
                .toList();
    }
}
