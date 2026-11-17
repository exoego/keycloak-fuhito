package net.exoego.keycloak.fuhito.jpa;

import java.util.List;
import java.util.OptionalLong;

import jakarta.persistence.EntityManager;
import net.exoego.keycloak.fuhito.domain.ExternalId;

/**
 * Counts up the version of a resource whose writes can change several rows, such as an org unit with its periods
 * and its parents. Each write counts it up once, however many rows it changes.
 */
final class Versions {

    private Versions() {
    }

    static void orgUnit(EntityManager em, String realmId, String id) {
        Rows.require(em, OrgUnitEntity.class, id, realmId, row -> row.realmId).countUp();
    }

    static void person(EntityManager em, String realmId, String id) {
        Rows.require(em, PersonEntity.class, id, realmId, row -> row.realmId).countUp();
    }

    static void employment(EntityManager em, String realmId, String id) {
        Rows.require(em, EmploymentEntity.class, id, realmId, row -> row.realmId).countUp();
    }

    static void qualification(EntityManager em, String realmId, String id) {
        Rows.require(em, QualificationEntity.class, id, realmId, row -> row.realmId).countUp();
    }

    /**
     * Counts up the version on every row of an assignment, once in a transaction.
     *
     * @param em the entity manager of the transaction
     * @param realmId the ID of the realm
     * @param assignmentId the ID of the assignment
     * @return the version, or empty when the assignment has no row yet
     */
    static OptionalLong assignment(EntityManager em, String realmId, String assignmentId) {
        List<AssignmentEntity> rows = em.createQuery("""
                        select a from AssignmentEntity a where a.realmId = :realmId
                        and a.assignmentId = :assignmentId""", AssignmentEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("assignmentId", assignmentId)
                .getResultList();
        if (rows.isEmpty()) {
            return OptionalLong.empty();
        }
        long current = rows.stream().mapToLong(row -> row.version).max().orElseThrow();
        long version = rows.stream().anyMatch(row -> row.counted) ? current : current + 1;
        rows.forEach(row -> {
            row.version = version;
            row.counted = true;
        });
        return OptionalLong.of(version);
    }

    // The IDs that systems have for a resource are part of it.
    static void of(EntityManager em, String realmId, ExternalId.Kind kind, String resourceId) {
        switch (kind) {
            case ORG_UNIT -> orgUnit(em, realmId, resourceId);
            case PERSON -> person(em, realmId, resourceId);
            case EMPLOYMENT -> employment(em, realmId, resourceId);
            case QUALIFICATION -> qualification(em, realmId, resourceId);
            case ASSIGNMENT -> assignment(em, realmId, resourceId);
        }
    }
}
