package net.exoego.keycloak.fuhito.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.domain.RefItemName;
import net.exoego.keycloak.fuhito.store.RefItemStore;

final class MemoryRefItemStore implements RefItemStore {
    private final Map<String, RefItem> items = new LinkedHashMap<>();
    private final Map<String, List<RefItemName>> names = new LinkedHashMap<>();
    final Set<String> inUse = new HashSet<>();

    @Override
    public List<RefItem> list(String kind, boolean includeInactive) {
        return items.values().stream()
                .filter(i -> i.kind().equals(kind) && (includeInactive || i.active()))
                .sorted(Comparator.comparingInt(RefItem::displayOrder).thenComparing(RefItem::code))
                .map(i -> i.withNames(names(i.id()).getLast()))
                .toList();
    }

    @Override
    public List<RefItem> list(String kind, boolean includeInactive, LocalDate date, int first, int max) {
        return list(kind, includeInactive).stream()
                .skip(first)
                .limit(max)
                .map(i -> i.withNames(RefItemName.on(names(i.id()), date)))
                .toList();
    }

    @Override
    public long count(String kind, boolean includeInactive) {
        return list(kind, includeInactive).size();
    }

    @Override
    public Optional<RefItem> find(String id) {
        return Optional.ofNullable(items.get(id)).map(i -> i.withNames(names(id).getLast()));
    }

    @Override
    public Optional<RefItem> find(String id, LocalDate date) {
        return Optional.ofNullable(items.get(id)).map(i -> i.withNames(RefItemName.on(names(id), date)));
    }

    @Override
    public Optional<RefItem> findByCode(String kind, String code) {
        return byCode(kind, code).flatMap(i -> find(i.id()));
    }

    @Override
    public Optional<RefItem> findByCode(String kind, String code, LocalDate date) {
        return byCode(kind, code).flatMap(i -> find(i.id(), date));
    }

    private Optional<RefItem> byCode(String kind, String code) {
        return items.values().stream().filter(i -> i.kind().equals(kind) && i.code().equals(code)).findFirst();
    }

    @Override
    public List<RefItemName> names(String id) {
        return names.getOrDefault(id, List.of()).stream()
                .sorted(Comparator.comparing(RefItemName::validFrom, Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
    }

    /** The unit types that make an employer a legal entity. A test of employers sets them. */
    final Set<String> typesOfEmployers = new HashSet<>();

    @Override
    public boolean isTypeOfAnEmployer(String id) {
        return typesOfEmployers.contains(id);
    }

    @Override
    public boolean isInUse(String id) {
        return inUse.contains(id);
    }

    @Override
    public void insert(RefItem item, List<RefItemName> periods) {
        items.put(item.id(), item);
        names.put(item.id(), new ArrayList<>(periods));
    }

    @Override
    public void update(RefItem item) {
        items.put(item.id(), item);
    }

    @Override
    public void insertNames(RefItemName period) {
        names.get(period.refItemId()).add(period);
    }

    @Override
    public void updateNames(RefItemName period) {
        List<RefItemName> stored = names.get(period.refItemId());
        stored.replaceAll(n -> n.id().equals(period.id()) ? period : n);
    }

    @Override
    public void delete(String id) {
        items.remove(id);
        names.remove(id);
    }
}
