package net.exoego.keycloak.fuhito.rest;

/**
 * Permission checks for the caller of the current request.
 * Each method throws when the caller lacks the permission.
 */
public interface Access {

    /**
     * Requires the permission to read organization data.
     */
    void requireRead();

    /**
     * Requires the permission to change organization data.
     */
    void requireWrite();
}
