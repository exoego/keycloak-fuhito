package net.exoego.keycloak.fuhito.jpa;

import java.util.List;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaDelete;

/**
 * Removal of the rows of this extension that belong to one realm or to one user.
 */
public final class RealmData {

    // Children come before the rows they refer to.
    private static final List<Class<?>> ENTITIES = List.of(
            UserMaskEntity.class,
            PersonEntity.class,
            ReportingLineEntity.class,
            QualificationEntity.class,
            ChangeLogEntity.class,
            ChangeOrderEntity.class,
            AssignmentEntity.class,
            EmploymentTermsEntity.class,
            EmploymentEntity.class,
            OrgUnitLinkEntity.class,
            OrgUnitVersionEntity.class,
            OrgUnitEntity.class,
            RefItemEntity.class,
            HierarchyEntity.class);

    private RealmData() {
    }

    /**
     * Deletes every row of a realm.
     *
     * @param em the entity manager
     * @param realmId the ID of the realm
     */
    public static void delete(EntityManager em, String realmId) {
        ENTITIES.forEach(entity -> deleteOfRealm(em, entity, realmId));
    }

    // The criteria API names the entity by its class, so no query text is put together.
    private static <T> void deleteOfRealm(EntityManager em, Class<T> entity, String realmId) {
        CriteriaBuilder builder = em.getCriteriaBuilder();
        CriteriaDelete<T> delete = builder.createCriteriaDelete(entity);
        delete.where(builder.equal(delete.from(entity).get("realmId"), realmId));
        em.createQuery(delete).executeUpdate();
    }

    /**
     * Erases every row about one user, for a request to delete personal data. Removal of a user from Keycloak
     * does not call this. These are the employments of the user with their terms and assignments,
     * the qualifications of the user, and the person record that is linked to the user.
     *
     * @param em the entity manager
     * @param realmId the ID of the realm
     * @param userId the ID of the user
     */
    public static void deleteUser(EntityManager em, String realmId, String userId) {
        // Every query is one fixed text. The order matters, because later queries read rows of earlier ones.
        List.of(
                // Lines where the user reports, and lines where the user is the manager.
                """
                delete from ReportingLineEntity r where r.realmId = :realmId
                and (r.managerUserId = :userId or r.assignmentId in
                (select a.assignmentId from AssignmentEntity a where a.realmId = :realmId
                and a.employmentId in
                (select e.id from EmploymentEntity e where e.realmId = :realmId and e.userId = :userId)))""",
                // An entry that only names the user as the actor of a write stays.
                """
                delete from ChangeLogEntity l where l.realmId = :realmId
                and ((l.resourceType = 'FUHITO_EMPLOYMENT' and l.resourceId in
                (select e.id from EmploymentEntity e where e.realmId = :realmId and e.userId = :userId))
                or (l.resourceType = 'FUHITO_ASSIGNMENT' and l.resourceId in
                (select a.assignmentId from AssignmentEntity a where a.realmId = :realmId
                and a.employmentId in
                (select e.id from EmploymentEntity e where e.realmId = :realmId and e.userId = :userId))))""",
                """
                delete from AssignmentEntity x where x.realmId = :realmId and x.employmentId in
                (select e.id from EmploymentEntity e where e.realmId = :realmId and e.userId = :userId)""",
                """
                delete from EmploymentTermsEntity x where x.realmId = :realmId and x.employmentId in
                (select e.id from EmploymentEntity e where e.realmId = :realmId and e.userId = :userId)""",
                // The change log keeps no entries about the qualifications of the user either.
                """
                delete from ChangeLogEntity l where l.realmId = :realmId
                and l.resourceType = 'FUHITO_QUALIFICATION' and l.resourceId in
                (select q.id from QualificationEntity q where q.realmId = :realmId and q.userId = :userId)""",
                "delete from QualificationEntity q where q.realmId = :realmId and q.userId = :userId",
                "delete from UserMaskEntity m where m.realmId = :realmId and m.userId = :userId",
                // The person record of the user holds the name, so it goes too, with its change log entries.
                """
                delete from ChangeLogEntity l where l.realmId = :realmId
                and l.resourceType = 'FUHITO_PERSON' and l.resourceId in
                (select p.id from PersonEntity p where p.realmId = :realmId and p.userId = :userId)""",
                "delete from PersonEntity p where p.realmId = :realmId and p.userId = :userId",
                "delete from EmploymentEntity e where e.realmId = :realmId and e.userId = :userId")
                .forEach(query -> em.createQuery(query)
                        .setParameter("realmId", realmId)
                        .setParameter("userId", userId)
                        .executeUpdate());
    }

    /**
     * Removes the link to a Keycloak organization from the org unit that has it.
     *
     * @param em the entity manager
     * @param realmId the ID of the realm
     * @param organizationId the ID of the organization
     */
    public static void unlinkOrganization(EntityManager em, String realmId, String organizationId) {
        em.createQuery("""
                        update OrgUnitEntity u set u.keycloakOrganizationId = null where u.realmId = :realmId
                        and u.keycloakOrganizationId = :organizationId""")
                .setParameter("realmId", realmId)
                .setParameter("organizationId", organizationId)
                .executeUpdate();
    }
}
