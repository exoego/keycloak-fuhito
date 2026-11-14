package net.exoego.keycloak.fuhito.store;

import java.util.List;

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
     * Lists the entries of one change order, oldest first.
     *
     * @param changeOrderId the ID of the change order
     * @param first the number of entries to skip
     * @param max the largest number of entries to return
     * @return the entries
     */
    List<ChangeLogEntry> listByChangeOrder(String changeOrderId, int first, int max);

    /**
     * Tells whether any entry belongs to a change order.
     *
     * @param changeOrderId the ID of the change order
     * @return true when the change order has entries
     */
    boolean existsForChangeOrder(String changeOrderId);
}
