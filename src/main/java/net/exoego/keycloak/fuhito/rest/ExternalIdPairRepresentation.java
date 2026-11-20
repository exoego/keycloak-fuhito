package net.exoego.keycloak.fuhito.rest;

import java.util.List;

import net.exoego.keycloak.fuhito.domain.ExternalId;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * One ID of a record in one system, in the responses of the record.
 */
@Schema(
        name = "ExternalIdPair",
        description = "The ID that one system has for a record.",
        requiredProperties = {"source", "externalId"})
public record ExternalIdPairRepresentation(
        @Schema(description = "The name of the system.")
        String source,
        @Schema(description = "The ID in that system.")
        String externalId) {

    // A write rejects an empty list, so a response that is sent back as a body must not have one.
    static List<ExternalIdPairRepresentation> of(List<ExternalId> ids) {
        if (ids.isEmpty()) {
            return null;
        }
        return ids.stream().map(id -> new ExternalIdPairRepresentation(id.source(), id.externalId())).toList();
    }
}
