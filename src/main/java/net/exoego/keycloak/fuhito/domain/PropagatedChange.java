package net.exoego.keycloak.fuhito.domain;

/**
 * A resource whose read a write changed through a derived value, although the write did not write the resource.
 * The write counted up the version of the resource already. The change log gets an entry of it after the entry of
 * the write.
 *
 * @param value the derived value that changed
 * @param resourceId the ID of the resource whose read changed
 */
public record PropagatedChange(DerivedValue value, String resourceId) {
}
