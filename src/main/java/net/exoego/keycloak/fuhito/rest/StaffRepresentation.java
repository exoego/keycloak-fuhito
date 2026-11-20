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
        @Schema(
                description = "The ID of the org unit that employs the person. Leave it out when the realm has "
                        + "exactly one legal entity, or for an external employer.",
                writeOnly = true)
        String employerId,
        @Schema(
                description = "The code of the employer outside the realm, a reference item of the kind "
                        + "`EXTERNAL_EMPLOYER`. Give this or `employerId`, not both.",
                writeOnly = true)
        String externalEmployer,
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
                description = "The number that the employer gives to the person.",
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
        @Schema(
                description = "The last day of the fixed-term contract. Leave it out for an indefinite contract.",
                format = "date",
                writeOnly = true)
        String contractEndDate,
        @Schema(description = "The code of the job title.", writeOnly = true)
        String jobTitle,
        @Schema(description = "The code of the occupation.", writeOnly = true)
        String occupation,
        @Schema(
                description = "The code of the work location. Leave it out to take the one of the org unit.",
                writeOnly = true)
        String workLocation,
        @Schema(
                description = "The code of the cost center. Leave it out to take the one of the org unit.",
                writeOnly = true)
        String costCenter,
        @Schema(description = "True when the person leads the org unit.", defaultValue = "false", writeOnly = true)
        Boolean head,
        @Schema(description = "False when this is not the main assignment.", defaultValue = "true", writeOnly = true)
        Boolean primary,
        @Schema(readOnly = true)
        EmploymentRepresentation employment,
        @Schema(readOnly = true)
        AssignmentRepresentation assignment) {

    NewEmployment toNewEmployment(String person) {
        return new NewEmployment(person, validFrom, validTo, staffNumber, employmentType, grade, null, null,
                workPercent, contractEndDate, null, null, null, employerId, externalEmployer);
    }

    // The first assignment of a new employment is its primary one unless the request says otherwise.
    AssignmentValues toAssignment(String employmentId) {
        return new AssignmentValues(employmentId, orgUnitId, validFrom, validTo, jobTitle, occupation, null,
                workLocation, costCenter, head, primary == null || primary, null, null, null, null, null, null);
    }

    static StaffRepresentation of(EmploymentRepresentation employment, AssignmentRepresentation assignment) {
        return new StaffRepresentation(null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, employment, assignment);
    }
}
