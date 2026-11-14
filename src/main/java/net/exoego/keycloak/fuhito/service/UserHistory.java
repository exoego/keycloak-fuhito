package net.exoego.keycloak.fuhito.service;

import java.util.List;

/**
 * Everything that is stored about one user over time.
 *
 * @param personId the ID of the person, or null for a user with no person
 * @param userId the ID of the Keycloak user that the person is linked to, or null
 * @param employments the employments with their terms, oldest first
 * @param assignments the assignments with their details, oldest first
 */
public record UserHistory(String personId, String userId, List<EmploymentView> employments, List<AssignmentView> assignments) {
}
