package net.exoego.keycloak.fuhito.rest;

import java.util.Locale;
import java.util.OptionalInt;

import jakarta.ws.rs.core.Response;
import net.exoego.keycloak.fuhito.store.IdempotencyStore;

/**
 * What a resource needs to know about the current request and its caller.
 */
public interface RequestContext {

    /**
     * Requires the permission to read everything, the data of people included. Throws when the caller lacks it.
     */
    void requireRead();

    /**
     * Requires the permission to read the org structure. These are the org units, the hierarchies, and the
     * reference items. Throws when the caller lacks it. A caller who may read everything has it too.
     */
    void requireReadStructure();

    /**
     * Returns how many days after today the caller may read the org structure on. A caller who may read
     * everything has no limit, and neither does any caller of a realm that sets none.
     *
     * @return the number of days, or empty for no limit
     */
    OptionalInt structureDaysAhead();

    /**
     * Requires the permission to change organization data. Throws when the caller lacks it.
     * Also throws when the request names a change order that does not exist.
     */
    void requireWrite();

    /**
     * Requires the permission of Keycloak to manage a user. Throws when the caller lacks it.
     * A user that does not exist asks for nothing here.
     *
     * @param userId the ID of the Keycloak user
     */
    void requireManageUser(String userId);

    /**
     * Requires the permission of Keycloak to manage the groups of the realm. Throws when the caller lacks it.
     */
    void requireManageGroups();

    /**
     * Requires the permission to manage the members of a Keycloak organization. Throws when the caller lacks it.
     * An organization that does not exist asks for nothing here, and fails where it is used.
     *
     * @param organizationId the ID of the organization
     */
    void requireManageOrganization(String organizationId);

    /**
     * Returns the URL of the request with another cursor, for the link to the next page of a list.
     *
     * @param cursor the cursor of the next page
     * @return the URL, with `after` set to the cursor and without `first`
     */
    String nextPage(String cursor);

    /**
     * Returns who sent the request.
     *
     * @return the ID of the Keycloak user of the caller
     */
    String callerUserId();

    /**
     * Returns the language for messages.
     *
     * @return the locale
     */
    Locale locale();

    /**
     * Returns a header of the request.
     *
     * @param name the name of the header
     * @return the value, or null when the request has none
     */
    String header(String name);

    /**
     * Returns the path of the request.
     *
     * @return the path, without the query
     */
    String requestPath();

    /**
     * Locks the stored row of the resource that the path of the request names, until the end of the transaction.
     * A second write to the resource then waits for the first one.
     */
    void lockTarget();

    /**
     * Locks the stored row of one resource until the end of the transaction, as {@link #lockTarget()} does for the
     * resource of the path.
     *
     * @param collection the first part of the path of the resource, for example {@code org-units}
     * @param id the ID of the resource
     */
    void lock(String collection, String id);

    /**
     * Sends the changes of a write to the database now, so that a conflict with another write fails the request.
     * A read sends nothing.
     *
     * @throws jakarta.persistence.OptimisticLockException when another write changed a row after this one read it
     */
    void flush();

    /**
     * Returns the query of the request, as it came.
     *
     * @return the query, or null when the request has none
     */
    String requestQuery();

    /**
     * Returns the storage of the responses to requests with an idempotency key.
     *
     * @return the storage of the realm
     */
    IdempotencyStore idempotency();

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
     * Only the admin event gets the new state.
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
