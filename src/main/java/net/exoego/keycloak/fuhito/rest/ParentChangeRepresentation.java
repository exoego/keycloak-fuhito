package net.exoego.keycloak.fuhito.rest;

/**
 * A request to give an org unit a new parent.
 *
 * @param parentId the ID of the new parent, or null to make the unit a root
 * @param validFrom the first date under the new parent, as {@code YYYY-MM-DD}
 */
public record ParentChangeRepresentation(String parentId, String validFrom) {
}
