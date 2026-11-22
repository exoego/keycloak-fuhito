package net.exoego.keycloak.fuhito.domain;

/**
 * The work location and the cost center that an org unit takes on some dates from itself and from the units between
 * it and a unit above it in the default hierarchy. A value that none of them has is null, and then comes from above
 * that unit.
 *
 * @param orgUnitId the ID of the org unit
 * @param period the dates
 * @param workLocationId the ID of the work location, or null
 * @param costCenterId the ID of the cost center, or null
 */
public record PlaceSpan(String orgUnitId, ValidPeriod period, String workLocationId, String costCenterId) {
}
