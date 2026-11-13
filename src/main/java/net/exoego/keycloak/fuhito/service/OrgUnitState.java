package net.exoego.keycloak.fuhito.service;

import net.exoego.keycloak.fuhito.domain.OrgUnit;
import net.exoego.keycloak.fuhito.domain.OrgUnitVersion;

/**
 * An org unit as it is on one date.
 *
 * @param unit the identity of the unit
 * @param version the details on the date
 * @param unitType the code of the unit type on the date, or null
 * @param parentId the ID of the parent in the default hierarchy on the date, or null for a root
 */
public record OrgUnitState(OrgUnit unit, OrgUnitVersion version, String unitType, String parentId) {
}
