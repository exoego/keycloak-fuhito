package net.exoego.keycloak.fuhito.service;

import java.util.Map;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * One operation of a reorganization. The values are not checked yet.
 *
 * @param op {@code create}, {@code change}, {@code move}, or {@code end}
 * @param ref a name that later steps of the same request can use for the unit that a create step makes, or null
 * @param id the ID of the unit to change, move, or end, or null
 * @param unitRef the {@code ref} of an earlier create step, in place of {@code id}, or null
 * @param parentId the ID of the parent for a create or a move, or null
 * @param parentRef the {@code ref} of an earlier create step, in place of {@code parentId}, or null
 * @param code the code of a new unit, or null
 * @param name the default name, for a create or a change
 * @param names the names in other languages, or null
 * @param shortName the default short name, or null
 * @param shortNames the short names in other languages, or null
 * @param phoneticName the reading of the name for sorting, or null
 * @param unitType the code of the unit type, or null
 * @param displayOrder the position among siblings, or null for 0
 * @param attributes free values, or null
 */
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
