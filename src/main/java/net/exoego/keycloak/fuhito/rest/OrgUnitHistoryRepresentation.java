package net.exoego.keycloak.fuhito.rest;

import java.util.List;

import net.exoego.keycloak.fuhito.service.OrgUnitHistory;

/**
 * Everything that is stored about an org unit over time.
 *
 * @param id the ID
 * @param code the code, or null
 * @param externalId the ID of the unit in the system it came from, or null
 * @param source the name of that system, or null
 * @param versions the details of each period, oldest first. They have no parent.
 * @param parents the parents in the default hierarchy, oldest first. A gap means the unit was a root.
 */
public record OrgUnitHistoryRepresentation(String id, String code, String externalId, String source,
        List<OrgUnitRepresentation> versions, List<ParentPeriod> parents) {

    /**
     * The parent of an org unit for a period.
     *
     * @param parentId the ID of the parent
     * @param validFrom the first date under the parent
     * @param validTo the first date after the period, or null for no end
     */
    public record ParentPeriod(String parentId, String validFrom, String validTo) {
    }

    static OrgUnitHistoryRepresentation of(OrgUnitHistory history) {
        var unit = history.unit();
        return new OrgUnitHistoryRepresentation(unit.id(), unit.code(), unit.externalId(), unit.source(),
                history.versions().stream().map(OrgUnitRepresentation::of).toList(),
                history.parents().stream()
                        .map(link -> new ParentPeriod(link.parentId(), link.period().from().toString(),
                                link.period().to() == null ? null : link.period().to().toString()))
                        .toList());
    }
}
