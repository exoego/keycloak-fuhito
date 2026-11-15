package net.exoego.keycloak.fuhito.service;

/**
 * A request to promote a person on a date. It is not checked yet.
 *
 * @param validFrom the first date of the new grade and the new job title, as {@code YYYY-MM-DD}
 * @param grade the code of the new grade, or null to keep the grade
 * @param jobTitle the code of the new job title, or null to keep the job title
 * @param assignmentId the ID of the assignment that gets the job title, or null for the primary assignment
 * @param head true or false to set whether the person leads the org unit, or null to keep it
 */
public record PromotionRequest(String validFrom, String grade, String jobTitle, String assignmentId, Boolean head) {
}
