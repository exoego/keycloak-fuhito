package net.exoego.keycloak.fuhito.jpa;

import java.util.List;

import jakarta.persistence.EntityManager;
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

    @Override
    public List<ChangeLogEntry> listByResource(String resourceType, String resourceId, int first, int max) {
        return em.createQuery("""
                        select l from ChangeLogEntity l where l.realmId = :realmId
                        and l.resourceType = :resourceType and l.resourceId = :resourceId order by l.sequence""",
                        ChangeLogEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("resourceType", resourceType)
                .setParameter("resourceId", resourceId)
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
        // No test has an entry with no number. It happens when an entry is read in the transaction that
        // wrote it, before the database gave the number back.
        long sequence = e.sequence == null ? 0 : e.sequence;
        return new ChangeLogEntry(e.id, sequence, e.recordedAt, e.actorUserId, e.resourceType, e.resourceId,
                e.operation, e.resourcePath, e.changeOrderId, e.representation);
    }
}
