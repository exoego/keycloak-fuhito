package net.exoego.keycloak.fuhito.domain;

/**
 * The identity of a node in the organization tree. It stays the same when the name or the parent changes.
 *
 * @param id the ID
 * @param code a code that is unique in the realm, or null
 * @param externalId the ID of the unit in the system it came from, or null
 * @param source the name of that system, or null
 * @param keycloakOrganizationId the ID of the linked Keycloak organization, or null
 */
public record OrgUnit(String id, String code, String externalId, String source, String keycloakOrganizationId) {
}
