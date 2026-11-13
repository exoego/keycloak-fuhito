package net.exoego.keycloak.fuhito.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

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
public record ImportDocument(String effectiveDate, boolean dryRun, String source, List<RefItemRow> refItems,
        List<OrgUnitRow> orgUnits, List<EmploymentRow> employments, List<AssignmentRow> assignments) {

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
    public record EmploymentRow(String source, String externalId, String username, String userId,
            String staffNumber, String employmentType, String grade, String status, BigDecimal workPercent,
            Map<String, Object> attributes, String validFrom, String validTo, String endReason) {
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
    public record AssignmentRow(String source, String externalId, String employmentExternalId,
            String orgUnitExternalId, String jobTitle, String occupation, String kind, Boolean head,
            Boolean primary, BigDecimal allocationPercent, Integer displayOrder, Map<String, Object> attributes,
            String validFrom, String validTo) {
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
    public record RefItemRow(String kind, String code, String name, Map<String, String> names,
            Integer displayOrder, Map<String, Object> attributes, Boolean active) {
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
    public record OrgUnitRow(String source, String externalId, String code, String name, Map<String, String> names,
            String shortName, Map<String, String> shortNames, String phoneticName, String unitType,
            String parentExternalId, Integer displayOrder, Map<String, Object> attributes, String validFrom,
            String validTo) {
    }
}
