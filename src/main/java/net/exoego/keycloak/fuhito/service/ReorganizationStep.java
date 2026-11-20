package net.exoego.keycloak.fuhito.service;

import java.util.List;
import java.util.Map;

import net.exoego.keycloak.fuhito.domain.OrgUnitName;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(
        name = "ReorganizationStep",
        description = "One operation of a reorganization. Each operation reads only some fields. A step that gives "
                + "a field that its operation does not read fails with 400 and `reorganization.field_unused`, which "
                + "names the field in `field`.",
        requiredProperties = {"op"})
public record ReorganizationStep(
        @Schema(enumeration = {"create", "change", "move", "end", "succeed"})
        String op,
        @Schema(
                description = "For a create step, a name that later steps of the same request can use for the unit "
                        + "that it makes.")
        String ref,
        @Schema(description = "The ID of the unit to change, move, end, or succeed. A step gives this or `unitRef`. A "
                + "step with both fails with 400 and `invalid.twice` before any step runs.")
        String id,
        @Schema(description = "The `ref` of an earlier create step, in place of `id`, not with it.")
        String unitRef,
        @Schema(description = "The version of the unit of `id` that the caller read. A change, move, end, or "
                + "succeed step with `id` needs it. Two steps of one unit name the same version. A create step and "
                + "a step with no `id` have no version to give.")
        Long version,
        @Schema(description = "The ID of the parent for a create or a move. A step gives this or `parentRef`. A step "
                + "with both fails with 400 and `invalid.twice` before any step runs.")
        String parentId,
        @Schema(description = "The `ref` of an earlier create step, in place of `parentId`, not with it.")
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
        @Schema(description = "The code of the unit type, for a create or a change.")
        String unitType,
        @Schema(description = "The code of the work location that the unit gives its members, for a create or a "
                + "change.")
        String workLocation,
        @Schema(description = "The code of the cost center that the unit gives its members, for a create or a "
                + "change.")
        String costCenter,
        @Schema(description = "The position among siblings, for a create or a change.", defaultValue = "0")
        Integer displayOrder,
        @Schema(description = "Free values that the realm defines, for a create or a change.")
        Map<String, Object> attributes,
        @Schema(description = "For a succeed step, the IDs of the units that follow the unit that ends.")
        List<String> successorIds,
        @Schema(description = "For a succeed step, the `ref` of earlier create steps. A step can give it with "
                + "`successorIds`, and the units of both lists follow.")
        List<String> successorRefs) {
}
