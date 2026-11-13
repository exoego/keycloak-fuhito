package net.exoego.keycloak.fuhito.store;

import java.util.Optional;

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

    /**
     * Finds the ID of the user with a username.
     *
     * @param username the username
     * @return the ID, or empty when the realm has no such user
     */
    default Optional<String> findIdByUsername(String username) {
        return Optional.empty();
    }
}
