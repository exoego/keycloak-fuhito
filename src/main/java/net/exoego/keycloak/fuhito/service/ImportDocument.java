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
        List<AssignmentRow> assignments,
        @Schema(description = "The people. They come before the employments, which can name them.")
        List<PersonRow> people) {

    /**
     * Makes a document with no list of people.
     *
     * @param effectiveDate the date of the state, as {@code YYYY-MM-DD}
     * @param dryRun true to do all checks and write nothing
     * @param source the name of the system that the rows come from, or null
     * @param refItems the reference items, or null
     * @param orgUnits the org units, or null
     * @param employments the employments, or null
     * @param assignments the assignments, or null
     */
    public ImportDocument(String effectiveDate, boolean dryRun, String source, List<RefItemRow> refItems,
            List<OrgUnitRow> orgUnits, List<EmploymentRow> employments, List<AssignmentRow> assignments) {
        this(effectiveDate, dryRun, source, refItems, orgUnits, employments, assignments, null);
    }

    @Schema(
            name = "ImportPersonRow",
            description = "A person, also one with no Keycloak user. It is matched by source and external ID, or by "
                    + "`id`. A row needs one of them.")
    public record PersonRow(
            @Schema(
                    description = "The name of the system that the row comes from. The default is the source of the "
                            + "document.",
                    maxLength = 64,
                    pattern = "^[A-Za-z0-9][A-Za-z0-9_.-]*$")
            String source,
            @Schema(description = "The ID of the person in that system.")
            String externalId,
            @Schema(description = "The username of a Keycloak user to link. A row that names no user keeps the link "
                    + "that is stored.")
            String username,
            @Schema(description = "The ID of a Keycloak user to link.")
            String userId,
            @Schema(description = "The default name. A person with no user needs it.", maxLength = 255)
            String name,
            @Schema(description = "Names in other languages, by language tag.")
            Map<String, String> names,
            @Schema(description = "The reading of the name for sorting.", maxLength = 255)
            String phoneticName,
            @Schema(description = "Free values of the person.")
            Map<String, Object> attributes,
            @Schema(description = "The ID of a stored person, for a row of a person with no external ID. A CSV export "
                    + "has it in every row. A new person has none.")
            String id) {
    }

    @Schema(
            name = "ImportEmploymentRow",
            description = "An employment. It is matched by source and external ID, or by `id`. A row needs one of "
                    + "them.")
    public record EmploymentRow(
            @Schema(
                    description = "The name of the system that the row comes from. The default is the source of the "
                            + "document.",
                    maxLength = 64,
                    pattern = "^[A-Za-z0-9][A-Za-z0-9_.-]*$")
            String source,
            @Schema(description = "The ID of the employment in that system.")
            String externalId,
            @Schema(description = "The username of the Keycloak user. A row needs this, `userId`, or "
                    + "`personExternalId`, except for a stored employment.")
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
            @Schema(description = "The code of the leave reason. Only the status `ON_LEAVE` may have one.")
            String leaveReason,
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
            String endReason,
            @Schema(description = "The external ID of the org unit that employs the person. It is a legal entity. "
                    + "Leave it out when the realm has exactly one. A stored employment keeps its employer, so "
                    + "the value must name that one.")
            String employerExternalId,
            @Schema(description = "The external ID of the person with the same source, for a person from the list "
                    + "of people. It names a person with no Keycloak user too.")
            String personExternalId,
            @Schema(description = "The ID of the person, for a person with no external ID of the same source. Give "
                    + "this or `personExternalId`, not both.")
            String personId,
            @Schema(description = "The ID of the employer, for an employer with no external ID of the same source. "
                    + "Give this or `employerExternalId`, not both.")
            String employerId,
            @Schema(description = "The ID of a stored employment, for a row of an employment with no external ID. A "
                    + "CSV export has it in every row. A new employment has none.")
            String id) {

        /**
         * Makes a row that names no employer.
         *
         * @param source the name of the system that the row comes from, or null
         * @param externalId the ID of the employment in that system
         * @param username the username of the user, or null
         * @param userId the ID of the user, or null
         * @param staffNumber the staff number, or null
         * @param employmentType the code of the employment type, or null
         * @param grade the code of the grade, or null
         * @param status the status, or null
         * @param workPercent the share of full-time work in percent, or null
         * @param attributes free values, or null
         * @param validFrom the first date of a new employment, or null
         * @param validTo the first date on which the employment no longer holds, or null
         * @param endReason the code of the end reason, or null
         */
        public EmploymentRow(String source, String externalId, String username, String userId, String staffNumber,
                String employmentType, String grade, String status, BigDecimal workPercent,
                Map<String, Object> attributes, String validFrom, String validTo, String endReason) {
            this(source, externalId, username, userId, staffNumber, employmentType, grade, status, null,
                    workPercent, attributes, validFrom, validTo, endReason, null, null, null, null, null);
        }
    }

    @Schema(
            name = "ImportAssignmentRow",
            description = "An assignment. It is matched by source and external ID, or by `id`. A row needs one of "
                    + "them. A new assignment names its employment and its org unit.")
    public record AssignmentRow(
            @Schema(
                    description = "The name of the system that the row comes from. The default is the source of the "
                            + "document.",
                    maxLength = 64,
                    pattern = "^[A-Za-z0-9][A-Za-z0-9_.-]*$")
            String source,
            @Schema(description = "The ID of the assignment in that system.")
            String externalId,
            @Schema(description = "The external ID of the employment with the same source. A row of a stored "
                    + "assignment that names no employment keeps the one that is stored.")
            String employmentExternalId,
            @Schema(description = "The external ID of the org unit with the same source. A row of a stored "
                    + "assignment that names no org unit keeps the one that is stored.")
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
            String validTo,
            @Schema(description = "The ID of the employment, for an employment with no external ID of the same "
                    + "source. Give this or `employmentExternalId`, not both.")
            String employmentId,
            @Schema(description = "The ID of the org unit, for an org unit with no external ID of the same source. "
                    + "Give this or `orgUnitExternalId`, not both.")
            String orgUnitId,
            @Schema(description = "The ID of a stored assignment, for a row of an assignment with no external ID. A "
                    + "CSV export has it in every row. A new assignment has none.")
            String id) {

        /**
         * Makes a row that names the employment and the org unit by external ID.
         *
         * @param source the name of the system that the row comes from, or null
         * @param externalId the ID of the assignment in that system
         * @param employmentExternalId the external ID of the employment
         * @param orgUnitExternalId the external ID of the org unit
         * @param jobTitle the code of the job title, or null
         * @param occupation the code of the occupation, or null
         * @param kind the code of the assignment kind, or null
         * @param head true when the person leads the org unit, or null
         * @param primary true when this is the main assignment of the employment, or null
         * @param allocationPercent the share of the person's work in percent, or null
         * @param displayOrder the position among the members of the org unit, or null
         * @param attributes free values, or null
         * @param validFrom the first date of a new assignment, or null
         * @param validTo the first date on which the assignment no longer holds, or null
         */
        public AssignmentRow(String source, String externalId, String employmentExternalId,
                String orgUnitExternalId, String jobTitle, String occupation, String kind, Boolean head,
                Boolean primary, BigDecimal allocationPercent, Integer displayOrder, Map<String, Object> attributes,
                String validFrom, String validTo) {
            this(source, externalId, employmentExternalId, orgUnitExternalId, jobTitle, occupation, kind, head,
                    primary, allocationPercent, displayOrder, attributes, validFrom, validTo, null, null, null);
        }
    }

    @Schema(
            name = "ImportRefItemRow",
            description = "A reference item. It is matched by kind and code.",
            requiredProperties = {"kind", "code", "name"})
    public record RefItemRow(
            @Schema(description = "A fixed kind such as `UNIT_TYPE`, or the code of a kind that the realm added.")
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
            Boolean active,
            @Schema(description = "True for a unit type whose org units are legal entities. A row that leaves it "
                    + "out keeps the mark of a stored item.")
            Boolean legalEntity,
            @Schema(description = "True for an assignment kind whose heads stand in for an absent head. A row "
                    + "that leaves it out keeps the mark of a stored item.")
            Boolean actingHead,
            @Schema(description = "True for a unit type whose org units are groups outside the line organization. A "
                    + "row that leaves it out keeps the mark of a stored item.")
            Boolean group) {

        /**
         * Makes a row that does not speak about the marks.
         *
         * @param kind the kind
         * @param code the code
         * @param name the default name
         * @param names the names in other languages, or null
         * @param displayOrder the position in lists, or null
         * @param attributes free values, or null
         * @param active false to stop the use of the item for new data, or null
         */
        public RefItemRow(String kind, String code, String name, Map<String, String> names, Integer displayOrder,
                Map<String, Object> attributes, Boolean active) {
            this(kind, code, name, names, displayOrder, attributes, active, null, null, null);
        }
    }

    @Schema(
            name = "ImportOrgUnitRow",
            description = "An org unit. It is matched by source and external ID, or by `id`. A row needs one of them.",
            requiredProperties = {"name"})
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
                    description = "A code that no other org unit has on the same date. A row for a stored unit "
                            + "changes it from the effective date.",
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
            @Schema(description = "The ID of the parent, for a parent that has no external ID with the same source. "
                    + "Give this or `parentExternalId`, not both.")
            String parentId,
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
            String validTo,
            @Schema(description = "The ID of a stored unit, for a row of a unit with no external ID. A CSV export has "
                    + "it in every row. A new unit has none.")
            String id) {
    }
}
