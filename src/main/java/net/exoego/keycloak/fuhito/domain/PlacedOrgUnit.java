package net.exoego.keycloak.fuhito.domain;

/**
 * An org unit with its details and its place in a hierarchy on one date.
 *
 * @param unit the identity of the unit
 * @param version the details on the date
 * @param parentId the ID of the parent on the date, or null for a root
 */
public record PlacedOrgUnit(OrgUnit unit, OrgUnitVersion version, String parentId) {
}
