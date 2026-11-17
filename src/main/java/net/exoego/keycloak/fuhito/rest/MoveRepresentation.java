package net.exoego.keycloak.fuhito.rest;

import net.exoego.keycloak.fuhito.service.MoveRequest;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(
        name = "Move",
        description = "A request to move a person to another org unit on a date, and its response.",
        requiredProperties = {"orgUnitId", "validFrom"})
public record MoveRepresentation(
        @Schema(description = "The ID of the org unit where the person works from the date.", writeOnly = true)
        String orgUnitId,
        @Schema(description = "The first date in the new org unit.", format = "date", writeOnly = true)
        String validFrom,
        @Schema(
                description = "The code of the job title in the new org unit. Leave it out to keep the job title.",
                writeOnly = true)
        String jobTitle,
        @Schema(
                description = "True when the person leads the new org unit. The flag of the old org unit is not "
                        + "kept.",
                defaultValue = "false",
                writeOnly = true)
        Boolean head,
        @Schema(
                description = "The position among the members of the new org unit.",
                defaultValue = "0",
                writeOnly = true)
        Integer displayOrder,
        @Schema(
                description = "The code of the work location in the new org unit. Leave it out to take the one of "
                        + "the new org unit. The one of the old assignment is not kept.",
                writeOnly = true)
        String workLocation,
        @Schema(
                description = "The code of the cost center in the new org unit. Leave it out to take the one of the "
                        + "new org unit. The one of the old assignment is not kept.",
                writeOnly = true)
        String costCenter,
        @Schema(description = "The assignment to the old org unit, with its end.", readOnly = true)
        AssignmentRepresentation ended,
        @Schema(description = "The assignment to the new org unit.", readOnly = true)
        AssignmentRepresentation started) {

    MoveRequest toRequest() {
        return new MoveRequest(orgUnitId, validFrom, jobTitle, head, displayOrder, workLocation, costCenter);
    }

    static MoveRepresentation of(AssignmentRepresentation ended, AssignmentRepresentation started) {
        return new MoveRepresentation(null, null, null, null, null, null, null, ended, started);
    }
}
