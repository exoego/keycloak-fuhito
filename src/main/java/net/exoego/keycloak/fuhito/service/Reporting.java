package net.exoego.keycloak.fuhito.service;

import net.exoego.keycloak.fuhito.domain.ReportingLine;

/**
 * One manager of a person, or one direct report of a person, on a date.
 *
 * @param personId the ID of the other person. It is the manager in a list of managers, and the report in a list
 *        of reports.
 * @param userId the ID of the Keycloak user that the other person is linked to, or null
 * @param kind solid or dotted
 * @param derived true when no reporting line is stored and the head of the org unit is taken as the manager
 * @param lineId the ID of the stored reporting line, or null when derived
 * @param assignmentId the ID of the assignment of the person who reports
 * @param orgUnitId the ID of the org unit of that assignment
 */
public record Reporting(String personId, String userId, ReportingLine.Kind kind, boolean derived, String lineId,
        String assignmentId, String orgUnitId) {
}
