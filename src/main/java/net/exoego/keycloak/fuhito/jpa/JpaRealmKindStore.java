package net.exoego.keycloak.fuhito.jpa;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import net.exoego.keycloak.fuhito.domain.Localized;
import net.exoego.keycloak.fuhito.domain.RealmKind;
import net.exoego.keycloak.fuhito.domain.RefKindName;
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
        return withNames(em, realmId, em.createQuery(
                        "select k from RealmKindEntity k where k.realmId = :realmId order by k.code",
                        RealmKindEntity.class)
                .setParameter("realmId", realmId)
                .getResultList());
    }

    @Override
    public Optional<RealmKind> findByCode(String code) {
        return withNames(em, realmId, em.createQuery(
                        "select k from RealmKindEntity k where k.realmId = :realmId and k.code = :code",
                        RealmKindEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("code", code)
                .getResultList())
                .stream()
                .findFirst();
    }

    @Override
    public void insert(RealmKind kind) {
        RealmKindEntity entity = new RealmKindEntity();
        entity.id = kind.id();
        entity.realmId = realmId;
        entity.code = kind.code();
        entity.version = kind.version();
        entity.counted = true;
        em.persist(entity);
        RefKindNames.insert(em, realmId, kind.id(), kind.names());
        em.flush();
    }

    @Override
    public void update(RealmKind kind) {
        RealmKindEntity entity = Rows.require(em, RealmKindEntity.class, kind.id(), realmId, found -> found.realmId);
        RefKindNames.replace(em, realmId, kind.id(), kind.names());
        entity.countUp();
    }

    @Override
    public void delete(String id) {
        RealmKindEntity entity = em.find(RealmKindEntity.class, id);
        if (entity != null && entity.realmId.equals(realmId)) {
            RefKindNames.delete(em, realmId, id);
            em.remove(entity);
            em.flush();
        }
    }

    /**
     * Reads the names of kinds in one query, and gives each kind its names.
     *
     * @param em the entity manager
     * @param realmId the ID of the realm
     * @param entities the rows of the kinds
     * @return the kinds in the same order
     */
    static List<RealmKind> withNames(EntityManager em, String realmId, List<RealmKindEntity> entities) {
        Map<String, Localized<RefKindName>> names = RefKindNames.of(em, realmId,
                entities.stream().map(e -> e.id).toList());
        return entities.stream()
                .map(e -> new RealmKind(e.id, e.code, names.get(e.id), e.version))
                .toList();
    }
}
