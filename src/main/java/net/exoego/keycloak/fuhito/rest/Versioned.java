package net.exoego.keycloak.fuhito.rest;

/**
 * The body of a write that names the version of the resource that the caller read.
 */
interface Versioned {

    /**
     * Returns the version of the resource that the caller read.
     *
     * @return the version, or null when the body has none
     */
    Long version();
}
