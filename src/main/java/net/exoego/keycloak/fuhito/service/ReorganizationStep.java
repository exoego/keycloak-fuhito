package net.exoego.keycloak.fuhito.service;

import java.util.List;
import java.util.Map;

import net.exoego.keycloak.fuhito.domain.OrgUnitName;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(name = "ReorganizationStep", description = "One operation of a reorganization.", requiredProperties = {"op"})
public record ReorganizationStep(
        @Schema(enumeration = {"create", "change", "move", "end", "succeed"})
        String op,
        @Schema(
                description = "A name that later steps of the same request can use for the unit that a create step "
                        + "makes.")
        String ref,
        @Schema(description = "The ID of the unit to change, move, end, or succeed.")
        String id,
        @Schema(description = "The `ref` of an earlier create step, in place of `id`.")
        String unitRef,
        @Schema(description = "The version of the unit of `id` that the caller read. A change, move, end, or "
                + "succeed step with `id` needs it. Two steps of one unit name the same version.")
        Long version,
        @Schema(description = "The ID of the parent for a create or a move.")
        String parentId,
        @Schema(description = "The `ref` of an earlier create step, in place of `parentId`.")
        String parentRef,
        @Schema(
                description = "The code of a new unit, or of a unit after `change`. A `change` that leaves it out "
                        + "clears it.",
                maxLength = 64,
                pattern = "^[A-Za-z0-9][A-Za-z0-9_.-]*$")
        String code,
        @Schema(description = "The language tag of the primary names, for a create or a change. It matches a key "
                + "of `names` in standard form.")
        String primaryLanguage,
        @Schema(description = "The names by language tag, for a create or a change. Two keys of one tag in standard "
                + "form, such as `ja` and `JA`, fail with `invalid.duplicate_language`.")
        Map<String, OrgUnitName> names,
        @Schema(description = "The code of the unit type.")
        String unitType,
        @Schema(description = "The code of the work location that the unit gives its members.")
        String workLocation,
        @Schema(description = "The code of the cost center that the unit gives its members.")
        String costCenter,
        @Schema(description = "The position among siblings.", defaultValue = "0")
        Integer displayOrder,
        @Schema(description = "Free values that the realm defines.")
        Map<String, Object> attributes,
        @Schema(description = "For a succeed step, the IDs of the units that follow the unit that ends.")
        List<String> successorIds,
        @Schema(description = "For a succeed step, the `ref` of earlier create steps, in place of `successorIds`.")
        List<String> successorRefs) {
}
