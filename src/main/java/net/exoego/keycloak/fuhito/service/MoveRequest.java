package net.exoego.keycloak.fuhito.service;

/**
 * The values of a request to move a person to another org unit. They are not checked yet.
 *
 * @param orgUnitId the ID of the org unit where the person works from the date
 * @param validFrom the first date in the new org unit, as {@code YYYY-MM-DD}
 * @param jobTitle the code of the job title in the new org unit, or null to keep the job title
 * @param head true when the person leads the new org unit, or null for false
 * @param displayOrder the position among the members of the new org unit, or null for 0
 */
public record MoveRequest(String orgUnitId, String validFrom, String jobTitle, Boolean head, Integer displayOrder) {
}
