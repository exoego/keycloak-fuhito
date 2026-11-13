package net.exoego.keycloak.fuhito.rest;

/**
 * A request to end something on a date.
 *
 * @param validTo the first date on which it no longer exists, as {@code YYYY-MM-DD}
 * @param endReason the code of the end reason, or null. Only the end of an employment uses it.
 */
public record EndRepresentation(String validTo, String endReason) {
}
