package net.exoego.keycloak.fuhito.service;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.exoego.keycloak.fuhito.domain.ChangeOrder;
import net.exoego.keycloak.fuhito.store.ChangeOrderStore;

final class MemoryChangeOrderStore implements ChangeOrderStore {

    final Map<String, ChangeOrder> orders = new LinkedHashMap<>();

    @Override
    public Optional<ChangeOrder> find(String id) {
        return Optional.ofNullable(orders.get(id));
    }

    @Override
    public Optional<ChangeOrder> findByNumber(String number) {
        return orders.values().stream().filter(o -> number.equals(o.number())).findFirst();
    }

    @Override
    public List<ChangeOrder> list(LocalDate from, LocalDate to) {
        return orders.values().stream()
                .filter(o -> (from == null || !o.effectiveDate().isBefore(from))
                        && (to == null || o.effectiveDate().isBefore(to)))
                .sorted(Comparator.comparing(ChangeOrder::effectiveDate).reversed())
                .toList();
    }

    @Override
    public void insert(ChangeOrder order) {
        orders.put(order.id(), order);
    }

    @Override
    public void update(ChangeOrder order) {
        orders.put(order.id(), order);
    }

    @Override
    public void delete(String id) {
        orders.remove(id);
    }
}
