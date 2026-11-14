package net.exoego.keycloak.fuhito.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.exoego.keycloak.fuhito.domain.ChangeLogEntry;
import net.exoego.keycloak.fuhito.domain.Unchecked;

/**
 * One successful write.
 *
 * @param id the ID of the entry
 * @param recordedAt when the write happened, in UTC
 * @param actorUserId the ID of the user who did the write
 * @param resourceType the type of the changed resource, for example {@code FUHITO_ORG_UNIT}
 * @param resourceId the ID of the changed resource
 * @param operation {@code CREATE}, {@code UPDATE}, or {@code DELETE}
 * @param resourcePath the path of the request below the realm
 * @param changeOrderId the ID of the change order that the write belongs to, or null
 * @param representation the state of the resource after the write, or null after a delete
 */
public record ChangeLogRepresentation(String id, String recordedAt, String actorUserId, String resourceType,
        String resourceId, String operation, String resourcePath, String changeOrderId, JsonNode representation) {

    private static final ObjectMapper JSON = new ObjectMapper();

    static ChangeLogRepresentation of(ChangeLogEntry entry) {
        return new ChangeLogRepresentation(entry.id(), entry.recordedAt().toString(), entry.actorUserId(),
                entry.resourceType(), entry.resourceId(), entry.operation(), entry.resourcePath(),
                entry.changeOrderId(), parse(entry.representation()));
    }

    private static JsonNode parse(String json) {
        return json == null ? null : Unchecked.io(() -> JSON.readTree(json));
    }
}
