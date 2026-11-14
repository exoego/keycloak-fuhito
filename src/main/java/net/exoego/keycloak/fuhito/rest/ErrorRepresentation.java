package net.exoego.keycloak.fuhito.rest;

import java.util.Map;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The body of an error response.
 *
 * @param error the stable error code
 * @param params the values that the message refers to
 * @param message the message for people
 */
@Schema(name = "Error", requiredProperties = {"error", "message"})
public record ErrorRepresentation(
        @Schema(description = "The stable error code.")
        String error,
        @Schema(description = "The values that the message refers to.")
        Map<String, String> params,
        @Schema(
                description = "The message for people. Its text can change. Its language follows the "
                        + "`Accept-Language` header.")
        String message) {
}
