package net.exoego.keycloak.fuhito.rest;

import java.util.Locale;

import jakarta.ws.rs.core.Response;

/**
 * What a resource needs to know about the current request and its caller.
 */
public interface RequestContext {

    /**
     * Requires the permission to read organization data. Throws when the caller lacks it.
     */
    void requireRead();

    /**
     * Requires the permission to change organization data. Throws when the caller lacks it.
     * Also throws when the request names a change order that does not exist.
     */
    void requireWrite();

    /**
     * Returns the language for messages.
     *
     * @return the locale
     */
    Locale locale();

    /**
     * Makes an exception that ends the request with the response and rolls back the transaction.
     *
     * @param response the error response
     * @return the exception to throw
     */
    RuntimeException failure(Response response);

    /**
     * The kind of a change, for the event log.
     */
    enum Change {
        /** Something new was stored. */
        CREATE,
        /** Something stored was changed. */
        UPDATE,
        /** Something stored was removed. */
        DELETE
    }

    /**
     * Records a successful change in the admin event log of the realm and in the change log.
     *
     * @param change the kind of the change
     * @param resourceType the type of the changed resource, for example {@code FUHITO_ORG_UNIT}
     * @param resourceId the ID of the changed resource
     * @param resourcePath the path of the request below the realm
     * @param representation the new state, or null
     */
    void recordChange(Change change, String resourceType, String resourceId, String resourcePath,
            Object representation);
}
