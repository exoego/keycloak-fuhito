package net.exoego.keycloak.fuhito.store;

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
}
