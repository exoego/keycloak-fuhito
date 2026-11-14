package net.exoego.keycloak.fuhito.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The data of one import. The values are not checked yet.
 * The document describes the state from the effective date.
 * A new thing starts on that date, and a change to an existing thing takes effect on that date.
 *
 * @param effectiveDate the date of the state, as {@code YYYY-MM-DD}
 * @param dryRun true to do all checks and write nothing
 * @param source the name of the system that the rows come from. Rows can override it. The default is "import".
 * @param refItems the reference items, or null
 * @param orgUnits the org units, or null
 * @param employments the employments, or null
 * @param assignments the assignments, or null
 */
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

    /**
     * An employment. It is matched by source and external ID.
     *
     * @param source the name of the system that the row comes from, or null for the source of the document
     * @param externalId the ID of the employment in that system
     * @param username the username of the Keycloak user. Either this or {@code userId} is required.
     * @param userId the ID of the Keycloak user
     * @param staffNumber the number that the legal entity gives to the person, or null. It is used only when
     *        the employment is created.
     * @param employmentType the code of the employment type, or null
     * @param grade the code of the grade, or null
     * @param status the status, or null for {@code ACTIVE}
     * @param workPercent the share of full-time work in percent, or null
     * @param attributes free values of the terms, or null
     * @param validFrom the first date of a new employment, or null for the effective date. An existing
     *        employment ignores it.
     * @param validTo the first date on which the employment no longer holds, or null for no end
     * @param endReason the code of the end reason, or null. It is used only with {@code validTo}.
     */
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

    /**
     * An assignment. It is matched by source and external ID.
     *
     * @param source the name of the system that the row comes from, or null for the source of the document
     * @param externalId the ID of the assignment in that system
     * @param employmentExternalId the external ID of the employment with the same source
     * @param orgUnitExternalId the external ID of the org unit with the same source
     * @param jobTitle the code of the job title, or null
     * @param occupation the code of the occupation, or null
     * @param kind the code of the assignment kind, or null
     * @param head true when the person leads the org unit, or null for false
     * @param primary true when this is the main assignment of the employment, or null for false
     * @param allocationPercent the share of the person's work in percent, or null
     * @param displayOrder the position among the members of the org unit, or null for 0
     * @param attributes free values, or null
     * @param validFrom the first date of a new assignment, or null for the effective date. An existing
     *        assignment ignores it.
     * @param validTo the first date on which the assignment no longer holds, or null for the end of the
     *        employment
     */
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

    /**
     * A reference item. It is matched by kind and code.
     *
     * @param kind the kind
     * @param code the code
     * @param name the default name
     * @param names the names in other languages, or null
     * @param displayOrder the position in lists, or null for 0
     * @param attributes free values, or null
     * @param active false to stop the use of the item for new data, or null for true
     */
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

    /**
     * An org unit. It is matched by source and external ID.
     *
     * @param source the name of the system that the row comes from, or null for the source of the document
     * @param externalId the ID of the unit in that system
     * @param code a code that is unique in the realm, or null. It is used only when the unit is created.
     * @param name the default name
     * @param names the names in other languages, or null
     * @param shortName the default short name, or null
     * @param shortNames the short names in other languages, or null
     * @param phoneticName the reading of the name for sorting, or null
     * @param unitType the code of the unit type, or null
     * @param parentExternalId the external ID of the parent with the same source, or null for a root
     * @param displayOrder the position among siblings, or null for 0
     * @param attributes free values, or null
     * @param validFrom the first date of a new unit, or null for the effective date. An existing unit ignores it.
     * @param validTo the first date on which the unit no longer exists, or null for no end
     */
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
