package net.exoego.keycloak.fuhito.service;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.PlacedOrgUnit;
import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.store.OrgUnitStore;
import net.exoego.keycloak.fuhito.store.RefItemStore;

/**
 * Reads the way from a root down to one org unit, without the rest of the tree.
 */
final class OrgUnitPaths {

    private OrgUnitPaths() {
    }

    /**
     * Lists the units from the root down to an org unit on a date.
     *
     * @param store the storage of org units of the realm
     * @param refItems the storage of reference items of the realm
     * @param hierarchyId the ID of the hierarchy
     * @param id the ID of the unit
     * @param date the date
     * @return the path, with the root first and the unit last
     * @throws FuhitoException when the unit does not exist on the date
     */
    static List<OrgUnitState> on(OrgUnitStore store, RefItemStore refItems, String hierarchyId, String id,
            LocalDate date) {
        List<PlacedOrgUnit> path = store.pathOn(hierarchyId, id, date);
        if (path.isEmpty()) {
            throw FuhitoException.notFound("org_unit.not_valid_on", Map.of("id", id, "date", date.toString()));
        }
        return states(path, refItems);
    }

    /**
     * Gives each unit the code of its unit type.
     *
     * @param placed the units
     * @param refItems the storage of reference items of the realm
     * @return the units as states, in the same order
     */
    static List<OrgUnitState> states(List<PlacedOrgUnit> placed, RefItemStore refItems) {
        Map<String, Optional<RefItem>> unitTypes = new HashMap<>();
        return placed.stream()
                .map(row -> OrgUnitState.of(row.unit(), row.version(), row.version().unitTypeId() == null ? null
                        : unitTypes.computeIfAbsent(row.version().unitTypeId(), refItems::find).orElse(null),
                        row.parentId()))
                .toList();
    }
}
