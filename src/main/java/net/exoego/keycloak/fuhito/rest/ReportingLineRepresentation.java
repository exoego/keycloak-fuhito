package net.exoego.keycloak.fuhito.rest;

import java.util.Objects;

import net.exoego.keycloak.fuhito.domain.ReportingLine;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * A reporting line in requests and responses.
 */
@Schema(name = "ReportingLine", requiredProperties = {"assignmentId", "managerUserId", "validFrom"})
public record ReportingLineRepresentation(
        @Schema(readOnly = true)
        String id,
        @Schema(description = "The ID of the assignment of the person who reports.")
        String assignmentId,
        @Schema(description = "The ID of the Keycloak user who is the manager.")
        String managerUserId,
        @Schema(ref = "ReportingKind", defaultValue = "SOLID")
        String kind,
        @Schema(description = "The first date of the line.", format = "date")
        String validFrom,
        @Schema(
                description = "The first date after the line. Leave it out to last as long as the assignment.",
                format = "date")
        String validTo) {

    static ReportingLineRepresentation of(ReportingLine line) {
        return new ReportingLineRepresentation(line.id(), line.assignmentId(), line.managerUserId(),
                line.kind().name(), line.period().from().toString(),
                Objects.toString(line.period().to(), null));
    }
}
