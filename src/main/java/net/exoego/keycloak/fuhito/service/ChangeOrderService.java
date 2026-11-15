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
import net.exoego.keycloak.fuhito.store.ChangeLogStore;
import net.exoego.keycloak.fuhito.store.ChangeOrderStore;
import net.exoego.keycloak.fuhito.store.UserDirectory;

/**
 * Operations on the change orders of one realm.
 */
public class ChangeOrderService {

    private final ChangeOrderStore store;
    private final ChangeLogStore changeLog;
    private final UserDirectory users;
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param store the storage of change orders of the realm
     * @param changeLog the storage of the change log of the realm
     * @param users the users of the realm
     * @param clock the clock for the time of creation
     */
    public ChangeOrderService(ChangeOrderStore store, ChangeLogStore changeLog, UserDirectory users, Clock clock) {
        this.store = store;
        this.changeLog = changeLog;
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
                Instant.now(clock).truncatedTo(ChronoUnit.MILLIS), null);
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
        ChangeOrder before = get(id);
        ChangeOrder order = checked(id, request, before.createdAt(), before);
        store.update(order);
        return order;
    }

    /**
     * Records that a change order was announced, now. This cannot be taken back.
     * It is a fact and not a planned date. So a step that someone forgot leaves the order as not announced.
     *
     * @param id the ID
     * @param byUserId the ID of the Keycloak user who records the announcement
     * @return the changed order
     * @throws FuhitoException when the order does not exist or was announced before
     */
    public ChangeOrder announce(String id, String byUserId) {
        ChangeOrder before = get(id);
        if (before.announcedAt() != null) {
            throw FuhitoException.conflict("change_order.already_announced",
                    Map.of("id", id, "announcedAt", before.announcedAt().toString()));
        }
        ChangeOrder order = new ChangeOrder(id, before.number(), before.kind(), before.title(), before.note(),
                before.orderDate(), before.effectiveDate(), before.approverUserId(), before.requesterUserId(),
                before.externalReference(), before.createdAt(), Instant.now(clock).truncatedTo(ChronoUnit.MILLIS),
                byUserId);
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
     * @param first the number of orders to skip, or null for 0
     * @param max the largest number of orders to return, or null for 100. At most 1000 are returned.
     * @return the orders
     */
    public List<ChangeOrder> list(String from, String to, Integer first, Integer max) {
        Page page = Page.of(first, max);
        return store.list(Checks.optionalDate("from", from), Checks.optionalDate("to", to), page.first(),
                page.max());
    }

    /**
     * Deletes a change order. A change order with writes in the change log cannot be deleted.
     *
     * @param id the ID
     * @throws FuhitoException when the order does not exist or has writes
     */
    public void delete(String id) {
        get(id);
        if (changeLog.existsForChangeOrder(id)) {
            throw FuhitoException.conflict("change_order.in_use", Map.of("id", id));
        }
        store.delete(id);
    }

    // A replace keeps the announcement. Only the operation for it sets it.
    private ChangeOrder checked(String id, ChangeOrderValues request, Instant createdAt, ChangeOrder before) {
        Checks.required("body", request);
        String number = Checks.optionalCode("number", request.number());
        ChangeOrder order = new ChangeOrder(id, number, Checks.optionalCode("kind", request.kind()),
                Checks.name("title", request.title()), request.note(),
                Checks.optionalDate("orderDate", request.orderDate()),
                Checks.date("effectiveDate", request.effectiveDate()),
                knownUser("approverUserId", request.approverUserId()),
                knownUser("requesterUserId", request.requesterUserId()), request.externalReference(), createdAt,
                before == null ? null : before.announcedAt(), before == null ? null : before.announcedByUserId());
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
