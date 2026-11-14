package net.exoego.keycloak.fuhito.jpa;

import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import net.exoego.keycloak.fuhito.domain.ChangeLogEntry;
import net.exoego.keycloak.fuhito.store.ChangeLogStore;

/**
 * Stores the change log of one realm in the database of Keycloak.
 */
public class JpaChangeLogStore implements ChangeLogStore {

    private final EntityManager em;
    private final String realmId;

    /**
     * Creates the store.
     *
     * @param em the entity manager of the request
     * @param realmId the ID of the realm
     */
    public JpaChangeLogStore(EntityManager em, String realmId) {
        this.em = em;
        this.realmId = realmId;
    }

    @Override
    public void append(ChangeLogEntry entry) {
        ChangeLogEntity entity = new ChangeLogEntity();
        entity.id = entry.id();
        entity.realmId = realmId;
        entity.sequence = nextSequence();
        entity.recordedAt = entry.recordedAt();
        entity.actorUserId = entry.actorUserId();
        entity.resourceType = entry.resourceType();
        entity.resourceId = entry.resourceId();
        entity.operation = entry.operation();
        entity.resourcePath = entry.resourcePath();
        entity.changeOrderId = entry.changeOrderId();
        entity.representation = entry.representation();
        em.persist(entity);
    }

    // The update locks the counter of the realm until the transaction ends. So the entries of a realm become
    // visible in the order of their numbers, and a reader that goes on after the last number it saw misses none.
    private long nextSequence() {
        if (increment() == 0) {
            em.createQuery("""
                            insert into ChangeLogCounterEntity (realmId, lastSequence) values (:realmId, 0L)
                            on conflict (realmId) do nothing""")
                    .setParameter("realmId", realmId)
                    .executeUpdate();
            increment();
        }
        return em.createQuery("select c.lastSequence from ChangeLogCounterEntity c where c.realmId = :realmId",
                        Long.class)
                .setParameter("realmId", realmId)
                .getSingleResult();
    }

    private int increment() {
        return em.createQuery("""
                        update ChangeLogCounterEntity c set c.lastSequence = c.lastSequence + 1
                        where c.realmId = :realmId""")
                .setParameter("realmId", realmId)
                .executeUpdate();
    }

    @Override
    public List<ChangeLogEntry> list(long after, String resourceType, String resourceId, int first, int max) {
        CriteriaBuilder builder = em.getCriteriaBuilder();
        CriteriaQuery<ChangeLogEntity> query = builder.createQuery(ChangeLogEntity.class);
        Root<ChangeLogEntity> entry = query.from(ChangeLogEntity.class);
        List<Predicate> where = new ArrayList<>();
        where.add(builder.equal(entry.get("realmId"), realmId));
        where.add(builder.gt(entry.get("sequence"), after));
        if (resourceType != null) {
            where.add(builder.equal(entry.get("resourceType"), resourceType));
        }
        if (resourceId != null) {
            where.add(builder.equal(entry.get("resourceId"), resourceId));
        }
        return em.createQuery(query.select(entry).where(where.toArray(Predicate[]::new))
                        .orderBy(builder.asc(entry.get("sequence"))))
                .setFirstResult(first)
                .setMaxResults(max)
                .getResultStream()
                .map(JpaChangeLogStore::toDomain)
                .toList();
    }

    @Override
    public List<ChangeLogEntry> listByChangeOrder(String changeOrderId, int first, int max) {
        return em.createQuery("""
                        select l from ChangeLogEntity l where l.realmId = :realmId
                        and l.changeOrderId = :changeOrderId order by l.sequence""", ChangeLogEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("changeOrderId", changeOrderId)
                .setFirstResult(first)
                .setMaxResults(max)
                .getResultStream()
                .map(JpaChangeLogStore::toDomain)
                .toList();
    }

    @Override
    public boolean existsForChangeOrder(String changeOrderId) {
        return em.createQuery("""
                        select count(l) from ChangeLogEntity l where l.realmId = :realmId
                        and l.changeOrderId = :changeOrderId""", Long.class)
                .setParameter("realmId", realmId)
                .setParameter("changeOrderId", changeOrderId)
                .getSingleResult() > 0;
    }

    private static ChangeLogEntry toDomain(ChangeLogEntity e) {
        return new ChangeLogEntry(e.id, e.sequence, e.recordedAt, e.actorUserId, e.resourceType, e.resourceId,
                e.operation, e.resourcePath, e.changeOrderId, e.representation);
    }
}
