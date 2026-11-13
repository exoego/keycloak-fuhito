package net.exoego.keycloak.fuhito.rest;

/**
 * The link of an org unit to a Keycloak organization.
 *
 * @param orgUnitId the ID of the org unit. Requests ignore it.
 * @param organizationId the ID of the Keycloak organization. A response has none after the link was removed.
 */
public record OrganizationLinkRepresentation(String orgUnitId, String organizationId) {
}
