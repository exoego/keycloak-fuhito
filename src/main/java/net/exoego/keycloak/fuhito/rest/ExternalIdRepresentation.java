package net.exoego.keycloak.fuhito.rest;

import net.exoego.keycloak.fuhito.service.ExternalIdService;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * An ID of a resource in another system, in requests and responses.
 */
@Schema(
        name = "ExternalId",
        description = "The ID that one system has for one resource.",
        requiredProperties = {"resourceType", "resourceId", "source", "externalId"})
public record ExternalIdRepresentation(
        @Schema(enumeration = {"ORG_UNIT", "PERSON", "EMPLOYMENT", "ASSIGNMENT", "QUALIFICATION"})
        String resourceType,
        String resourceId,
        @Schema(description = "The name of the system.", maxLength = 64, pattern = "^[A-Za-z0-9][A-Za-z0-9_.-]*$")
        String source,
        @Schema(description = "The ID in that system.", maxLength = 255)
        String externalId,
        @Schema(
                description = "True when the system made the resource, as the `source` of the resource says.",
                readOnly = true)
        Boolean own) {

    static ExternalIdRepresentation of(ExternalIdService.View view) {
        return new ExternalIdRepresentation(view.kind().name(), view.resourceId(), view.source(), view.externalId(),
                view.own());
    }
}
