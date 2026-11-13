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
     * Tells whether the ID is of a user of the realm, or of a removed user that the extension still has data
     * about. Reads use this, so that the history of a removed user stays readable.
     *
     * @param userId the ID of the user
     * @return true when the user exists or left data behind
     */
    default boolean isKnown(String userId) {
        return exists(userId);
    }

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
