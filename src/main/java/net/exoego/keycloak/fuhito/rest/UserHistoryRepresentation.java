package net.exoego.keycloak.fuhito.rest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import net.exoego.keycloak.fuhito.domain.ExternalId;
import net.exoego.keycloak.fuhito.service.AssignmentView;
import net.exoego.keycloak.fuhito.service.ExternalIdLists;
import net.exoego.keycloak.fuhito.service.ExternalIdService;
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

    // The IDs of every employment and of every assignment are read with one query for each.
    static UserHistoryRepresentation of(UserHistory history, Optional<Instant> maskedAt,
            ExternalIdService externalIds) {
        ExternalIdLists employmentIds = externalIds.lists(ExternalId.Kind.EMPLOYMENT,
                history.employments().stream().map(view -> view.employment().id()).toList());
        ExternalIdLists assignmentIds = externalIds.lists(ExternalId.Kind.ASSIGNMENT,
                history.assignments().stream().map(AssignmentView::assignmentId).toList());
        return new UserHistoryRepresentation(history.personId(), history.userId(),
                maskedAt.map(Instant::toString).orElse(null),
                history.employments().stream().map(view -> EmploymentRepresentation.of(view, employmentIds))
                        .toList(),
                history.assignments().stream().map(view -> AssignmentRepresentation.of(view, assignmentIds))
                        .toList());
    }
}
