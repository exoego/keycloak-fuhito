package net.exoego.keycloak.fuhito.service;

import java.util.Map;

/**
 * The values of a request to move every person of one employer to another employer, for example when two
 * companies merge. They are not checked yet.
 *
 * @param fromEmployerId the ID of the org unit that employs the people before the date
 * @param toEmployerId the ID of the org unit that employs them from the date
 * @param validFrom the first date at the new employer, as {@code YYYY-MM-DD}
 * @param endReason the code of the end reason of the old employments, or null
 * @param keepStaffNumbers true to keep the staff number of each person, unless {@code staffNumbers} gives a new
 *        one
 * @param staffNumbers the new staff numbers by the ID of the old employment, or null
 */
public record TransferAllRequest(String fromEmployerId, String toEmployerId, String validFrom, String endReason,
        boolean keepStaffNumbers, Map<String, String> staffNumbers) {
}
