package net.exoego.keycloak.fuhito.service;

import java.util.List;
import java.util.Map;

import net.exoego.keycloak.fuhito.domain.ChangeLogEntry;
import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.store.ChangeLogStore;
import net.exoego.keycloak.fuhito.store.ChangeOrderStore;

/**
 * Reads of the change log of one realm.
 */
public class ChangeLogService {

    private static final int DEFAULT_MAX = 100;

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
     * Lists the writes to one resource, oldest first.
     *
     * @param resourceType the type of the resource, for example {@code FUHITO_ORG_UNIT}
     * @param resourceId the ID of the resource
     * @param first the number of entries to skip, or null for 0
     * @param max the largest number of entries to return, or null for 100
     * @return the entries
     * @throws FuhitoException when the type or the ID is missing
     */
    public List<ChangeLogEntry> ofResource(String resourceType, String resourceId, Integer first, Integer max) {
        return store.listByResource(Checks.required("resourceType", resourceType),
                Checks.required("resourceId", resourceId), skip(first), limit(max));
    }

    /**
     * Lists the writes that belong to one change order, oldest first.
     *
     * @param changeOrderId the ID of the change order
     * @param first the number of entries to skip, or null for 0
     * @param max the largest number of entries to return, or null for 100
     * @return the entries
     * @throws FuhitoException when the realm has no such change order
     */
    public List<ChangeLogEntry> ofChangeOrder(String changeOrderId, Integer first, Integer max) {
        if (changeOrders.find(changeOrderId).isEmpty()) {
            throw FuhitoException.notFound("change_order.not_found", Map.of("id", changeOrderId));
        }
        return store.listByChangeOrder(changeOrderId, skip(first), limit(max));
    }

    private static int skip(Integer first) {
        return first == null ? 0 : Math.max(0, first);
    }

    private static int limit(Integer max) {
        return max == null ? DEFAULT_MAX : Math.max(0, max);
    }
}
