package net.exoego.keycloak.fuhito.rest;

import java.util.List;

import net.exoego.keycloak.fuhito.service.TransferRequest;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(
        name = "Transfer",
        description = "A request to move a person to another employer on a date, and its response.",
        requiredProperties = {"employerId", "validFrom"})
public record TransferRepresentation(
        @Schema(
                description = "The ID of the org unit that employs the person from the date. It is a legal entity "
                        + "on that date, and not the employer of today.",
                writeOnly = true)
        String employerId,
        @Schema(description = "The first date at the new employer.", format = "date", writeOnly = true)
        String validFrom,
        @Schema(
                description = "The number that the new employer gives to the person.",
                maxLength = 64,
                pattern = "^[A-Za-z0-9][A-Za-z0-9_.-]*$",
                writeOnly = true)
        String staffNumber,
        @Schema(description = "The code of the end reason of the old employment.", writeOnly = true)
        String endReason,
        @Schema(description = "The employment at the old employer, with its end.", readOnly = true)
        EmploymentRepresentation ended,
        @Schema(description = "The employment at the new employer.", readOnly = true)
        EmploymentRepresentation started,
        @Schema(description = "The assignments that the new employment got.", readOnly = true)
        List<AssignmentRepresentation> assignments,
        @Schema(description = "The reporting lines that the new assignments got.", readOnly = true)
        List<ReportingLineRepresentation> reportingLines,
        @Schema(description = "The roles over org units that the new employment got.", readOnly = true)
        List<UnitRoleRepresentation> unitRoles) {

    TransferRequest toRequest() {
        return new TransferRequest(employerId, validFrom, staffNumber, endReason);
    }

    static TransferRepresentation of(EmploymentRepresentation ended, EmploymentRepresentation started,
            List<AssignmentRepresentation> assignments, List<ReportingLineRepresentation> reportingLines,
            List<UnitRoleRepresentation> unitRoles) {
        return new TransferRepresentation(null, null, null, null, ended, started, assignments, reportingLines,
                unitRoles);
    }
}
