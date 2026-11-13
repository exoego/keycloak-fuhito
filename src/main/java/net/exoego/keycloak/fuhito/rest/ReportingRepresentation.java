package net.exoego.keycloak.fuhito.rest;

import net.exoego.keycloak.fuhito.service.Reporting;

/**
 * One manager of a person, or one direct report of a person, on a date.
 *
 * @param userId the ID of the other user. It is the manager in a list of managers, and the report in a list of
 *        reports.
 * @param kind {@code SOLID} or {@code DOTTED}
 * @param derived true when no reporting line is stored and the head of the org unit is taken as the manager
 * @param lineId the ID of the stored reporting line. A derived entry has none.
 * @param assignmentId the ID of the assignment of the person who reports
 * @param orgUnitId the ID of the org unit of that assignment
 */
public record ReportingRepresentation(String userId, String kind, boolean derived, String lineId,
        String assignmentId, String orgUnitId) {

    static ReportingRepresentation of(Reporting reporting) {
        return new ReportingRepresentation(reporting.userId(), reporting.kind().name(), reporting.derived(),
                reporting.lineId(), reporting.assignmentId(), reporting.orgUnitId());
    }
}
