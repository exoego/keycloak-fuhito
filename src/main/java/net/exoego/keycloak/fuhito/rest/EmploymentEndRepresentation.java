package net.exoego.keycloak.fuhito.rest;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(
        name = "EmploymentEnd",
        description = "A request to end an employment on a date.",
        requiredProperties = {"version", "validTo"})
public record EmploymentEndRepresentation(
        @Schema(description = "The version of the employment that the caller read.")
        Long version,
        @Schema(description = "The first date on which the employment no longer holds.", format = "date")
        String validTo,
        @Schema(description = "The code of the end reason. A request without it removes a stored reason.")
        String endReason) implements Versioned {
}
