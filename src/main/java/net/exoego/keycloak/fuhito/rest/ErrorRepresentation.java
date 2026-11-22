package net.exoego.keycloak.fuhito.rest;

import java.util.Map;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The body of an error response.
 */
@Schema(name = "Error", requiredProperties = {"error", "message"})
public record ErrorRepresentation(
        @Schema(description = "The stable error code.")
        String error,
        @Schema(description = "The values that the message refers to.")
        Map<String, String> params,
        @Schema(
                description = "The message for people. Its text can change. Its language is the one of the names, "
                        + "which the `Accept-Language` header gives, else English. The message of `invalid.header` "
                        + "is in English.")
        String message) {
}
