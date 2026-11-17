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

    static List<ExternalIdPairRepresentation> of(List<ExternalId> ids) {
        return ids.stream().map(id -> new ExternalIdPairRepresentation(id.source(), id.externalId())).toList();
    }
}
