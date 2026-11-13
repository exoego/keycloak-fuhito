package net.exoego.keycloak.fuhito.domain;

import java.math.BigDecimal;
import java.util.Map;

/**
 * The placement of an employment in an org unit, as it is for a period.
 * An assignment keeps its ID when its details change. Each change adds a row with the same assignment ID.
 * A move to another org unit is not a change. It ends one assignment and adds another.
 *
 * @param rowId the ID of this row
 * @param assignmentId the ID of the assignment, shared by all its rows
 * @param employmentId the ID of the employment
 * @param orgUnitId the ID of the org unit
 * @param period the dates when these details are true
 * @param jobTitleId the ID of the job title, or null
 * @param occupationId the ID of the occupation, or null
 * @param kindId the ID of the assignment kind, or null
 * @param head true when the person leads the org unit
 * @param primary true when this is the main assignment of the employment
 * @param allocationPercent the share of the person's work that goes to this assignment in percent, or null
 * @param displayOrder the position among the members of the org unit, lowest first
 * @param attributes free values that the realm defines
 * @param externalId the ID of the assignment in the system it came from, or null
 * @param source the name of that system, or null
 */
public record Assignment(String rowId, String assignmentId, String employmentId, String orgUnitId,
        ValidPeriod period, String jobTitleId, String occupationId, String kindId, boolean head, boolean primary,
        BigDecimal allocationPercent, int displayOrder, Map<String, Object> attributes, String externalId,
        String source) {

    /**
     * Returns this row with another period.
     *
     * @param newPeriod the new period
     * @return the row with the new period
     */
    public Assignment withPeriod(ValidPeriod newPeriod) {
        return new Assignment(rowId, assignmentId, employmentId, orgUnitId, newPeriod, jobTitleId, occupationId,
                kindId, head, primary, allocationPercent, displayOrder, attributes, externalId, source);
    }
}
