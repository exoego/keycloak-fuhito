package net.exoego.keycloak.fuhito.service;

import java.util.List;
import java.util.Map;

import net.exoego.keycloak.fuhito.domain.ChangeLogEntry;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.store.ChangeLogStore;
import net.exoego.keycloak.fuhito.store.ChangeOrderStore;

/**
 * Reads of the change log of one realm.
 */
public class ChangeLogService {

    private final ChangeLogStore store;
    private final ChangeOrderStore changeOrders;

    /**
     * Creates the service.
     *
     * @param store the storage of the change log of the realm
     * @param changeOrders the storage of change orders of the realm
     */
    public ChangeLogService(ChangeLogStore store, ChangeOrderStore changeOrders) {
        this.store = store;
        this.changeOrders = changeOrders;
    }

    /**
     * Lists the writes to the realm, oldest first.
     *
     * @param after the sequence number that the entries come after, or null for all
     * @param resourceType the type of the resource, for example {@code FUHITO_ORG_UNIT}, or null for every type
     * @param resourceId the ID of the resource, or null for every resource
     * @param first the number of entries to skip, or null for 0
     * @param max the largest number of entries to return, or null for 100. At most 1000 are returned.
     * @return the entries
     */
    public List<ChangeLogEntry> list(Long after, String resourceType, String resourceId, Integer first,
            Integer max) {
        Page page = Page.of(first, max);
        return store.list(after == null ? 0 : after, resourceType, resourceId, page.first(), page.max());
    }

    /**
     * Lists the writes that belong to one change order, oldest first.
     *
     * @param changeOrderId the ID of the change order
     * @param first the number of entries to skip, or null for 0
     * @param max the largest number of entries to return, or null for 100. At most 1000 are returned.
     * @return the entries
     * @throws FuhitoException when the realm has no such change order
     */
    public List<ChangeLogEntry> ofChangeOrder(String changeOrderId, Integer first, Integer max) {
        if (changeOrders.find(changeOrderId).isEmpty()) {
            throw FuhitoException.notFound("change_order.not_found", Map.of("id", changeOrderId));
        }
        Page page = Page.of(first, max);
        return store.listByChangeOrder(changeOrderId, page.first(), page.max());
    }
}
