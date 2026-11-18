package net.exoego.keycloak.fuhito.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.exoego.keycloak.fuhito.domain.ChangeLogEntry;
import net.exoego.keycloak.fuhito.store.ChangeLogStore;

/**
 * The change log of one realm, in memory. The sequence number of an entry is its position.
 */
final class MemoryChangeLogStore implements ChangeLogStore {
    final List<ChangeLogEntry> entries = new ArrayList<>();

    @Override
    public void append(ChangeLogEntry entry) {
        entries.add(new ChangeLogEntry(entry.id(), entries.size() + 1, entry.recordedAt(), entry.actorUserId(),
                entry.resourceType(), entry.resourceId(), entry.operation(), entry.resourcePath(),
                entry.changeOrderId(), entry.representation()));
    }

    @Override
    public List<ChangeLogEntry> list(long after, String resourceType, String resourceId, int first, int max) {
        return entries.stream()
                .filter(e -> e.sequence() > after)
                .filter(e -> resourceType == null || e.resourceType().equals(resourceType))
                .filter(e -> resourceId == null || e.resourceId().equals(resourceId))
                .skip(first).limit(max).toList();
    }

    @Override
    public long lastSequence() {
        return entries.size();
    }

    @Override
    public Map<String, String> statesUpTo(String resourceType, Collection<String> resourceIds, long upTo) {
        Map<String, String> result = new HashMap<>();
        entries.stream()
                .filter(e -> e.sequence() <= upTo && e.resourceType().equals(resourceType)
                        && resourceIds.contains(e.resourceId()) && e.representation() != null)
                .forEach(e -> result.put(e.resourceId(), e.representation()));
        return result;
    }

    @Override
    public long count(String resourceType, String resourceId) {
        return list(0, resourceType, resourceId, 0, Integer.MAX_VALUE).size();
    }

    @Override
    public List<ChangeLogEntry> listByChangeOrder(String changeOrderId, int first, int max) {
        return entries.stream().filter(e -> changeOrderId.equals(e.changeOrderId())).skip(first).limit(max)
                .toList();
    }

    @Override
    public long countByChangeOrder(String changeOrderId) {
        return listByChangeOrder(changeOrderId, 0, Integer.MAX_VALUE).size();
    }

    @Override
    public boolean existsForChangeOrder(String changeOrderId) {
        return entries.stream().anyMatch(e -> changeOrderId.equals(e.changeOrderId()));
    }
}
