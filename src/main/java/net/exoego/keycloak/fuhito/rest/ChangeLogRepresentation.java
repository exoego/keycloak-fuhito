package net.exoego.keycloak.fuhito.rest;

import net.exoego.keycloak.fuhito.domain.ChangeLogEntry;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(
        name = "ChangeLogEntry",
        description = "One successful write, or one propagated change. It has no state of the resource. Read the "
                + "resource again for its state.",
        requiredProperties = {"id", "sequence", "recordedAt", "kind", "resourceType", "resourceId", "operation",
            "resourcePath"})
public record ChangeLogRepresentation(
        String id,
        @Schema(description = "The position of the entry in the change log of the realm, counted from 1.")
        long sequence,
        @Schema(description = "When the write happened, in UTC. For a propagated change, when its cause happened.",
                format = "date-time")
        String recordedAt,
        @Schema(
                description = "`WRITE` for a write to the resource. `PROPAGATED` for a change of the read of the "
                        + "resource that nobody wrote, such as a value that the read takes from another resource. "
                        + "Its version counts up too. A propagated change has no user and the operation `UPDATE`.",
                enumeration = {"WRITE", "PROPAGATED"})
        String kind,
        @Schema(description = "The ID of the user who did the write. It is absent when no user did it, as for a "
                + "propagated change or for the end of the employments of a user that Keycloak removed.")
        String actorUserId,
        @Schema(examples = {"FUHITO_ORG_UNIT"})
        String resourceType,
        String resourceId,
        @Schema(enumeration = {"CREATE", "UPDATE", "DELETE"})
        String operation,
        @Schema(
                description = "The path of the request below the realm. For a propagated change, the path of the "
                        + "resource.",
                examples = {"fuhito/org-units/123/end"})
        String resourcePath,
        @Schema(description = "The ID of the change order that the write belongs to. A propagated change has the "
                + "change order of the write that caused it.")
        String changeOrderId,
        @Schema(
                description = "What caused a propagated change. `WRITE` is a write to the API, whose entry "
                        + "`causeSequence` names. `KEYCLOAK_ORGANIZATION_REMOVED` is the removal of the linked "
                        + "organization in Keycloak. A write has no cause.",
                enumeration = {"WRITE", "KEYCLOAK_ORGANIZATION_REMOVED"})
        String cause,
        @Schema(description = "The sequence number of the entry of the write that caused a propagated change. Only a "
                + "propagated change with the cause `WRITE` has it.")
        Long causeSequence) {

    static ChangeLogRepresentation of(ChangeLogEntry entry) {
        return new ChangeLogRepresentation(entry.id(), entry.sequence(), entry.recordedAt().toString(),
                entry.kind().name(), entry.actorUserId(), entry.resourceType().name(), entry.resourceId(),
                entry.operation(), entry.resourcePath(), entry.changeOrderId(),
                entry.cause() == null ? null : entry.cause().name(), entry.causeSequence());
    }
}
