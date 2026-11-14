package net.exoego.keycloak.fuhito.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(
        name = "ImportDocument",
        description = "The data of one import. The document describes the state from the effective date. A new "
                + "thing starts on that date, and a change to an existing thing takes effect on that date.",
        requiredProperties = {"effectiveDate"})
public record ImportDocument(
        @Schema(description = "The date of the state.", format = "date")
        String effectiveDate,
        @Schema(description = "True to do all checks and write nothing.", defaultValue = "false")
        boolean dryRun,
        @Schema(
                description = "The name of the system that the rows come from. Rows can override it.",
                maxLength = 64,
                pattern = "^[A-Za-z0-9][A-Za-z0-9_.-]*$",
                defaultValue = "import")
        String source,
        List<RefItemRow> refItems,
        List<OrgUnitRow> orgUnits,
        List<EmploymentRow> employments,
        List<AssignmentRow> assignments) {

    @Schema(
            name = "ImportEmploymentRow",
            description = "An employment. It is matched by source and external ID.",
            requiredProperties = {"externalId"})
    public record EmploymentRow(
            @Schema(
                    description = "The name of the system that the row comes from. The default is the source of the "
                            + "document.",
                    maxLength = 64,
                    pattern = "^[A-Za-z0-9][A-Za-z0-9_.-]*$")
            String source,
            @Schema(description = "The ID of the employment in that system.")
            String externalId,
            @Schema(description = "The username of the Keycloak user. Either this or `userId` is required.")
            String username,
            @Schema(description = "The ID of the Keycloak user.")
            String userId,
            @Schema(
                    description = "The number that the legal entity gives to the person. It is used only when the "
                            + "employment is created.",
                    maxLength = 64,
                    pattern = "^[A-Za-z0-9][A-Za-z0-9_.-]*$")
            String staffNumber,
            @Schema(description = "The code of the employment type.")
            String employmentType,
            @Schema(description = "The code of the grade.")
            String grade,
            @Schema(ref = "EmploymentStatus", defaultValue = "ACTIVE")
            String status,
            @Schema(
                    description = "The share of full-time work in percent.",
                    minimum = "0",
                    exclusiveMinimum = true,
                    maximum = "100")
            BigDecimal workPercent,
            @Schema(description = "Free values of the terms.")
            Map<String, Object> attributes,
            @Schema(
                    description = "The first date of a new employment. The default is the effective date. An "
                            + "existing employment ignores it.",
                    format = "date")
            String validFrom,
            @Schema(description = "The first date on which the employment no longer holds.", format = "date")
            String validTo,
            @Schema(description = "The code of the end reason. It is used only with `validTo`.")
            String endReason) {
    }

    @Schema(
            name = "ImportAssignmentRow",
            description = "An assignment. It is matched by source and external ID.",
            requiredProperties = {"externalId", "employmentExternalId", "orgUnitExternalId"})
    public record AssignmentRow(
            @Schema(
                    description = "The name of the system that the row comes from. The default is the source of the "
                            + "document.",
                    maxLength = 64,
                    pattern = "^[A-Za-z0-9][A-Za-z0-9_.-]*$")
            String source,
            @Schema(description = "The ID of the assignment in that system.")
            String externalId,
            @Schema(description = "The external ID of the employment with the same source.")
            String employmentExternalId,
            @Schema(description = "The external ID of the org unit with the same source.")
            String orgUnitExternalId,
            @Schema(description = "The code of the job title.")
            String jobTitle,
            @Schema(description = "The code of the occupation.")
            String occupation,
            @Schema(description = "The code of the assignment kind.")
            String kind,
            @Schema(description = "True when the person leads the org unit.", defaultValue = "false")
            Boolean head,
            @Schema(description = "True when this is the main assignment of the employment.", defaultValue = "false")
            Boolean primary,
            @Schema(
                    description = "The share of the person's work in percent.",
                    minimum = "0",
                    exclusiveMinimum = true,
                    maximum = "100")
            BigDecimal allocationPercent,
            @Schema(description = "The position among the members of the org unit.", defaultValue = "0")
            Integer displayOrder,
            @Schema(description = "Free values that the realm defines.")
            Map<String, Object> attributes,
            @Schema(
                    description = "The first date of a new assignment. The default is the effective date. An "
                            + "existing assignment ignores it.",
                    format = "date")
            String validFrom,
            @Schema(
                    description = "The first date on which the assignment no longer holds. The default is the end "
                            + "of the employment.",
                    format = "date")
            String validTo) {
    }

    @Schema(
            name = "ImportRefItemRow",
            description = "A reference item. It is matched by kind and code.",
            requiredProperties = {"kind", "code", "name"})
    public record RefItemRow(
            @Schema(ref = "RefKind")
            String kind,
            @Schema(maxLength = 64, pattern = "^[A-Za-z0-9][A-Za-z0-9_.-]*$")
            String code,
            @Schema(description = "The default name.", maxLength = 255)
            String name,
            @Schema(description = "Names in other languages, by language tag.")
            Map<String, String> names,
            @Schema(description = "The position in lists.", defaultValue = "0")
            Integer displayOrder,
            @Schema(description = "Free values that the realm defines.")
            Map<String, Object> attributes,
            @Schema(description = "False to stop the use of the item for new data.", defaultValue = "true")
            Boolean active) {
    }

    @Schema(
            name = "ImportOrgUnitRow",
            description = "An org unit. It is matched by source and external ID.",
            requiredProperties = {"externalId", "name"})
    public record OrgUnitRow(
            @Schema(
                    description = "The name of the system that the row comes from. The default is the source of the "
                            + "document.",
                    maxLength = 64,
                    pattern = "^[A-Za-z0-9][A-Za-z0-9_.-]*$")
            String source,
            @Schema(description = "The ID of the unit in that system.")
            String externalId,
            @Schema(
                    description = "A code that is unique in the realm. It is used only when the unit is created.",
                    maxLength = 64,
                    pattern = "^[A-Za-z0-9][A-Za-z0-9_.-]*$")
            String code,
            @Schema(description = "The default name.", maxLength = 255)
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
            @Schema(description = "The external ID of the parent with the same source. A root has none.")
            String parentExternalId,
            @Schema(description = "The position among siblings.", defaultValue = "0")
            Integer displayOrder,
            @Schema(description = "Free values that the realm defines.")
            Map<String, Object> attributes,
            @Schema(
                    description = "The first date of a new unit. The default is the effective date. An existing "
                            + "unit ignores it.",
                    format = "date")
            String validFrom,
            @Schema(description = "The first date on which the unit no longer exists.", format = "date")
            String validTo) {
    }
}
