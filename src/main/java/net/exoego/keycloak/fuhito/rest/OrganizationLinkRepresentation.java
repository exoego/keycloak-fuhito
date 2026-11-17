package net.exoego.keycloak.fuhito.rest;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(
        name = "OrganizationLink",
        description = "The link of an org unit to a Keycloak organization.",
        requiredProperties = {"version"})
public record OrganizationLinkRepresentation(
        @Schema(readOnly = true)
        String orgUnitId,
        @Schema(description = "The version of the org unit. A request sends the one that the caller read. A response "
                + "has the one after the write.")
        Long version,
        @Schema(
                description = "The ID of the Keycloak organization. A request to link needs it. A response has none "
                        + "after the link was removed.")
        String organizationId) implements Versioned {
}
