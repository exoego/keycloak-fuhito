package net.exoego.keycloak.fuhito.service;

import java.util.Map;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(name = "ReorganizationStep", description = "One operation of a reorganization.", requiredProperties = {"op"})
public record ReorganizationStep(
        @Schema(enumeration = {"create", "change", "move", "end"})
        String op,
        @Schema(
                description = "A name that later steps of the same request can use for the unit that a create step "
                        + "makes.")
        String ref,
        @Schema(description = "The ID of the unit to change, move, or end.")
        String id,
        @Schema(description = "The `ref` of an earlier create step, in place of `id`.")
        String unitRef,
        @Schema(description = "The ID of the parent for a create or a move.")
        String parentId,
        @Schema(description = "The `ref` of an earlier create step, in place of `parentId`.")
        String parentRef,
        @Schema(description = "The code of a new unit.", maxLength = 64, pattern = "^[A-Za-z0-9][A-Za-z0-9_.-]*$")
        String code,
        @Schema(description = "The default name, for a create or a change.", maxLength = 255)
        String name,
        @Schema(description = "Names in other languages, by language tag.")
        Map<String, String> names,
        @Schema(description = "The default short name.", maxLength = 255)
        String shortName,
        @Schema(description = "The short names in other languages, by language tag.")
        Map<String, String> shortNames,
        @Schema(description = "The reading of the name for sorting.", maxLength = 255)
        String phoneticName,
        @Schema(description = "The code of the unit type.")
        String unitType,
        @Schema(description = "The position among siblings.", defaultValue = "0")
        Integer displayOrder,
        @Schema(description = "Free values that the realm defines.")
        Map<String, Object> attributes) {
}
