package net.exoego.keycloak.fuhito.rest;

import java.util.List;

import net.exoego.keycloak.fuhito.service.UserHistory;

/**
 * Everything that is stored about one user over time.
 *
 * @param userId the ID of the user
 * @param employments the employments with their terms, oldest first
 * @param assignments the assignments with their details, oldest first
 */
public record UserHistoryRepresentation(String userId, List<EmploymentRepresentation> employments,
        List<AssignmentRepresentation> assignments) {

    static UserHistoryRepresentation of(UserHistory history) {
        return new UserHistoryRepresentation(history.userId(),
                history.employments().stream().map(EmploymentRepresentation::of).toList(),
                history.assignments().stream().map(AssignmentRepresentation::of).toList());
    }
}
