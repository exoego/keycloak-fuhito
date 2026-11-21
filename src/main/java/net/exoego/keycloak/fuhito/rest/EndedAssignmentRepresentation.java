package net.exoego.keycloak.fuhito.rest;

import java.util.List;

import net.exoego.keycloak.fuhito.domain.Assignment;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * An assignment that ends on the start of another assignment of the same person, in the reads of that one.
 */
@Schema(
        name = "EndedAssignment",
        description = "An assignment of the same person that ends on the start of another one, with the same primary "
                + "flag.",
        requiredProperties = {"assignmentId", "orgUnitId", "primary"})
public record EndedAssignmentRepresentation(
        @Schema(description = "The ID of the assignment that ends.")
        String assignmentId,
        @Schema(description = "The ID of its org unit.")
        String orgUnitId,
        @Schema(description = "True when it is the main assignment of its employment on its last day.")
        Boolean primary) {

    // A write rejects an empty list, so a response that is sent back as a body must not have one.
    static List<EndedAssignmentRepresentation> of(List<Assignment> lastRows) {
        if (lastRows.isEmpty()) {
            return null;
        }
        return lastRows.stream()
                .map(row -> new EndedAssignmentRepresentation(row.assignmentId(), row.orgUnitId(), row.primary()))
                .toList();
    }
}
