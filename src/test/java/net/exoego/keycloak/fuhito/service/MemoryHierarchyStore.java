package net.exoego.keycloak.fuhito.service;

import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.exoego.keycloak.fuhito.domain.Hierarchy;
import net.exoego.keycloak.fuhito.store.HierarchyStore;

final class MemoryHierarchyStore implements HierarchyStore {

    final Map<String, Hierarchy> hierarchies = new LinkedHashMap<>();
    final Set<String> withLinks = new HashSet<>();

    @Override
    public String defaultId() {
        return findByCode(Hierarchy.DEFAULT_CODE).map(Hierarchy::id).orElseGet(() -> {
            insert(new Hierarchy("default-hierarchy", Hierarchy.DEFAULT_CODE, "Default", 1));
            return "default-hierarchy";
        });
    }

    @Override
    public List<Hierarchy> list(int first, int max) {
        return hierarchies.values().stream().sorted(Comparator.comparing(Hierarchy::code)).skip(first).limit(max)
                .toList();
    }

    @Override
    public long count() {
        return hierarchies.size();
    }

    @Override
    public Optional<Hierarchy> find(String id) {
        return Optional.ofNullable(hierarchies.get(id));
    }

    @Override
    public Optional<Hierarchy> findByCode(String code) {
        return hierarchies.values().stream().filter(h -> h.code().equals(code)).findFirst();
    }

    @Override
    public void insert(Hierarchy hierarchy) {
        hierarchies.put(hierarchy.id(), hierarchy);
    }

    @Override
    public void update(Hierarchy hierarchy) {
        hierarchies.put(hierarchy.id(), hierarchy);
    }

    @Override
    public void delete(String id) {
        hierarchies.remove(id);
    }

    @Override
    public boolean hasLinks(String id) {
        return withLinks.contains(id);
    }
}
