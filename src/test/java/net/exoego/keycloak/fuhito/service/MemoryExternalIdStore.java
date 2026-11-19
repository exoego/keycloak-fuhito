package net.exoego.keycloak.fuhito.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import net.exoego.keycloak.fuhito.domain.ExternalId;
import net.exoego.keycloak.fuhito.store.ExternalIdStore;

final class MemoryExternalIdStore implements ExternalIdStore {

    /** The systems that made the resources, by kind and ID. A resource that is here exists. */
    final Map<ExternalId.Kind, Map<String, ExternalId.Origin>> origins = new HashMap<>();
    /** The stores of records that a test shares with this store, by kind. */
    private final Map<ExternalId.Kind, Function<String, Optional<ExternalId.Origin>>> records = new HashMap<>();
    final List<ExternalId> rows = new ArrayList<>();
    /** The number of reads of the IDs of many resources. */
    int batchReads;
    /** The number of reads of the resource that one ID of a system names. */
    int singleFinds;
    /** The number of reads of the resources that many IDs of a system name. */
    int manyFinds;

    void resource(ExternalId.Kind kind, String id, String source) {
        origins.computeIfAbsent(kind, k -> new HashMap<>()).put(id, new ExternalId.Origin(source));
    }

    /** The resource that an ID of a system names, as an import finds it. */
    Optional<String> idOf(ExternalId.Kind kind, String source, String externalId) {
        return find(kind, source, externalId).map(ExternalId::resourceId);
    }

    /** Reads the records of a kind from a store of records, by the ID of the record. */
    void records(ExternalId.Kind kind, Function<String, Optional<ExternalId.Origin>> originOf) {
        records.put(kind, originOf);
    }

    @Override
    public Optional<ExternalId.Origin> originOf(ExternalId.Kind kind, String resourceId) {
        return Optional.ofNullable(origins.getOrDefault(kind, Map.of()).get(resourceId))
                .or(() -> records.getOrDefault(kind, id -> Optional.empty()).apply(resourceId));
    }

    @Override
    public List<ExternalId> ofResource(ExternalId.Kind kind, String resourceId) {
        return rows.stream().filter(r -> r.kind() == kind && r.resourceId().equals(resourceId))
                .sorted(Comparator.comparing(ExternalId::source)).toList();
    }

    @Override
    public List<ExternalId> ofResources(ExternalId.Kind kind, Collection<String> resourceIds) {
        batchReads++;
        return rows.stream().filter(r -> r.kind() == kind && resourceIds.contains(r.resourceId())).toList();
    }

    @Override
    public Optional<ExternalId> find(ExternalId.Kind kind, String source, String externalId) {
        singleFinds++;
        return rows.stream()
                .filter(r -> r.kind() == kind && r.source().equals(source) && r.externalId().equals(externalId))
                .findFirst();
    }

    @Override
    public List<ExternalId> findAll(ExternalId.Kind kind, String source, Collection<String> externalIds) {
        manyFinds++;
        return rows.stream()
                .filter(r -> r.kind() == kind && r.source().equals(source) && externalIds.contains(r.externalId()))
                .toList();
    }

    @Override
    public List<ExternalId> list(String afterId, int max) {
        return rows.stream()
                .filter(row -> afterId == null || row.id().compareTo(afterId) > 0)
                .sorted(Comparator.comparing(ExternalId::id))
                .limit(max)
                .toList();
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
    public void moveAll(Map<ExternalId.Kind, Map<String, String>> newResourceIds) {
        rows.replaceAll(r -> {
            String moved = newResourceIds.getOrDefault(r.kind(), Map.of()).get(r.resourceId());
            return moved == null ? r
                    : new ExternalId(r.id(), r.kind(), moved, r.source(), r.externalId(), r.version() + 1);
        });
    }

    @Override
    public void delete(String id) {
        rows.removeIf(r -> r.id().equals(id));
    }
}
