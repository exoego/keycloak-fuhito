package net.exoego.keycloak.fuhito.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import net.exoego.keycloak.fuhito.domain.RealmKind;
import net.exoego.keycloak.fuhito.store.RealmKindStore;

/**
 * The kinds of reference data of one realm, in memory.
 */
final class MemoryRealmKindStore implements RealmKindStore {

    private final List<RealmKind> rows = new ArrayList<>();

    @Override
    public List<RealmKind> list() {
        return rows.stream().sorted(Comparator.comparing(RealmKind::code)).toList();
    }

    @Override
    public Optional<RealmKind> findByCode(String code) {
        return rows.stream().filter(kind -> kind.code().equals(code)).findFirst();
    }

    @Override
    public void insert(RealmKind kind) {
        rows.add(kind);
    }

    @Override
    public void update(RealmKind kind) {
        rows.replaceAll(stored -> stored.id().equals(kind.id()) ? kind : stored);
    }

    @Override
    public void delete(String id) {
        rows.removeIf(kind -> kind.id().equals(id));
    }
}
