package net.exoego.keycloak.fuhito.jpa;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;

/**
 * Keeps two writes of one resource apart. It locks the stored rows of resources until the end of the transaction,
 * and sends the writes of a request before the commit, so that a conflict fails the request.
 *
 * <p>Every write locks in one order. The row of the realm comes first. Then the kinds come in the order in which an
 * import applies its lists, and the resources of one kind in the order of their IDs. So two writes that change the
 * same resources never wait for each other at once.
 */
public final class RowLocks {

    private record Rows(Class<?> entity, String key) {
    }

    // The first part of the path of each resource, with its table and the field that the path names, in the order
    // of the locks. Each kind comes after the kinds that it refers to.
    private static final SequencedMap<String, Rows> ROWS = new LinkedHashMap<>();

    static {
        ROWS.put("change-orders", new Rows(ChangeOrderEntity.class, "id"));
        // The path names a kind of the realm by its code.
        ROWS.put("ref-kinds", new Rows(RealmKindEntity.class, "code"));
        ROWS.put("attribute-definitions", new Rows(AttributeDefinitionEntity.class, "id"));
        ROWS.put("ref-items", new Rows(RefItemEntity.class, "id"));
        ROWS.put("hierarchies", new Rows(HierarchyEntity.class, "id"));
        ROWS.put("org-units", new Rows(OrgUnitEntity.class, "id"));
        ROWS.put("people", new Rows(PersonEntity.class, "id"));
        ROWS.put("qualifications", new Rows(QualificationEntity.class, "id"));
        ROWS.put("employments", new Rows(EmploymentEntity.class, "id"));
        ROWS.put("unit-roles", new Rows(UnitRoleEntity.class, "id"));
        // An assignment has no row of its own, only one row for each of its periods.
        ROWS.put("assignments", new Rows(AssignmentEntity.class, "assignmentId"));
        ROWS.put("reporting-lines", new Rows(ReportingLineEntity.class, "id"));
    }

    // Oracle takes at most 1,000 values after in.
    private static final int IDS_AT_A_TIME = 1000;

    private RowLocks() {
    }

    /**
     * Locks the row of the realm for writes until the end of the transaction. A bulk write, such as an import,
     * locks it alone, and every other write shares it. So the other writes of the realm wait while a bulk write
     * runs, and a bulk write waits for the writes that run. A write calls it once, before it locks or reads
     * anything that it changes. The first write of a realm adds the row.
     *
     * @param em the entity manager of the transaction
     * @param realmId the ID of the realm
     * @param alone true for a bulk write
     */
    public static void lockRealm(EntityManager em, String realmId, boolean alone) {
        TypedQuery<RealmWriteEntity> row = em.createQuery(
                        "select r from RealmWriteEntity r where r.realmId = :realmId", RealmWriteEntity.class)
                .setParameter("realmId", realmId)
                .setLockMode(alone ? LockModeType.PESSIMISTIC_WRITE : LockModeType.PESSIMISTIC_READ);
        if (row.getResultList().isEmpty()) {
            // A second first write waits here until the first one ends, and then adds nothing.
            em.createQuery("insert into RealmWriteEntity (realmId) values (:realmId) on conflict (realmId) do nothing")
                    .setParameter("realmId", realmId)
                    .executeUpdate();
            row.getSingleResult();
        }
    }

    /**
     * Tells whether a path names a kind of resource that has stored rows to lock.
     *
     * @param collection the first part of the path, for example {@code org-units}
     * @return true for a known kind
     */
    public static boolean knows(String collection) {
        return ROWS.containsKey(collection);
    }

    /**
     * Locks the rows of resources, so that a second write to one of them waits until the first one ends.
     * It reads the rows, so a write calls it before it reads them itself. A row that the session holds already keeps
     * the values that it had then.
     * A resource that the realm does not have locks nothing.
     *
     * @param em the entity manager of the request
     * @param realmId the ID of the realm
     * @param ids the IDs of the resources by the first part of their path, for example {@code org-units}
     * @throws IllegalArgumentException when a kind is unknown
     */
    public static void lock(EntityManager em, String realmId, Map<String, ? extends Collection<String>> ids) {
        for (String collection : ids.keySet()) {
            if (!knows(collection)) {
                throw new IllegalArgumentException("No rows to lock for " + collection);
            }
        }
        ROWS.forEach((collection, rows) -> {
            Collection<String> of = ids.get(collection);
            List<String> sorted = of == null ? List.of() : of.stream().distinct().sorted().toList();
            for (int start = 0; start < sorted.size(); start += IDS_AT_A_TIME) {
                lock(em, realmId, rows.entity(), rows.key(),
                        sorted.subList(start, Math.min(start + IDS_AT_A_TIME, sorted.size())));
            }
        });
    }

    // The criteria API names the entity by its class, so no query text is put together.
    private static <T> void lock(EntityManager em, String realmId, Class<T> entity, String key, List<String> ids) {
        CriteriaBuilder builder = em.getCriteriaBuilder();
        CriteriaQuery<T> query = builder.createQuery(entity);
        Root<T> row = query.from(entity);
        query.select(row)
                .where(builder.equal(row.get("realmId"), realmId), row.get(key).in(ids))
                .orderBy(builder.asc(row.get(key)), builder.asc(row.get("id")));
        em.createQuery(query).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
    }

    /**
     * Sends the changes of the transaction to the database now. A row with a version that another write changed
     * after this one read it fails here, and not at the commit.
     *
     * @param em the entity manager of the request
     * @throws jakarta.persistence.OptimisticLockException when another write changed such a row
     */
    public static void flush(EntityManager em) {
        em.flush();
    }
}
