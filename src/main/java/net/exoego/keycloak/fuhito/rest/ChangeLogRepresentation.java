package net.exoego.keycloak.fuhito.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.exoego.keycloak.fuhito.domain.ChangeLogEntry;
import net.exoego.keycloak.fuhito.domain.Unchecked;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(
        name = "ChangeLogEntry",
        description = "One successful write.",
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
        String changeOrderId,
        @Schema(
                description = "The state of the resource after the write. It is missing after a delete.",
                type = SchemaType.OBJECT,
                additionalProperties = Schema.True.class)
        JsonNode representation) {

    private static final ObjectMapper JSON = new ObjectMapper();

    static ChangeLogRepresentation of(ChangeLogEntry entry) {
        return new ChangeLogRepresentation(entry.id(), entry.sequence(), entry.recordedAt().toString(), entry.actorUserId(),
                entry.resourceType(), entry.resourceId(), entry.operation(), entry.resourcePath(),
                entry.changeOrderId(), parse(entry.representation()));
    }

    private static JsonNode parse(String json) {
        return json == null ? null : Unchecked.io(() -> JSON.readTree(json));
    }
}
