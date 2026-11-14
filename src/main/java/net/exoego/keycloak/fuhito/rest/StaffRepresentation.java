package net.exoego.keycloak.fuhito.rest;

import java.math.BigDecimal;

import net.exoego.keycloak.fuhito.service.AssignmentValues;
import net.exoego.keycloak.fuhito.service.NewEmployment;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(
        name = "Staff",
        description = "A request to add a person to an org unit in one call, and its response. The request starts "
                + "an employment and adds its first assignment.",
        requiredProperties = {"orgUnitId", "validFrom"})
public record StaffRepresentation(
        @Schema(description = "The ID of the person.", writeOnly = true)
        String personId,
        @Schema(
                description = "The ID of the Keycloak user, in place of `personId`. The person of the user is used, "
                        + "and created when the user has none.",
                writeOnly = true)
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

    NewEmployment toNewEmployment(String person) {
        return new NewEmployment(person, validFrom, validTo, staffNumber, employmentType, grade, null, workPercent,
                null, null, null);
    }

    // The first assignment of a new employment is its primary one unless the request says otherwise.
    AssignmentValues toAssignment(String employmentId) {
        return new AssignmentValues(employmentId, orgUnitId, validFrom, validTo, jobTitle, occupation, null, head,
                primary == null || primary, null, null, null, null, null, null);
    }

    static StaffRepresentation of(EmploymentRepresentation employment, AssignmentRepresentation assignment) {
        return new StaffRepresentation(null, null, null, null, null, null, null, null, null, null, null, null, null,
                employment, assignment);
    }
}
