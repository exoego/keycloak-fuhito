package net.exoego.keycloak.fuhito.rest;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(
        name = "End",
        description = "A request to end something on a date. It has no end reason. Only an `EmploymentEnd` has "
                + "one.",
        requiredProperties = {"version", "validTo"})
public record EndRepresentation(
        @Schema(description = "The version of the resource that the caller read.")
        Long version,
        @Schema(description = "The first date on which it no longer exists.", format = "date")
        String validTo) implements Versioned {
}
