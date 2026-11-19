package net.exoego.keycloak.fuhito.domain;

import java.util.Map;

/**
 * The details of an org unit for a period.
 *
 * @param id the ID
 * @param orgUnitId the ID of the org unit
 * @param period the dates when these details are true
 * @param code a code that no other org unit has on the same date, or null
 * @param names the names in each language
 * @param unitTypeId the ID of the unit type, or null
 * @param workLocationId the ID of the work location that the unit gives its members, or null
 * @param costCenterId the ID of the cost center that the unit gives its members, or null
 * @param displayOrder the position among siblings, lowest first
 * @param attributes free values that the realm defines
 */
public record OrgUnitVersion(String id, String orgUnitId, ValidPeriod period, String code,
        Localized<OrgUnitName> names, String unitTypeId, String workLocationId, String costCenterId,
        int displayOrder, Map<String, Object> attributes) {

    /**
     * Returns these details for another period.
     *
     * @param newPeriod the period
     * @return the details with the period
     */
    public OrgUnitVersion withPeriod(ValidPeriod newPeriod) {
        return new OrgUnitVersion(id, orgUnitId, newPeriod, code, names, unitTypeId, workLocationId, costCenterId,
                displayOrder, attributes);
    }
}
