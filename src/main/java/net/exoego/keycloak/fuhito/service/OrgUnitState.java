package net.exoego.keycloak.fuhito.service;

import net.exoego.keycloak.fuhito.domain.Hierarchy;
import net.exoego.keycloak.fuhito.domain.OrgUnit;
import net.exoego.keycloak.fuhito.domain.OrgUnitVersion;
import net.exoego.keycloak.fuhito.domain.RefItem;

/**
 * An org unit as it is on one date.
 *
 * @param unit the identity of the unit
 * @param version the details on the date
 * @param unitType the code of the unit type on the date, or null
 * @param hierarchy the code of the hierarchy of the read, or null when the read has no parent, as a history has none
 * @param parentId the ID of the parent in the hierarchy of the read on the date, or null for a root
 * @param legalEntity true when the unit type on the date marks the unit as a legal entity
 * @param lateral true when the unit type on the date marks the unit as a lateral unit outside the line organization
 * @param rollup true when the unit type on the date marks the unit as a rollup unit, which is not in the default
 *        hierarchy
 * @param places the work location and the cost center of the unit and the ones that apply to it, or
 *        {@link LocationAndCost#NONE} when a read does not need them
 */
public record OrgUnitState(OrgUnit unit, OrgUnitVersion version, String unitType, String hierarchy, String parentId,
        boolean legalEntity, boolean lateral, boolean rollup, LocationAndCost places) {

    /**
     * Makes the state from the unit type of the date, with its parent in the default hierarchy.
     *
     * @param unit the identity of the unit
     * @param version the details on the date
     * @param unitType the unit type on the date, or null
     * @param parentId the ID of the parent on the date, or null for a root
     * @return the state
     */
    public static OrgUnitState of(OrgUnit unit, OrgUnitVersion version, RefItem unitType, String parentId) {
        return new OrgUnitState(unit, version, unitType == null ? null : unitType.code(), Hierarchy.DEFAULT_CODE,
                parentId, unitType != null && unitType.legalEntity(), unitType != null && unitType.lateral(),
                unitType != null && unitType.rollup(), LocationAndCost.NONE);
    }

    /**
     * Returns this state with the work location and the cost center.
     *
     * @param newPlaces the work location and the cost center
     * @return the new state
     */
    public OrgUnitState withPlaces(LocationAndCost newPlaces) {
        return new OrgUnitState(unit, version, unitType, hierarchy, parentId, legalEntity, lateral, rollup,
                newPlaces);
    }

    /**
     * Returns this state with the code of the hierarchy that its parent was read in.
     *
     * @param code the code of the hierarchy, or null when the state has no parent
     * @return the new state
     */
    public OrgUnitState withHierarchy(String code) {
        return new OrgUnitState(unit, version, unitType, code, parentId, legalEntity, lateral, rollup, places);
    }
}
