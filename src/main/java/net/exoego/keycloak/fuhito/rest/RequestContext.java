package net.exoego.keycloak.fuhito.rest;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import jakarta.ws.rs.core.Response;
import net.exoego.keycloak.fuhito.domain.PropagatedChange;
import net.exoego.keycloak.fuhito.domain.ResourceType;

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
     * The write shares the lock of its realm until the end of the transaction. So it waits while a bulk write runs.
     */
    void requireWrite();

    /**
     * Requires the same as {@link #requireWrite()} for a bulk write, such as an import. The bulk write holds the lock
     * of its realm alone until the end of the transaction. So it waits for the writes of the realm that run, and
     * the writes that come later wait for it.
     */
    void requireBulkWrite();

    /**
     * Requires the same as {@link #requireWrite()} for a write that can change the reads of other resources, such as
     * a new work location of an org unit. Such a write holds the lock of its realm alone, as
     * {@link #requireBulkWrite()} does. So no other write of the realm changes what it compares while it finds the
     * reads that it changed. The check runs after the permission is checked, before the lock is taken.
     *
     * @param changesOtherReads tells whether the write can change the reads of other resources
     */
    void requireWrite(BooleanSupplier changesOtherReads);

    /**
     * Requires the same as {@link #requireWrite()} for a write that also changes a Keycloak user, such as a mask that
     * empties the name of the user. It locks the row of the user first, before the lock of the realm. Keycloak takes
     * the row first too, when it removes the user or its realm. So the write and such a removal wait for each other
     * in turn. The user is found after the permission is checked, before any lock is taken.
     *
     * @param userId finds the ID of the user, or gives null when the write changes no user
     * @return the ID that it found, or null
     */
    String requireWriteOfUser(Supplier<String> userId);

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
     * Returns the language for messages. It is the tag of {@link #nameLanguage()}, or English when there is none.
     *
     * @return the locale
     */
    Locale locale();

    /**
     * Returns the language that the caller reads names in. It is the tag of {@code Accept-Language} with the highest
     * {@code q}, as the header writes it. A tag with {@code q=0} is never the language.
     *
     * @return the language tag, or null when the request names no language with a weight above 0. The caller then
     *         reads the primary names.
     */
    String nameLanguage();

    /**
     * Returns the parts of the path of the request after {@code fuhito}, decoded. The first part names a kind of
     * resource, for example {@code org-units}. The second part is often the ID of one resource of the kind.
     *
     * @return the parts, or an empty list for the root
     */
    List<String> pathParts();

    /**
     * Locks the stored rows of the resource that the path of the request names, and of other resources, until the
     * end of the transaction. A second write to one of them then waits for the first one.
     * The rows are locked in one fixed order, as {@link #lock(Map)} does.
     *
     * @param others the IDs of the other resources by the first part of their path, for example {@code employments}
     */
    void lockTarget(Map<String, ? extends Collection<String>> others);

    /**
     * Locks the stored rows of resources until the end of the transaction. Every write locks in one order of kinds,
     * and in the order of the IDs within one kind. So two writes never wait for each other at once.
     * These locks come after the lock of {@link #requireWrite()}.
     * A write calls it once, before it reads or changes any of them.
     *
     * @param ids the IDs of the resources by the first part of their path, for example {@code org-units}
     */
    void lock(Map<String, ? extends Collection<String>> ids);

    /**
     * Sends the changes of a write to the database now, so that a conflict with another write fails the request.
     * A read sends nothing.
     *
     * @throws jakarta.persistence.OptimisticLockException when another write changed a row after this one read it
     */
    void flush();

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
     * A successful change, for the event log.
     *
     * @param change the kind of the change
     * @param resourceType the type of the changed resource
     * @param resourceId the ID of the changed resource
     * @param resourcePath the path of the request below the realm
     * @param representation the new state, or null
     * @param propagated the other resources whose read the change changed. Their versions are counted up already.
     */
    record ChangeRecord(Change change, ResourceType resourceType, String resourceId, String resourcePath,
            Object representation, List<PropagatedChange> propagated) {

        /**
         * Makes the record of a change that changed no read of another resource.
         *
         * @param change the kind of the change
         * @param resourceType the type of the changed resource
         * @param resourceId the ID of the changed resource
         * @param resourcePath the path of the request below the realm
         * @param representation the new state, or null
         */
        public ChangeRecord(Change change, ResourceType resourceType, String resourceId, String resourcePath,
                Object representation) {
            this(change, resourceType, resourceId, resourcePath, representation, List.of());
        }
    }

    /**
     * Records a successful change in the admin event log of the realm and in the change log.
     * Only the admin event gets the new state.
     *
     * @param change the kind of the change
     * @param resourceType the type of the changed resource
     * @param resourceId the ID of the changed resource
     * @param resourcePath the path of the request below the realm
     * @param representation the new state, or null
     */
    default void recordChange(Change change, ResourceType resourceType, String resourceId, String resourcePath,
            Object representation) {
        recordChange(change, resourceType, resourceId, resourcePath, representation, List.of());
    }

    /**
     * Records a successful change as {@link #recordChange(Change, ResourceType, String, String, Object)} does, and a
     * propagated change of each other resource whose read it changed.
     *
     * @param change the kind of the change
     * @param resourceType the type of the changed resource
     * @param resourceId the ID of the changed resource
     * @param resourcePath the path of the request below the realm
     * @param representation the new state, or null
     * @param propagated the other resources whose read the change changed. Their versions are counted up already.
     */
    default void recordChange(Change change, ResourceType resourceType, String resourceId, String resourcePath,
            Object representation, List<PropagatedChange> propagated) {
        recordChanges(List.of(new ChangeRecord(change, resourceType, resourceId, resourcePath, representation,
                propagated)));
    }

    /**
     * Records successful changes in their order, as {@link #recordChange} does for each. Each change gets an admin
     * event and an entry of the kind {@code WRITE}. The propagated changes come after all those entries, with no
     * admin event. Each has the time and the change order of the first change that caused it, and names its entry in
     * {@code causeSequence}. A resource gets one propagated change, also when several of the changes changed its read.
     * A resource that one of the changes writes gets none, as the entry of that write names it.
     *
     * @param changes the changes
     */
    void recordChanges(List<ChangeRecord> changes);
}
