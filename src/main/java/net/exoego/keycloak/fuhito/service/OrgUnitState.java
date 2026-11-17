package net.exoego.keycloak.fuhito.service;

import net.exoego.keycloak.fuhito.domain.OrgUnit;
import net.exoego.keycloak.fuhito.domain.OrgUnitVersion;
import net.exoego.keycloak.fuhito.domain.RefItem;

/**
 * An org unit as it is on one date.
 *
 * @param unit the identity of the unit
 * @param version the details on the date
 * @param unitType the code of the unit type on the date, or null
 * @param parentId the ID of the parent in the hierarchy of the read on the date, or null for a root
 * @param legalEntity true when the unit type on the date marks the unit as a legal entity
 * @param lateral true when the unit type on the date marks the unit as a lateral unit outside the line organization
 * @param places the work location and the cost center of the unit and the ones that apply to it, or
 *        {@link LocationAndCost#NONE} when a read does not need them
 */
public record OrgUnitState(OrgUnit unit, OrgUnitVersion version, String unitType, String parentId,
        boolean legalEntity, boolean lateral, LocationAndCost places) {

    /**
     * Makes the state from the unit type of the date.
     *
     * @param unit the identity of the unit
     * @param version the details on the date
     * @param unitType the unit type on the date, or null
     * @param parentId the ID of the parent on the date, or null for a root
     * @return the state
     */
    public static OrgUnitState of(OrgUnit unit, OrgUnitVersion version, RefItem unitType, String parentId) {
        return new OrgUnitState(unit, version, unitType == null ? null : unitType.code(), parentId,
                unitType != null && unitType.legalEntity(), unitType != null && unitType.lateral(),
                LocationAndCost.NONE);
    }

    /**
     * Returns this state with the work location and the cost center.
     *
     * @param newPlaces the work location and the cost center
     * @return the new state
     */
    public OrgUnitState withPlaces(LocationAndCost newPlaces) {
        return new OrgUnitState(unit, version, unitType, parentId, legalEntity, lateral, newPlaces);
    }
}
