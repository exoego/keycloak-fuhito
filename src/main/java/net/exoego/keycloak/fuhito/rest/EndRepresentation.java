package net.exoego.keycloak.fuhito.rest;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(name = "End", description = "A request to end something on a date.", requiredProperties = {"validTo"})
public record EndRepresentation(
        @Schema(description = "The first date on which it no longer exists.", format = "date")
        String validTo,
        @Schema(description = "The code of the end reason. Only the end of an employment uses it.")
        String endReason) {
}
