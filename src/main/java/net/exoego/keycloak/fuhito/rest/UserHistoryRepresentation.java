package net.exoego.keycloak.fuhito.rest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import net.exoego.keycloak.fuhito.service.UserHistory;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(
        name = "UserHistory",
        description = "Everything that is stored about one person over time.",
        requiredProperties = {"employments", "assignments"})
public record UserHistoryRepresentation(
        @Schema(description = "The ID of the person. A user with no person has none.")
        String personId,
        @Schema(description = "The ID of the Keycloak user that the person is linked to.")
        String userId,
        @Schema(
                description = "When the personal data of the person was masked, in UTC. It is missing when it never "
                        + "was.",
                format = "date-time")
        String maskedAt,
        @Schema(description = "The employments with their terms, oldest first.")
        List<EmploymentRepresentation> employments,
        @Schema(description = "The assignments with their details, oldest first.")
        List<AssignmentRepresentation> assignments) {

    static UserHistoryRepresentation of(UserHistory history, Optional<Instant> maskedAt) {
        return new UserHistoryRepresentation(history.personId(), history.userId(), maskedAt.map(Instant::toString).orElse(null),
                history.employments().stream().map(EmploymentRepresentation::of).toList(),
                history.assignments().stream().map(AssignmentRepresentation::of).toList());
    }
}
