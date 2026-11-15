package net.exoego.keycloak.fuhito.domain;

import java.time.LocalDate;

/**
 * One org unit follows another that ended.
 * One old unit with one new unit is a succession. Several old units with one new unit are a merger. One old unit
 * with several new units is a split. The kind is not stored, because the links already say it.
 *
 * @param id the ID
 * @param predecessorId the ID of the unit that ended
 * @param successorId the ID of the unit that follows it
 * @param effectiveDate the first date on which the old unit no longer exists
 */
public record OrgUnitSuccession(String id, String predecessorId, String successorId, LocalDate effectiveDate) {
}
