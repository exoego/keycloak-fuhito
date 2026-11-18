package net.exoego.keycloak.fuhito.store;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import net.exoego.keycloak.fuhito.domain.ChangeLogEntry;

/**
 * Storage of the change log of one realm.
 */
public interface ChangeLogStore {

    /**
     * Adds an entry. The store gives it the next sequence number of the realm.
     *
     * @param entry the entry. Its sequence number is ignored.
     */
    void append(ChangeLogEntry entry);

    /**
     * Lists the entries of the realm, oldest first.
     *
     * @param after the sequence number that the entries come after, or 0 for all
     * @param resourceType the type of the resource, or null for every type
     * @param resourceId the ID of the resource, or null for every resource
     * @param first the number of entries to skip
     * @param max the largest number of entries to return
     * @return the entries
     */
    List<ChangeLogEntry> list(long after, String resourceType, String resourceId, int first, int max);

    /**
     * Returns the sequence number of the newest entry of the realm.
     * A writer holds the number until its transaction ends, so every entry up to it can be read.
     *
     * @return the number, or 0 when the realm has no entry
     */
    long lastSequence();

    /**
     * Returns, for some resources, the state that the newest entry up to a sequence number recorded.
     * An entry with no state, such as a removal, is skipped.
     *
     * @param resourceType the type of the resources
     * @param resourceIds the IDs of the resources
     * @param upTo the sequence number of the last entry to look at
     * @return the state as JSON by the ID of the resource. A resource with no such entry is left out.
     */
    Map<String, String> statesUpTo(String resourceType, Collection<String> resourceIds, long upTo);

    /**
     * Counts the entries of the realm.
     *
     * @param resourceType the type of the resource, or null for every type
     * @param resourceId the ID of the resource, or null for every resource
     * @return the number of entries
     */
    long count(String resourceType, String resourceId);

    /**
     * Lists the entries of one change order, oldest first.
     *
     * @param changeOrderId the ID of the change order
     * @param first the number of entries to skip
     * @param max the largest number of entries to return
     * @return the entries
     */
    List<ChangeLogEntry> listByChangeOrder(String changeOrderId, int first, int max);

    /**
     * Counts the entries of one change order.
     *
     * @param changeOrderId the ID of the change order
     * @return the number of entries
     */
    long countByChangeOrder(String changeOrderId);

    /**
     * Tells whether any entry belongs to a change order.
     *
     * @param changeOrderId the ID of the change order
     * @return true when the change order has entries
     */
    boolean existsForChangeOrder(String changeOrderId);
}
