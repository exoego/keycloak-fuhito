package net.exoego.keycloak.fuhito.rest;

import java.util.List;

import net.exoego.keycloak.fuhito.domain.Hierarchy;
import net.exoego.keycloak.fuhito.service.ExternalIdLists;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(name = "Hierarchy", description = "A named tree of org units.", requiredProperties = {"code", "name"})
public record HierarchyRepresentation(
        @Schema(readOnly = true)
        String id,
        @Schema(description = "The version of the hierarchy. A new hierarchy has 1. Each write counts it up by one, "
                + "also a change of an ID of another system. A write of a stored hierarchy sends the version that the "
                + "caller read. A create needs none.")
        Long version,
        @Schema(
                description = "The code. It is unique in the realm and never changes. Every realm has the "
                        + "hierarchy `default`.",
                maxLength = 64,
                pattern = "^[A-Za-z0-9][A-Za-z0-9_.-]*$")
        String code,
        @Schema(maxLength = 255)
        String name,
        @Schema(
                description = "The ID of the hierarchy in the system that `source` names. Only a create reads it. "
                        + "A read gives every ID in `externalIds`.",
                maxLength = 255,
                writeOnly = true)
        String externalId,
        @Schema(
                description = "The name of the system of `externalId`. Only a create reads it.",
                maxLength = 64,
                pattern = "^[A-Za-z0-9][A-Za-z0-9_.-]*$",
                writeOnly = true)
        String source,
        @Schema(description = "Every ID that a system has for the hierarchy, by system.", readOnly = true)
        List<ExternalIdPairRepresentation> externalIds) implements Versioned {

    static HierarchyRepresentation of(Hierarchy hierarchy, ExternalIdLists ids) {
        return new HierarchyRepresentation(hierarchy.id(), hierarchy.version(), hierarchy.code(), hierarchy.name(),
                null, null, ExternalIdPairRepresentation.of(ids.of(hierarchy.id(), null)));
    }
}
