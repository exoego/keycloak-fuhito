package net.exoego.keycloak.fuhito.rest;

import net.exoego.keycloak.fuhito.service.Reporting;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(
        name = "Reporting",
        description = "One manager of a person, or one direct report of a person, on a date.",
        requiredProperties = {"personId", "kind", "derived", "assignmentId", "orgUnitId"})
public record ReportingRepresentation(
        @Schema(
                description = "The ID of the other person. It is the manager in a list of managers, and the report "
                        + "in a list of reports.")
        String personId,
        @Schema(description = "The ID of the Keycloak user that the other person is linked to.")
        String userId,
        @Schema(ref = "ReportingKind")
        String kind,
        @Schema(
                description = "True when no reporting line is stored and the head of the org unit is taken as the "
                        + "manager.")
        boolean derived,
        @Schema(description = "The ID of the stored reporting line. A derived entry has none.")
        String lineId,
        @Schema(description = "The ID of the assignment of the person who reports.")
        String assignmentId,
        @Schema(description = "The ID of the org unit of that assignment.")
        String orgUnitId) {

    static ReportingRepresentation of(Reporting reporting) {
        return new ReportingRepresentation(reporting.personId(), reporting.userId(), reporting.kind().name(), reporting.derived(),
                reporting.lineId(), reporting.assignmentId(), reporting.orgUnitId());
    }
}
