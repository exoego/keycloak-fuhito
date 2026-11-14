package net.exoego.keycloak.fuhito.rest;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The link of an org unit to a Keycloak organization.
 *
 * @param orgUnitId the ID of the org unit. Requests ignore it.
 * @param organizationId the ID of the Keycloak organization. A response has none after the link was removed.
 */
@Schema(name = "OrganizationLink", description = "The link of an org unit to a Keycloak organization.")
public record OrganizationLinkRepresentation(
        @Schema(readOnly = true)
        String orgUnitId,
        @Schema(
                description = "The ID of the Keycloak organization. A request to link needs it. A response has none "
                        + "after the link was removed.")
        String organizationId) {
}
