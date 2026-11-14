package net.exoego.keycloak.fuhito.rest;

import java.util.List;
import java.util.Map;

import net.exoego.keycloak.fuhito.service.TransferAllRequest;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(
        name = "Transfers",
        description = "A request to move every person of one employer to another employer on a date, and its "
                + "response. It is for a merger of two legal entities.",
        requiredProperties = {"fromEmployerId", "toEmployerId", "validFrom"})
public record TransfersRepresentation(
        @Schema(description = "The ID of the org unit that employs the people today.", writeOnly = true)
        String fromEmployerId,
        @Schema(
                description = "The ID of the org unit that employs them from the date. It is a legal entity on "
                        + "that date.",
                writeOnly = true)
        String toEmployerId,
        @Schema(description = "The first date at the new employer.", format = "date", writeOnly = true)
        String validFrom,
        @Schema(description = "The code of the end reason of the old employments.", writeOnly = true)
        String endReason,
        @Schema(
                description = "True to keep the staff number of each person. A number that the new employer has "
                        + "given to another person rejects the request.",
                defaultValue = "false",
                writeOnly = true)
        Boolean keepStaffNumbers,
        @Schema(
                description = "New staff numbers, by the ID of the old employment. They win over "
                        + "`keepStaffNumbers`.",
                writeOnly = true)
        Map<String, String> staffNumbers,
        @Schema(
                description = "True to also end the old employer on the date. The request is rejected when an "
                        + "employment, a child org unit, or an assignment would still be there.",
                defaultValue = "false",
                writeOnly = true)
        Boolean endEmployer,
        @Schema(description = "The number of people who moved.", readOnly = true)
        Integer transferred,
        @Schema(description = "The old and the new employment of each person.", readOnly = true)
        List<Moved> employments,
        @Schema(description = "The old employer with its end, when the request asked for it.", readOnly = true)
        OrgUnitRepresentation endedEmployer) {

    @Schema(
            name = "MovedEmployment",
            description = "One person who moved to the new employer.",
            requiredProperties = {"personId", "endedEmploymentId", "startedEmploymentId"})
    public record Moved(
            String personId,
            @Schema(description = "The ID of the employment at the old employer.")
            String endedEmploymentId,
            @Schema(description = "The ID of the employment at the new employer.")
            String startedEmploymentId) {
    }

    TransferAllRequest toRequest() {
        return new TransferAllRequest(fromEmployerId, toEmployerId, validFrom, endReason,
                Boolean.TRUE.equals(keepStaffNumbers), staffNumbers);
    }

    static TransfersRepresentation of(List<Moved> employments, OrgUnitRepresentation endedEmployer) {
        return new TransfersRepresentation(null, null, null, null, null, null, null, employments.size(), employments,
                endedEmployer);
    }
}
