package net.exoego.keycloak.fuhito.rest;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * A request to end the leave of a person.
 */
@Schema(name = "Return", description = "A request to end the leave of a person.", requiredProperties = {"validFrom"})
public record ReturnRepresentation(
        @Schema(description = "The first date back at work.", format = "date")
        String validFrom) {
}
