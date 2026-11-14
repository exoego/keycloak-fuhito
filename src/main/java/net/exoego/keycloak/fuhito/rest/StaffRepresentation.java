package net.exoego.keycloak.fuhito.rest;

import java.math.BigDecimal;

import net.exoego.keycloak.fuhito.service.AssignmentValues;
import net.exoego.keycloak.fuhito.service.NewEmployment;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * A request to add a person to an org unit in one call, and its response.
 * The request starts an employment and adds its first assignment.
 *
 * @param userId the ID of the Keycloak user
 * @param orgUnitId the ID of the org unit
 * @param validFrom the first date of the employment and of the assignment, as {@code YYYY-MM-DD}
 * @param validTo the first date after both, or null for no end
 * @param staffNumber the number that the legal entity gives to the person, or null
 * @param employmentType the code of the employment type, or null
 * @param grade the code of the grade, or null
 * @param workPercent the share of full-time work in percent, or null
 * @param jobTitle the code of the job title, or null
 * @param occupation the code of the occupation, or null
 * @param head true when the person leads the org unit, or null for false
 * @param primary false when this is not the main assignment, or null for true
 * @param employment the new employment. Only responses have it.
 * @param assignment the new assignment. Only responses have it.
 */
@Schema(
        name = "Staff",
        description = "A request to add a person to an org unit in one call, and its response. The request starts "
                + "an employment and adds its first assignment.",
        requiredProperties = {"userId", "orgUnitId", "validFrom"})
public record StaffRepresentation(
        @Schema(description = "The ID of the Keycloak user.", writeOnly = true)
        String userId,
        @Schema(writeOnly = true)
        String orgUnitId,
        @Schema(
                description = "The first date of the employment and of the assignment.",
                format = "date",
                writeOnly = true)
        String validFrom,
        @Schema(description = "The first date after both.", format = "date", writeOnly = true)
        String validTo,
        @Schema(
                description = "The number that the legal entity gives to the person.",
                maxLength = 64,
                pattern = "^[A-Za-z0-9][A-Za-z0-9_.-]*$",
                writeOnly = true)
        String staffNumber,
        @Schema(description = "The code of the employment type.", writeOnly = true)
        String employmentType,
        @Schema(description = "The code of the grade.", writeOnly = true)
        String grade,
        @Schema(
                description = "The share of full-time work in percent.",
                minimum = "0",
                exclusiveMinimum = true,
                maximum = "100",
                writeOnly = true)
        BigDecimal workPercent,
        @Schema(description = "The code of the job title.", writeOnly = true)
        String jobTitle,
        @Schema(description = "The code of the occupation.", writeOnly = true)
        String occupation,
        @Schema(description = "True when the person leads the org unit.", defaultValue = "false", writeOnly = true)
        Boolean head,
        @Schema(description = "False when this is not the main assignment.", defaultValue = "true", writeOnly = true)
        Boolean primary,
        @Schema(readOnly = true)
        EmploymentRepresentation employment,
        @Schema(readOnly = true)
        AssignmentRepresentation assignment) {

    NewEmployment toNewEmployment() {
        return new NewEmployment(userId, validFrom, validTo, staffNumber, employmentType, grade, null, workPercent,
                null, null, null);
    }

    // The first assignment of a new employment is its primary one unless the request says otherwise.
    AssignmentValues toAssignment(String employmentId) {
        return new AssignmentValues(employmentId, orgUnitId, validFrom, validTo, jobTitle, occupation, null, head,
                primary == null || primary, null, null, null, null, null, null);
    }

    static StaffRepresentation of(EmploymentRepresentation employment, AssignmentRepresentation assignment) {
        return new StaffRepresentation(null, null, null, null, null, null, null, null, null, null, null, null,
                employment, assignment);
    }
}
