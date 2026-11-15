package net.exoego.keycloak.fuhito.domain;

import java.time.LocalDate;

/**
 * The last row of a page of members. The next page starts after it.
 * It has the values that the order of the list uses.
 *
 * @param orgUnitId the ID of the org unit of the row
 * @param displayOrder the position of the row among the members of its org unit
 * @param validFrom the first date of the row
 * @param assignmentId the ID of the assignment of the row
 */
public record MemberCursor(String orgUnitId, int displayOrder, LocalDate validFrom, String assignmentId) {
}
