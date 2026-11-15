package net.exoego.keycloak.fuhito.jpa;

import java.util.Map;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

/**
 * Locks the stored row of one resource until the end of the transaction.
 */
public final class RowLocks {

    // The first part of the path of each resource, and the table of its row.
    private static final Map<String, Class<?>> ROWS = Map.ofEntries(
            Map.entry("org-units", OrgUnitEntity.class),
            Map.entry("employments", EmploymentEntity.class),
            Map.entry("people", PersonEntity.class),
            Map.entry("qualifications", QualificationEntity.class),
            Map.entry("unit-roles", UnitRoleEntity.class),
            Map.entry("reporting-lines", ReportingLineEntity.class),
            Map.entry("ref-items", RefItemEntity.class),
            Map.entry("hierarchies", HierarchyEntity.class),
            Map.entry("attribute-definitions", AttributeDefinitionEntity.class),
            Map.entry("change-orders", ChangeOrderEntity.class));

    private RowLocks() {
    }

    /**
     * Locks the row of a resource, so that a second write to it waits until the first one ends.
     * A path of no known resource locks nothing.
     *
     * @param em the entity manager of the request
     * @param realmId the ID of the realm
     * @param collection the first part of the path of the resource, for example {@code org-units}
     * @param id the ID of the resource
     */
    public static void lock(EntityManager em, String realmId, String collection, String id) {
        // An assignment has no row of its own, only one row for each of its periods.
        if (collection.equals("assignments")) {
            em.createQuery("""
                            select a from AssignmentEntity a where a.realmId = :realmId
                            and a.assignmentId = :id""", AssignmentEntity.class)
                    .setParameter("realmId", realmId)
                    .setParameter("id", id)
                    .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                    .getResultList();
            return;
        }
        Class<?> row = ROWS.get(collection);
        if (row != null) {
            em.find(row, id, LockModeType.PESSIMISTIC_WRITE);
        }
    }
}
