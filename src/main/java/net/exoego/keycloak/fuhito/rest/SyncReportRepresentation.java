package net.exoego.keycloak.fuhito.rest;

import java.util.List;

import net.exoego.keycloak.fuhito.service.SyncReport;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(
        name = "SyncReport",
        description = "What a sync from user attributes did, or would do.",
        requiredProperties = {"dryRun", "users", "employmentsStarted", "assignmentsAdded", "assignmentsMoved", "assignmentsChanged", "assignmentsEnded", "reportingLinesChanged", "unmatched"})
public record SyncReportRepresentation(
        @Schema(description = "True when the request asked for a dry run.")
        boolean dryRun,
        @Schema(description = "The number of users with a department.")
        int users,
        @Schema(description = "The number of employments that were started.")
        int employmentsStarted,
        @Schema(description = "The number of assignments that were added.")
        int assignmentsAdded,
        @Schema(description = "The number of users whose assignment moved to another org unit.")
        int assignmentsMoved,
        @Schema(description = "The number of assignments that got a new job title.")
        int assignmentsChanged,
        @Schema(description = "The number of assignments that ended because the department was removed.")
        int assignmentsEnded,
        @Schema(description = "The number of users whose reporting line changed.")
        int reportingLinesChanged,
        @Schema(description = "The values of user attributes that matched nothing.")
        List<SyncReport.Unmatched> unmatched) {

    static SyncReportRepresentation of(SyncReport report, boolean dryRun) {
        return new SyncReportRepresentation(dryRun, report.users(), report.employmentsStarted(),
                report.assignmentsAdded(), report.assignmentsMoved(), report.assignmentsChanged(),
                report.assignmentsEnded(), report.reportingLinesChanged(), report.unmatched());
    }
}
