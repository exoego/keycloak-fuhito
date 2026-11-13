package net.exoego.keycloak.fuhito.jpa;

import java.util.List;

import jakarta.persistence.EntityManager;

/**
 * Removal of the rows of this extension that belong to one realm or to one user.
 */
public final class RealmData {

    // Children come before the rows they refer to.
    private static final List<String> ENTITIES = List.of(
            "ReportingLineEntity",
            "QualificationEntity",
            "ChangeLogEntity",
            "ChangeOrderEntity",
            "AssignmentEntity",
            "EmploymentTermsEntity",
            "EmploymentEntity",
            "OrgUnitLinkEntity",
            "OrgUnitVersionEntity",
            "OrgUnitEntity",
            "RefItemEntity",
            "HierarchyEntity");

    private RealmData() {
    }

    /**
     * Deletes every row of a realm.
     *
     * @param em the entity manager
     * @param realmId the ID of the realm
     */
    public static void delete(EntityManager em, String realmId) {
        for (String entity : ENTITIES) {
            em.createQuery("delete from " + entity + " e where e.realmId = :realmId")
                    .setParameter("realmId", realmId)
                    .executeUpdate();
        }
    }

    /**
     * Deletes every row about one user. These are the employments of the user with their terms and assignments,
     * and the qualifications of the user.
     *
     * @param em the entity manager
     * @param realmId the ID of the realm
     * @param userId the ID of the user
     */
    public static void deleteUser(EntityManager em, String realmId, String userId) {
        String employmentsOfUser = "(select e.id from EmploymentEntity e where e.realmId = :realmId"
                + " and e.userId = :userId)";
        // Lines where the user reports, and lines where the user is the manager.
        em.createQuery("delete from ReportingLineEntity r where r.realmId = :realmId"
                        + " and (r.managerUserId = :userId or r.assignmentId in"
                        + " (select a.assignmentId from AssignmentEntity a where a.realmId = :realmId"
                        + " and a.employmentId in " + employmentsOfUser + "))")
                .setParameter("realmId", realmId)
                .setParameter("userId", userId)
                .executeUpdate();
        // The change log keeps the ID of a removed user as the actor of a write.
        // It does not keep the entries about the employments and assignments of that user.
        em.createQuery("delete from ChangeLogEntity l where l.realmId = :realmId"
                        + " and ((l.resourceType = 'FUHITO_EMPLOYMENT' and l.resourceId in " + employmentsOfUser + ")"
                        + " or (l.resourceType = 'FUHITO_ASSIGNMENT' and l.resourceId in"
                        + " (select a.assignmentId from AssignmentEntity a where a.realmId = :realmId"
                        + " and a.employmentId in " + employmentsOfUser + ")))")
                .setParameter("realmId", realmId)
                .setParameter("userId", userId)
                .executeUpdate();
        for (String entity : List.of("AssignmentEntity", "EmploymentTermsEntity")) {
            em.createQuery("delete from " + entity + " x where x.realmId = :realmId and x.employmentId in "
                            + employmentsOfUser)
                    .setParameter("realmId", realmId)
                    .setParameter("userId", userId)
                    .executeUpdate();
        }
        // The change log keeps no entries about the qualifications of the user either.
        em.createQuery("delete from ChangeLogEntity l where l.realmId = :realmId"
                        + " and l.resourceType = 'FUHITO_QUALIFICATION' and l.resourceId in"
                        + " (select q.id from QualificationEntity q where q.realmId = :realmId"
                        + " and q.userId = :userId)")
                .setParameter("realmId", realmId)
                .setParameter("userId", userId)
                .executeUpdate();
        em.createQuery("delete from QualificationEntity q where q.realmId = :realmId and q.userId = :userId")
                .setParameter("realmId", realmId)
                .setParameter("userId", userId)
                .executeUpdate();
        em.createQuery("delete from EmploymentEntity e where e.realmId = :realmId and e.userId = :userId")
                .setParameter("realmId", realmId)
                .setParameter("userId", userId)
                .executeUpdate();
    }
}
