package net.exoego.keycloak.fuhito.rest;

import java.util.List;

import net.exoego.keycloak.fuhito.service.SyncReport;

/**
 * What a sync from user attributes did, or would do.
 *
 * @param dryRun true when the request asked for a dry run
 * @param users the number of users with a department
 * @param employmentsStarted the number of employments that were started
 * @param assignmentsAdded the number of assignments that were added
 * @param assignmentsMoved the number of users whose assignment moved to another org unit
 * @param assignmentsChanged the number of assignments that got a new job title
 * @param assignmentsEnded the number of assignments that ended because the department was removed
 * @param reportingLinesChanged the number of users whose reporting line changed
 * @param unmatched the values of user attributes that matched nothing
 */
public record SyncReportRepresentation(boolean dryRun, int users, int employmentsStarted, int assignmentsAdded,
        int assignmentsMoved, int assignmentsChanged, int assignmentsEnded, int reportingLinesChanged,
        List<SyncReport.Unmatched> unmatched) {

    static SyncReportRepresentation of(SyncReport report, boolean dryRun) {
        return new SyncReportRepresentation(dryRun, report.users(), report.employmentsStarted(),
                report.assignmentsAdded(), report.assignmentsMoved(), report.assignmentsChanged(),
                report.assignmentsEnded(), report.reportingLinesChanged(), report.unmatched());
    }
}
