package net.exoego.keycloak.fuhito.store;

import java.util.Optional;

/**
 * The Keycloak organizations of one realm.
 */
public interface OrganizationDirectory {

    /**
     * Tells whether the realm has a Keycloak organization with the ID.
     * It is false when the realm does not use Keycloak Organizations.
     *
     * @param organizationId the ID of the organization
     * @return true when the organization exists
     */
    boolean exists(String organizationId);

    /**
     * Returns the alias of a Keycloak organization.
     *
     * @param organizationId the ID of the organization
     * @return the alias, or empty when the realm has no such organization
     */
    default Optional<String> aliasOf(String organizationId) {
        return Optional.empty();
    }

    /**
     * Returns the ID of the Keycloak organization with an alias.
     *
     * @param alias the alias of the organization
     * @return the ID, or empty when the realm has no such organization
     */
    default Optional<String> idOfAlias(String alias) {
        return Optional.empty();
    }
}
