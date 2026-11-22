package net.exoego.keycloak.fuhito.jpa;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import net.exoego.keycloak.fuhito.domain.ChangeLogEntry;
import net.exoego.keycloak.fuhito.domain.DerivedValue;
import net.exoego.keycloak.fuhito.domain.ResourceType;
import net.exoego.keycloak.fuhito.store.ChangeLogStore;

/**
 * Stores the change log of one realm in the database of Keycloak.
 */
public class JpaChangeLogStore implements ChangeLogStore {

    // The read pages in the database, so the realm of the change order comes from a sub-select.
    private static final String OF_CHANGE_ORDER = " from ChangeLogEntity l where l.changeOrderId = :changeOrderId and "
            + Rows.ofRecord("l.realmId", "ChangeOrderEntity", "id", "changeOrderId");

    private static final String LIST_OF_CHANGE_ORDER = "select l" + OF_CHANGE_ORDER + " order by l.sequence";

    private static final String COUNT_OF_CHANGE_ORDER = "select count(l)" + OF_CHANGE_ORDER;

    // The kind is a literal, so that PostgreSQL takes the index of the propagated changes, which has this condition.
    private static final String OLD_PROPAGATED = """
            from ChangeLogEntity l where l.realmId = :realmId and l.kind = 'PROPAGATED' and l.recordedAt < :before""";

    // With "+ 0", PostgreSQL cannot read the key of the numbers backwards for the largest one, which would pass every
    // newer entry of the realm.
    private static final String LAST_OLD_PROPAGATED = "select max(l.sequence + 0) " + OLD_PROPAGATED;

    private static final String DELETE_OLD_PROPAGATED = "delete " + OLD_PROPAGATED;

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
    public List<ChangeLogEntry> appendAll(List<ChangeLogEntry> entries) {
        long sequence = lastSequence(entries.size()) - entries.size();
        List<ChangeLogEntry> stored = new ArrayList<>();
        for (ChangeLogEntry entry : entries) {
            ChangeLogEntity entity = new ChangeLogEntity();
            entity.id = entry.id();
            entity.realmId = realmId;
            entity.sequence = ++sequence;
            entity.recordedAt = entry.recordedAt();
            entity.kind = entry.kind().name();
            entity.actorUserId = entry.actorUserId();
            entity.resourceType = entry.resourceType().name();
            entity.resourceId = entry.resourceId();
            entity.operation = entry.operation();
            entity.resourcePath = entry.resourcePath();
            entity.changeOrderId = entry.changeOrderId();
            entity.cause = entry.cause() == null ? null : entry.cause().name();
            entity.causeSequence = entry.causeSequence();
            em.persist(entity);
            stored.add(toDomain(entity));
        }
        return stored;
    }

    /**
     * Adds a propagated change of each resource that a select finds, with one statement for all of them. The entries
     * come in the order of the IDs. No write of the API caused them, so they have no change order.
     *
     * @param recordedAt when the cause happened
     * @param value the derived value that changed
     * @param cause what changed it
     * @param resourceIds a select of native SQL with the column {@code ID}. It may use the parameter
     *        {@code realmId}.
     * @param parameters the other parameters of the select
     * @return the sequence number before the first new entry
     */
    long appendPropagated(Instant recordedAt, DerivedValue value, ChangeLogEntry.Cause cause, String resourceIds,
            Map<String, Object> parameters) {
        long last = lastSequence(0);
        Query insert = em.createNativeQuery("""
                        insert into FUHITO_CHANGE_LOG (ID, REALM_ID, SEQUENCE_NUMBER, RECORDED_AT, KIND, RESOURCE_TYPE,
                          RESOURCE_ID, OPERATION, RESOURCE_PATH, CAUSE)
                        select cast(gen_random_uuid() as varchar), :realmId, :last + row_number() over (order by r.ID),
                          :recordedAt, 'PROPAGATED', :resourceType, r.ID, 'UPDATE', :path || r.ID, :cause
                        from (%s) r""".formatted(resourceIds))
                .setParameter("realmId", realmId)
                .setParameter("last", last)
                .setParameter("recordedAt", recordedAt)
                .setParameter("resourceType", value.resourceType().name())
                .setParameter("path", value.path(""))
                .setParameter("cause", cause.name());
        parameters.forEach(insert::setParameter);
        int added = insert.executeUpdate();
        if (added > 0) {
            increment(added);
        }
        return last;
    }

    // The update locks the counter of the realm until the transaction ends. So the entries of a realm become
    // visible in the order of their numbers, and a reader that goes on after the last number it saw misses none.
    private long lastSequence(int count) {
        if (increment(count) == 0) {
            em.createQuery("""
                            insert into ChangeLogCounterEntity (realmId, lastSequence) values (:realmId, 0L)
                            on conflict (realmId) do nothing""")
                    .setParameter("realmId", realmId)
                    .executeUpdate();
            increment(count);
        }
        return em.createQuery("select c.lastSequence from ChangeLogCounterEntity c where c.realmId = :realmId",
                        Long.class)
                .setParameter("realmId", realmId)
                .getSingleResult();
    }

