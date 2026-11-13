package net.exoego.keycloak.fuhito.rest;

import java.math.BigDecimal;

import net.exoego.keycloak.fuhito.service.AssignmentValues;
import net.exoego.keycloak.fuhito.service.NewEmployment;

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
public record StaffRepresentation(String userId, String orgUnitId, String validFrom, String validTo,
        String staffNumber, String employmentType, String grade, BigDecimal workPercent, String jobTitle,
        String occupation, Boolean head, Boolean primary, EmploymentRepresentation employment,
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
