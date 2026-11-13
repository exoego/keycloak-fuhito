package net.exoego.keycloak.fuhito.service;

import java.math.BigDecimal;
import java.util.Map;

/**
 * The values of a request to add an assignment or to change its details. They are not checked yet.
 * In a change, the values replace all details. A missing optional value clears it.
 *
 * @param employmentId the ID of the employment. Only a new assignment uses it.
 * @param orgUnitId the ID of the org unit. Only a new assignment uses it.
 * @param validFrom the first date of the assignment or of the new details, as {@code YYYY-MM-DD}
 * @param validTo the first date after the assignment, or null. Only a new assignment uses it.
 * @param jobTitle the code of the job title, or null
 * @param occupation the code of the occupation, or null
 * @param kind the code of the assignment kind, or null
 * @param head true when the person leads the org unit, or null for false
 * @param primary true when this is the main assignment of the employment, or null for false
 * @param switchPrimary true to take the primary flag away from other assignments of the employment for the
 *        same dates, or null for false. It matters only when {@code primary} is true.
 * @param allocationPercent the share of the person's work in percent, or null
 * @param displayOrder the position among the members of the org unit, or null for 0
 * @param attributes free values, or null
 * @param externalId the ID in the system it came from, or null. Only a new assignment uses it.
 * @param source the name of that system, or null. Only a new assignment uses it.
 */
public record AssignmentValues(String employmentId, String orgUnitId, String validFrom, String validTo,
        String jobTitle, String occupation, String kind, Boolean head, Boolean primary, Boolean switchPrimary,
        BigDecimal allocationPercent, Integer displayOrder, Map<String, Object> attributes, String externalId,
        String source) {
}
