package net.exoego.keycloak.fuhito.rest;

import net.exoego.keycloak.fuhito.domain.ChangeLogEntry;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(
        name = "ChangeLogEntry",
        description = "One successful write. It has no state of the resource. Read the resource again for its state.",
        requiredProperties = {"id", "sequence", "recordedAt", "actorUserId", "resourceType", "resourceId", "operation", "resourcePath"})
public record ChangeLogRepresentation(
        String id,
        @Schema(description = "The position of the entry in the change log of the realm, counted from 1.")
        long sequence,
        @Schema(description = "When the write happened, in UTC.", format = "date-time")
        String recordedAt,
        @Schema(description = "The ID of the user who did the write.")
        String actorUserId,
        @Schema(examples = {"FUHITO_ORG_UNIT"})
        String resourceType,
        String resourceId,
        @Schema(enumeration = {"CREATE", "UPDATE", "DELETE"})
        String operation,
        @Schema(description = "The path of the request below the realm.", examples = {"fuhito/org-units/123/end"})
        String resourcePath,
        @Schema(description = "The ID of the change order that the write belongs to.")
        String changeOrderId) {

    static ChangeLogRepresentation of(ChangeLogEntry entry) {
        return new ChangeLogRepresentation(entry.id(), entry.sequence(), entry.recordedAt().toString(), entry.actorUserId(),
                entry.resourceType(), entry.resourceId(), entry.operation(), entry.resourcePath(),
                entry.changeOrderId());
    }
}
