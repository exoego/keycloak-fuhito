package net.exoego.keycloak.fuhito.domain;

/**
 * A link from an assignment to a manager, for a period.
 *
 * @param id the ID
 * @param assignmentId the ID of the assignment of the person who reports
 * @param managerUserId the ID of the Keycloak user who is the manager
 * @param kind solid or dotted
 * @param period the dates when the link holds
 */
public record ReportingLine(String id, String assignmentId, String managerUserId, Kind kind, ValidPeriod period) {

    /**
     * The kind of a reporting line.
     */
    public enum Kind {
        /** The main manager. An assignment has at most one at a time. */
        SOLID,
        /** A further manager, for example of a project. An assignment can have many. */
        DOTTED
    }

    /**
     * Returns this line with another period.
     *
     * @param newPeriod the new period
     * @return the line with the new period
     */
    public ReportingLine withPeriod(ValidPeriod newPeriod) {
        return new ReportingLine(id, assignmentId, managerUserId, kind, newPeriod);
    }
}
