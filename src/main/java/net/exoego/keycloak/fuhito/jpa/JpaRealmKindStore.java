package net.exoego.keycloak.fuhito.jpa;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import net.exoego.keycloak.fuhito.domain.RealmKind;
import net.exoego.keycloak.fuhito.store.RealmKindStore;

/**
 * Stores the kinds of reference data that one realm added, in the database of Keycloak.
 */
public class JpaRealmKindStore implements RealmKindStore {

    private final EntityManager em;
    private final String realmId;

    /**
     * Creates the store.
     *
     * @param em the entity manager of the request
     * @param realmId the ID of the realm
     */
    public JpaRealmKindStore(EntityManager em, String realmId) {
        this.em = em;
        this.realmId = realmId;
    }

    @Override
    public List<RealmKind> list() {
        return em.createQuery("select k from RealmKindEntity k where k.realmId = :realmId order by k.code",
                        RealmKindEntity.class)
                .setParameter("realmId", realmId)
                .getResultStream()
                .map(JpaRealmKindStore::toDomain)
                .toList();
    }

    @Override
    public Optional<RealmKind> findByCode(String code) {
        return em.createQuery("select k from RealmKindEntity k where k.realmId = :realmId and k.code = :code",
                        RealmKindEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("code", code)
                .getResultStream()
                .findFirst()
                .map(JpaRealmKindStore::toDomain);
    }

    @Override
    public void insert(RealmKind kind) {
        RealmKindEntity entity = new RealmKindEntity();
        entity.id = kind.id();
        entity.realmId = realmId;
        entity.code = kind.code();
        entity.name = kind.name();
        entity.names = kind.names();
        entity.version = kind.version();
        em.persist(entity);
        em.flush();
    }

    @Override
    public void update(RealmKind kind) {
        RealmKindEntity entity = Rows.require(em, RealmKindEntity.class, kind.id(), realmId, found -> found.realmId);
        entity.name = kind.name();
        entity.names = kind.names();
        // JPA counts up the version when it writes the row. A read after the write gives the new one.
        em.flush();
    }

    @Override
    public void delete(String id) {
        RealmKindEntity entity = em.find(RealmKindEntity.class, id);
        if (entity != null && entity.realmId.equals(realmId)) {
            em.remove(entity);
            em.flush();
        }
    }

    static RealmKind toDomain(RealmKindEntity e) {
        return new RealmKind(e.id, e.code, e.name, e.names, e.version);
    }
}
