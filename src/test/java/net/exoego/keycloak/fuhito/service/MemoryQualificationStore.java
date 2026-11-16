package net.exoego.keycloak.fuhito.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import net.exoego.keycloak.fuhito.domain.Qualification;
import net.exoego.keycloak.fuhito.store.QualificationStore;

/**
 * The qualifications of one realm, in memory.
 */
final class MemoryQualificationStore implements QualificationStore {
    final List<Qualification> rows = new ArrayList<>();

    @Override
    public Optional<Qualification> find(String id) {
        return rows.stream().filter(q -> q.id().equals(id)).findFirst();
    }

    @Override
    public List<Qualification> findByUser(String userId) {
        return rows.stream().filter(q -> userId.equals(q.userId()))
                .sorted(Comparator.comparing(q -> q.period().from())).toList();
    }

    @Override
    public List<Qualification> findByPerson(String personId) {
        return rows.stream().filter(q -> q.personId().equals(personId)).toList();
    }

    @Override
    public Optional<Qualification> findByExternalId(String source, String externalId) {
        return rows.stream()
                .filter(q -> source.equals(q.source()) && externalId.equals(q.externalId()))
                .findFirst();
    }

    @Override
    public void insert(Qualification qualification) {
        rows.add(qualification);
    }

    @Override
    public void update(Qualification qualification) {
        rows.replaceAll(q -> q.id().equals(qualification.id()) ? qualification : q);
    }

    @Override
    public void delete(String id) {
        rows.removeIf(q -> q.id().equals(id));
    }

    @Override
    public List<Qualification> search(Search search, String afterId, int first, int max) {
        return rows.stream()
                .filter(q -> search.kindId() == null || search.kindId().equals(q.kindId()))
                .filter(q -> search.date() == null || q.period().contains(search.date()))
                .filter(q -> afterId == null || q.id().compareTo(afterId) > 0)
                .sorted(Comparator.comparing(Qualification::id))
                .skip(first).limit(max).toList();
    }

    @Override
    public long count(Search search) {
        return search(search, null, 0, Integer.MAX_VALUE).size();
    }
}
