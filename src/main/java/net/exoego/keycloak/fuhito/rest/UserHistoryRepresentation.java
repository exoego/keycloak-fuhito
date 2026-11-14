package net.exoego.keycloak.fuhito.rest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import net.exoego.keycloak.fuhito.service.UserHistory;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Everything that is stored about one user over time.
 *
 * @param userId the ID of the user
 * @param maskedAt when the personal data of the user was masked, in UTC. It is missing when it never was.
 * @param employments the employments with their terms, oldest first
 * @param assignments the assignments with their details, oldest first
 */
@Schema(
        name = "UserHistory",
        description = "Everything that is stored about one user over time.",
        requiredProperties = {"userId", "employments", "assignments"})
public record UserHistoryRepresentation(
        String userId,
        @Schema(
                description = "When the personal data of the user was masked, in UTC. It is missing when it never "
                        + "was.",
                format = "date-time")
        String maskedAt,
        @Schema(description = "The employments with their terms, oldest first.")
        List<EmploymentRepresentation> employments,
        @Schema(description = "The assignments with their details, oldest first.")
        List<AssignmentRepresentation> assignments) {

    static UserHistoryRepresentation of(UserHistory history, Optional<Instant> maskedAt) {
        return new UserHistoryRepresentation(history.userId(), maskedAt.map(Instant::toString).orElse(null),
                history.employments().stream().map(EmploymentRepresentation::of).toList(),
                history.assignments().stream().map(AssignmentRepresentation::of).toList());
    }
}
