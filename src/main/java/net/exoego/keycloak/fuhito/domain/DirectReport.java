package net.exoego.keycloak.fuhito.domain;

/**
 * One assignment whose person reports to a manager on a date.
 *
 * @param personId the ID of the person who reports
 * @param userId the ID of the Keycloak user that the person is linked to, or null
 * @param kind the kind of the line
 * @param derived true when no stored line says so, and the manager leads the org unit or the nearest one above
 *        that has a head
 * @param lineId the ID of the stored line, or null for a derived one
 * @param assignmentId the ID of the assignment that reports
 * @param orgUnitId the ID of the org unit of that assignment
 */
public record DirectReport(String personId, String userId, ReportingLine.Kind kind, boolean derived, String lineId,
        String assignmentId, String orgUnitId) {
}
