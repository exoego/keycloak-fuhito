package net.exoego.keycloak.fuhito.rest;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The time settings of an org unit. They also hold for the units below it, unless one of them has its own.
 *
 * @param orgUnitId the ID of the org unit. Requests ignore it.
 * @param timeZone the time zone, for example {@code America/New_York}, or null to use the one from above
 * @param dayStart the time of day when the date changes, as {@code HH:mm}, or null to use the one from above
 */
@Schema(
        name = "TimeSettings",
        description = "The time settings of an org unit. They also hold for the units below it, unless one of them "
                + "has its own.")
public record TimeSettingsRepresentation(
        @Schema(readOnly = true)
        String orgUnitId,
        @Schema(
                description = "The time zone. Leave it out to use the one from above.",
                examples = {"America/New_York"})
        String timeZone,
        @Schema(
                description = "The time of day when the date changes, as `HH:mm`. Leave it out to use the one from "
                        + "above.",
                examples = {"05:00"})
        String dayStart) {
}
