package net.exoego.keycloak.fuhito.rest;

import java.util.List;
import java.util.Objects;

import net.exoego.keycloak.fuhito.domain.ReportingLine;
import net.exoego.keycloak.fuhito.service.ExternalIdLists;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * A reporting line in requests and responses.
 */
@Schema(name = "ReportingLine", requiredProperties = {"assignmentId", "validFrom"})
public record ReportingLineRepresentation(
        @Schema(readOnly = true)
        String id,
        @Schema(
                description = "The version of the line. A new line has 1. Each write counts it up by one, also a "
                        + "change of an ID of another system.",
                readOnly = true)
        Long version,
        @Schema(description = "The ID of the assignment of the person who reports.")
        String assignmentId,
        @Schema(description = "The ID of the person who is the manager.")
        String managerPersonId,
        @Schema(
                description = "The ID of the Keycloak user that the manager is linked to. A request can give it in "
                        + "place of `managerPersonId`. The person of the user is then used, and created when the "
                        + "user has none.")
        String managerUserId,
        @Schema(ref = "ReportingKind", defaultValue = "SOLID")
        String kind,
        @Schema(description = "The first date of the line.", format = "date")
        String validFrom,
        @Schema(
                description = "The first date after the line. Leave it out to last as long as the assignment.",
                format = "date")
        String validTo,
        @Schema(
                description = "The ID of the line in the system that `source` names. Only a create reads it. "
                        + "A read gives every ID in `externalIds`.",
                maxLength = 255,
                writeOnly = true)
        String externalId,
        @Schema(
                description = "The name of the system of `externalId`. Only a create reads it.",
                maxLength = 64,
                pattern = "^[A-Za-z0-9][A-Za-z0-9_.-]*$",
                writeOnly = true)
        String source,
        @Schema(description = "Every ID that a system has for the line, by system.", readOnly = true)
        List<ExternalIdPairRepresentation> externalIds) {

    /**
     * Makes the representation of a reporting line.
     *
     * @param line the line
     * @param ids the IDs of other systems, read with those of the other lines of the response
     * @return the representation
     */
    public static ReportingLineRepresentation of(ReportingLine line, ExternalIdLists ids) {
        return new ReportingLineRepresentation(line.id(), line.version(), line.assignmentId(), line.managerPersonId(),
                line.managerUserId(),
                line.kind().name(), line.period().from().toString(),
                Objects.toString(line.period().to(), null), null, null,
                ExternalIdPairRepresentation.of(ids.of(line.id(), null)));
    }
}
