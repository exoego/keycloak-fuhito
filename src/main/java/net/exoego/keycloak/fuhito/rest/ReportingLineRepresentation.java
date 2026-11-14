package net.exoego.keycloak.fuhito.rest;

import java.util.Objects;

import net.exoego.keycloak.fuhito.domain.ReportingLine;

/**
 * A reporting line in requests and responses.
 *
 * @param id the ID. Requests ignore it.
 * @param assignmentId the ID of the assignment of the person who reports
 * @param managerUserId the ID of the Keycloak user who is the manager
 * @param kind {@code SOLID} or {@code DOTTED}. The default is {@code SOLID}.
 * @param validFrom the first date of the line, as {@code YYYY-MM-DD}
 * @param validTo the first date after the line, or null to last as long as the assignment
 */
public record ReportingLineRepresentation(String id, String assignmentId, String managerUserId, String kind,
        String validFrom, String validTo) {

    static ReportingLineRepresentation of(ReportingLine line) {
        return new ReportingLineRepresentation(line.id(), line.assignmentId(), line.managerUserId(),
                line.kind().name(), line.period().from().toString(),
                Objects.toString(line.period().to(), null));
    }
}
