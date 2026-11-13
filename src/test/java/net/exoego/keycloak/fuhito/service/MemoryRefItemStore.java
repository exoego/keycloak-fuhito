package net.exoego.keycloak.fuhito.service;

import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.domain.RefKind;
import net.exoego.keycloak.fuhito.store.RefItemStore;

final class MemoryRefItemStore implements RefItemStore {
    private final Map<String, RefItem> items = new LinkedHashMap<>();
    final Set<String> inUse = new HashSet<>();

    @Override
    public List<RefItem> list(RefKind kind, boolean includeInactive) {
        return items.values().stream()
                .filter(i -> i.kind() == kind && (includeInactive || i.active()))
                .sorted(Comparator.comparingInt(RefItem::displayOrder).thenComparing(RefItem::code))
                .toList();
    }

    @Override
    public Optional<RefItem> find(String id) {
        return Optional.ofNullable(items.get(id));
    }

    @Override
    public Optional<RefItem> findByCode(RefKind kind, String code) {
        return items.values().stream().filter(i -> i.kind() == kind && i.code().equals(code)).findFirst();
    }

    @Override
    public boolean isInUse(String id) {
        return inUse.contains(id);
    }

    @Override
    public void insert(RefItem item) {
        items.put(item.id(), item);
    }

    @Override
    public void update(RefItem item) {
        items.put(item.id(), item);
    }

    @Override
    public void delete(String id) {
        items.remove(id);
    }
}
