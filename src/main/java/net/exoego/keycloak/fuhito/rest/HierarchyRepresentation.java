package net.exoego.keycloak.fuhito.rest;

import net.exoego.keycloak.fuhito.domain.Hierarchy;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(name = "Hierarchy", description = "A named tree of org units.", requiredProperties = {"code", "name"})
public record HierarchyRepresentation(
        @Schema(readOnly = true)
        String id,
        @Schema(
                description = "The code. It is unique in the realm and never changes. Every realm has the "
                        + "hierarchy `default`.",
                maxLength = 64,
                pattern = "^[A-Za-z0-9][A-Za-z0-9_.-]*$")
        String code,
        @Schema(maxLength = 255)
        String name) {

    static HierarchyRepresentation of(Hierarchy hierarchy) {
        return new HierarchyRepresentation(hierarchy.id(), hierarchy.code(), hierarchy.name());
    }
}
