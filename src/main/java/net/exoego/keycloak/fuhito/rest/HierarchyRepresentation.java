package net.exoego.keycloak.fuhito.rest;

/**
 * A named tree of org units.
 *
 * @param id the ID
 * @param code the code, which is unique in the realm
 * @param name the name
 */
public record HierarchyRepresentation(String id, String code, String name) {
}
