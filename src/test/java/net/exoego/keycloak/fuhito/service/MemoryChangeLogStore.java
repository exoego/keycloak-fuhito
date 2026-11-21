package net.exoego.keycloak.fuhito.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import net.exoego.keycloak.fuhito.domain.ChangeLogEntry;
import net.exoego.keycloak.fuhito.domain.ResourceType;
import net.exoego.keycloak.fuhito.store.ChangeLogStore;

class MemoryChangeLogStore implements ChangeLogStore {

    final List<ChangeLogEntry> entries = new ArrayList<>();
    private long lastSequence;
    private long expiredSequence;

    @Override
    public void appendAll(List<ChangeLogEntry> added) {
        added.forEach(entry -> entries.add(new ChangeLogEntry(entry.id(), ++lastSequence, entry.recordedAt(),
                entry.kind(), entry.actorUserId(), entry.resourceType(), entry.resourceId(), entry.operation(),
                entry.resourcePath(), entry.changeOrderId(), entry.cause(), entry.causeSequence())));
    }

    @Override
    public List<ChangeLogEntry> list(long after, ResourceType resourceType, String resourceId,
            ChangeLogEntry.Kind kind, int first, int max) {
        return entries.stream()
                .filter(e -> e.sequence() > after)
                .filter(e -> resourceType == null || e.resourceType().equals(resourceType))
                .filter(e -> resourceId == null || e.resourceId().equals(resourceId))
                .filter(e -> kind == null || e.kind() == kind)
                .skip(first).limit(max).toList();
    }

    @Override
    public long count(ResourceType resourceType, String resourceId, ChangeLogEntry.Kind kind) {
        return list(0, resourceType, resourceId, kind, 0, Integer.MAX_VALUE).size();
    }

    @Override
    public Bounds bounds() {
        return new Bounds(lastSequence, expiredSequence);
    }

    @Override
    public long removePropagated(Instant before) {
        List<ChangeLogEntry> old = entries.stream()
                .filter(e -> e.kind() == ChangeLogEntry.Kind.PROPAGATED && e.recordedAt().isBefore(before))
                .toList();
        old.forEach(e -> expiredSequence = Math.max(expiredSequence, e.sequence()));
        entries.removeAll(old);
        return old.size();
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
