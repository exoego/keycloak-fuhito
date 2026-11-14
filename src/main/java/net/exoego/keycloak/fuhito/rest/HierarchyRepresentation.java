package net.exoego.keycloak.fuhito.rest;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * A named tree of org units.
 *
 * @param id the ID
 * @param code the code, which is unique in the realm
 * @param name the name
 */
@Schema(name = "Hierarchy", description = "A named tree of org units.", requiredProperties = {"id", "code", "name"})
public record HierarchyRepresentation(
        String id,
        @Schema(description = "The code")
        String code,
        String name) {
}
