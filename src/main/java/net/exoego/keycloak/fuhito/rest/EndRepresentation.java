package net.exoego.keycloak.fuhito.rest;

/**
 * A request to end something on a date.
 *
 * @param validTo the first date on which it no longer exists, as {@code YYYY-MM-DD}
 */
public record EndRepresentation(String validTo) {
}
