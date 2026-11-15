package net.exoego.keycloak.fuhito.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.exoego.keycloak.fuhito.domain.ExternalId;
import net.exoego.keycloak.fuhito.store.ExternalIdStore;

final class MemoryExternalIdStore implements ExternalIdStore {

    /** The own pairs of the resources, by kind and ID. A resource that is here exists. */
    final Map<ExternalId.Kind, Map<String, ExternalId.Pair>> pairs = new HashMap<>();
    final List<ExternalId> rows = new ArrayList<>();

    void resource(ExternalId.Kind kind, String id, String source, String externalId) {
        pairs.computeIfAbsent(kind, k -> new HashMap<>()).put(id, new ExternalId.Pair(source, externalId));
    }

    @Override
    public Optional<ExternalId.Pair> pairOf(ExternalId.Kind kind, String resourceId) {
        return Optional.ofNullable(pairs.getOrDefault(kind, Map.of()).get(resourceId));
    }

    @Override
    public List<String> withPair(ExternalId.Kind kind, String source, String externalId) {
        return pairs.getOrDefault(kind, Map.of()).entrySet().stream()
                .filter(e -> source.equals(e.getValue().source()) && externalId.equals(e.getValue().externalId()))
                .map(Map.Entry::getKey)
                .toList();
    }

    @Override
    public List<ExternalId> ofResource(ExternalId.Kind kind, String resourceId) {
        return rows.stream().filter(r -> r.kind() == kind && r.resourceId().equals(resourceId))
                .sorted(Comparator.comparing(ExternalId::source)).toList();
    }

    @Override
    public Optional<ExternalId> find(ExternalId.Kind kind, String source, String externalId) {
        return rows.stream()
                .filter(r -> r.kind() == kind && r.source().equals(source) && r.externalId().equals(externalId))
                .findFirst();
    }

    @Override
    public void insert(ExternalId externalId) {
        rows.add(externalId);
    }

    @Override
    public void update(ExternalId externalId) {
        rows.replaceAll(r -> r.id().equals(externalId.id()) ? externalId : r);
    }

    @Override
    public void delete(String id) {
        rows.removeIf(r -> r.id().equals(id));
    }
}
