package net.exoego.keycloak.fuhito.rest;

/**
 * The time settings of an org unit. They also hold for the units below it, unless one of them has its own.
 *
 * @param orgUnitId the ID of the org unit. Requests ignore it.
 * @param timeZone the time zone, for example {@code America/New_York}, or null to use the one from above
 * @param dayStart the time of day when the date changes, as {@code HH:mm}, or null to use the one from above
 */
public record TimeSettingsRepresentation(String orgUnitId, String timeZone, String dayStart) {
}
