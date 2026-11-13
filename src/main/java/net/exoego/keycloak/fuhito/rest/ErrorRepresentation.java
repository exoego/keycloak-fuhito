package net.exoego.keycloak.fuhito.rest;

import java.util.Map;

/**
 * The body of an error response.
 *
 * @param error the stable error code
 * @param params the values that the message refers to
 * @param message the message for people
 */
public record ErrorRepresentation(String error, Map<String, String> params, String message) {
}
