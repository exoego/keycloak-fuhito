package net.exoego.keycloak.fuhito.service;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import net.exoego.keycloak.fuhito.store.ProjectedMemberStore;

/**
 * The users that the projection added to organizations, in memory. Each record has a number of its own.
 */
final class MemoryProjectedMemberStore implements ProjectedMemberStore {

    final Map<String, Map<String, Integer>> records = new TreeMap<>();
    private int next = 1;

    @Override
    public void record(String organizationId, String userId) {
        // The table has a unique key on the organization and the user.
        if (records.computeIfAbsent(organizationId, id -> new TreeMap<>()).putIfAbsent(userId, next++) != null) {
            throw new IllegalStateException("recorded twice: " + userId);
        }
    }

    @Override
    public void forget(String organizationId, String userId) {
        Map<String, Integer> users = records.get(organizationId);
        if (users != null) {
            users.remove(userId);
            if (users.isEmpty()) {
                records.remove(organizationId);
            }
        }
    }

    @Override
    public void forgetOrganization(String organizationId) {
        records.remove(organizationId);
    }

    @Override
    public Set<String> recorded(String organizationId, Collection<String> userIds) {
        Set<String> result = new HashSet<>(users(organizationId));
        result.retainAll(userIds);
        return result;
    }

    @Override
    public List<String> recordedAfter(String organizationId, String afterUserId, int max) {
        return users(organizationId).stream()
                .filter(userId -> afterUserId == null || userId.compareTo(afterUserId) > 0)
                .limit(max)
                .toList();
    }

    @Override
    public List<String> organizations() {
        return List.copyOf(records.keySet());
    }

    Set<String> users(String organizationId) {
        return records.getOrDefault(organizationId, Map.of()).keySet();
    }
}
