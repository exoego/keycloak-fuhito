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
}
