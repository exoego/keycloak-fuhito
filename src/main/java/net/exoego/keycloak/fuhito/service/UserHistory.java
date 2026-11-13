package net.exoego.keycloak.fuhito.service;

import java.util.List;

/**
 * Everything that is stored about one user over time.
 *
 * @param userId the ID of the user
 * @param employments the employments with their terms, oldest first
 * @param assignments the assignments with their details, oldest first
 */
public record UserHistory(String userId, List<EmploymentView> employments, List<AssignmentView> assignments) {
}
