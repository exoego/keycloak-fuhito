package net.exoego.keycloak.fuhito.rest;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * A request to put a person on leave.
 */
@Schema(
        name = "Leave",
        description = "A request to put a person on leave.",
        requiredProperties = {"version", "validFrom"})
public record LeaveRepresentation(
        @Schema(description = "The version of the employment that the caller read.")
        Long version,
        @Schema(description = "The first date of the leave.", format = "date")
        String validFrom,
        @Schema(
                description = "The first date back at work. Leave it out when it is not known yet. On that date "
                        + "the status of before the leave comes back.",
                format = "date")
        String validTo,
        @Schema(description = "The code of the leave reason.")
        String reason) implements Versioned {
}
