package net.exoego.keycloak.fuhito.store;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import net.exoego.keycloak.fuhito.domain.ChangeOrder;

/**
 * Storage of the change orders of one realm.
 */
public interface ChangeOrderStore {

    /**
     * Finds a change order by ID.
     *
     * @param id the ID
     * @return the order, or empty when the realm has no such order
     */
    Optional<ChangeOrder> find(String id);

    /**
     * Finds a change order by number.
     *
     * @param number the number
     * @return the order, or empty when the realm has no such order
     */
    Optional<ChangeOrder> findByNumber(String number);

    /**
     * Lists the change orders with an effective date in a range, newest effective date first.
     *
     * @param from the first effective date to return, or null for no lower bound
     * @param to the first effective date not to return, or null for no upper bound
     * @return the orders
     */
    List<ChangeOrder> list(LocalDate from, LocalDate to, int first, int max);

    /**
     * Stores a new change order.
     *
     * @param order the order
     */
    void insert(ChangeOrder order);

    /**
     * Stores the new state of an existing change order.
     *
     * @param order the order
     */
    void update(ChangeOrder order);

    /**
     * Removes a change order.
     *
     * @param id the ID
     */
    void delete(String id);
}
