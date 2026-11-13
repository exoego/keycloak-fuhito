package net.exoego.keycloak.fuhito.store;

/**
 * The users of one realm.
 */
public interface UserDirectory {

    /**
     * Tells whether the realm has a user with the ID.
     *
     * @param userId the ID of the user
     * @return true when the user exists
     */
    boolean exists(String userId);
}
