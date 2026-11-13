package net.exoego.keycloak.fuhito.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.exoego.keycloak.fuhito.domain.ChangeOrder;
import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.store.ChangeOrderStore;
import net.exoego.keycloak.fuhito.store.UserDirectory;

/**
 * Operations on the change orders of one realm.
 */
public class ChangeOrderService {

    private final ChangeOrderStore store;
    private final UserDirectory users;
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param store the storage of change orders of the realm
     * @param users the users of the realm
     * @param clock the clock for the time of creation
     */
    public ChangeOrderService(ChangeOrderStore store, UserDirectory users, Clock clock) {
        this.store = store;
        this.users = users;
        this.clock = clock;
    }

    /**
     * Creates a change order.
     *
     * @param request the values of the request
     * @return the new order
     * @throws FuhitoException when a value is bad, a user does not exist, or the number is taken
     */
    public ChangeOrder create(ChangeOrderValues request) {
        // The database keeps microseconds, so the returned time is cut to what a later read returns.
        ChangeOrder order = checked(UUID.randomUUID().toString(), request,
                Instant.now(clock).truncatedTo(ChronoUnit.MILLIS));
        store.insert(order);
        return order;
    }

    /**
     * Replaces the values of a change order.
     *
     * @param id the ID
     * @param request the new values
     * @return the changed order
     * @throws FuhitoException when the order does not exist, a value is bad, or the number is taken
     */
    public ChangeOrder update(String id, ChangeOrderValues request) {
        ChangeOrder order = checked(id, request, get(id).createdAt());
        store.update(order);
        return order;
    }

    /**
     * Returns a change order.
     *
     * @param id the ID
     * @return the order
     * @throws FuhitoException when the realm has no such order
     */
    public ChangeOrder get(String id) {
        return store.find(id)
                .orElseThrow(() -> FuhitoException.notFound("change_order.not_found", Map.of("id", id)));
    }

    /**
     * Lists the change orders with an effective date in a range, newest effective date first.
     *
     * @param from the first effective date to return, as {@code YYYY-MM-DD}, or null
     * @param to the first effective date not to return, as {@code YYYY-MM-DD}, or null
     * @return the orders
     */
    public List<ChangeOrder> list(String from, String to) {
        return store.list(Checks.optionalDate("from", from), Checks.optionalDate("to", to));
    }

    /**
     * Deletes a change order.
     *
     * @param id the ID
     * @throws FuhitoException when the order does not exist
     */
    public void delete(String id) {
        get(id);
        store.delete(id);
    }

    private ChangeOrder checked(String id, ChangeOrderValues request, Instant createdAt) {
        Checks.required("body", request);
        String number = Checks.optionalCode("number", request.number());
        ChangeOrder order = new ChangeOrder(id, number, Checks.optionalCode("kind", request.kind()),
                Checks.name("title", request.title()), request.note(),
                Checks.optionalDate("orderDate", request.orderDate()),
                Checks.date("effectiveDate", request.effectiveDate()),
                knownUser("approverUserId", request.approverUserId()),
                knownUser("requesterUserId", request.requesterUserId()), request.externalReference(), createdAt);
        if (number != null && store.findByNumber(number).filter(other -> !other.id().equals(id)).isPresent()) {
            throw FuhitoException.conflict("change_order.number_taken", Map.of("number", number));
        }
        return order;
    }

    private String knownUser(String field, String userId) {
        if (userId != null && !users.exists(userId)) {
            throw FuhitoException.invalid("change_order.user_unknown", Map.of("field", field, "userId", userId));
        }
        return userId;
    }
}
