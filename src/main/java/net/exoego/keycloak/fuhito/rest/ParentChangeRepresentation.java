package net.exoego.keycloak.fuhito.rest;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * A request to give an org unit a new parent.
 *
 * @param parentId the ID of the new parent, or null to make the unit a root
 * @param validFrom the first date under the new parent, as {@code YYYY-MM-DD}
 */
@Schema(name = "ParentChange", requiredProperties = {"validFrom"})
public record ParentChangeRepresentation(
        @Schema(description = "The ID of the new parent. Leave it out to make the unit a root.")
        String parentId,
        @Schema(description = "The first date under the new parent.", format = "date")
        String validFrom) {
}