    private int increment(int count) {
        return em.createQuery("""
                        update ChangeLogCounterEntity c set c.lastSequence = c.lastSequence + :count
                        where c.realmId = :realmId""")
                .setParameter("count", (long) count)
                .setParameter("realmId", realmId)
                .executeUpdate();
    }

    @Override
    public List<ChangeLogEntry> list(long after, ResourceType resourceType, String resourceId,
            ChangeLogEntry.Kind kind, int first, int max) {
        CriteriaBuilder builder = em.getCriteriaBuilder();
        CriteriaQuery<ChangeLogEntity> query = builder.createQuery(ChangeLogEntity.class);
        Root<ChangeLogEntity> entry = query.from(ChangeLogEntity.class);
        return em.createQuery(query.select(entry).where(where(builder, entry, after, resourceType, resourceId, kind))
                        .orderBy(builder.asc(entry.get("sequence"))))
                .setFirstResult(first)
                .setMaxResults(max)
                .getResultStream()
                .map(JpaChangeLogStore::toDomain)
                .toList();
    }

    @Override
    public long count(ResourceType resourceType, String resourceId, ChangeLogEntry.Kind kind) {
        CriteriaBuilder builder = em.getCriteriaBuilder();
        CriteriaQuery<Long> query = builder.createQuery(Long.class);
        Root<ChangeLogEntity> entry = query.from(ChangeLogEntity.class);
        return em.createQuery(query.select(builder.count(entry))
                        .where(where(builder, entry, 0, resourceType, resourceId, kind)))
                .getSingleResult();
    }

    private Predicate[] where(CriteriaBuilder builder, Root<ChangeLogEntity> entry, long after,
            ResourceType resourceType, String resourceId, ChangeLogEntry.Kind kind) {
        List<Predicate> where = new ArrayList<>();
        where.add(builder.equal(entry.get("realmId"), realmId));
        where.add(builder.gt(entry.get("sequence"), after));
        if (resourceType != null) {
            where.add(builder.equal(entry.get("resourceType"), resourceType.name()));
        }
        if (resourceId != null) {
            where.add(builder.equal(entry.get("resourceId"), resourceId));
        }
        if (kind != null) {
            where.add(builder.equal(entry.get("kind"), kind.name()));
        }
        return where.toArray(Predicate[]::new);
    }

    @Override
    public Bounds bounds() {
        return em.createQuery("""
                        select c.lastSequence, c.expiredSequence from ChangeLogCounterEntity c
                        where c.realmId = :realmId""", Object[].class)
                .setParameter("realmId", realmId)
                .getResultStream()
                .map(row -> new Bounds((Long) row[0], (Long) row[1]))
                .findFirst()
                .orElse(new Bounds(0, 0));
    }

    // The entries go first, so that the counter stays locked only for a short time. Each write of the realm waits
    // for it.
    @Override
    public long removePropagated(Instant before) {
        Long expired = em.createQuery(LAST_OLD_PROPAGATED, Long.class)
                .setParameter("realmId", realmId)
                .setParameter("before", before)
                .getSingleResult();
        if (expired == null) {
            return 0;
        }
        int removed = em.createQuery(DELETE_OLD_PROPAGATED)
                .setParameter("realmId", realmId)
                .setParameter("before", before)
                .executeUpdate();
        em.createQuery("""
                        update ChangeLogCounterEntity c set c.expiredSequence = :expired
                        where c.realmId = :realmId and c.expiredSequence < :expired""")
                .setParameter("expired", expired)
                .setParameter("realmId", realmId)
                .executeUpdate();
        return removed;
    }

    @Override
    public List<ChangeLogEntry> listByChangeOrder(String changeOrderId, int first, int max) {
        return em.createQuery(LIST_OF_CHANGE_ORDER, ChangeLogEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("changeOrderId", changeOrderId)
                .setFirstResult(first)
                .setMaxResults(max)
                .getResultStream()
                .map(JpaChangeLogStore::toDomain)
                .toList();
    }

    @Override
    public long countByChangeOrder(String changeOrderId) {
        return em.createQuery(COUNT_OF_CHANGE_ORDER, Long.class)
                .setParameter("realmId", realmId)
                .setParameter("changeOrderId", changeOrderId)
                .getSingleResult();
    }

    @Override
    public boolean existsForChangeOrder(String changeOrderId) {
        return countByChangeOrder(changeOrderId) > 0;
    }

    private static ChangeLogEntry toDomain(ChangeLogEntity e) {
        return new ChangeLogEntry(e.id, e.sequence, e.recordedAt, ChangeLogEntry.Kind.valueOf(e.kind), e.actorUserId,
                ResourceType.valueOf(e.resourceType), e.resourceId, e.operation, e.resourcePath, e.changeOrderId,
                e.cause == null ? null : ChangeLogEntry.Cause.valueOf(e.cause), e.causeSequence);
    }
}
