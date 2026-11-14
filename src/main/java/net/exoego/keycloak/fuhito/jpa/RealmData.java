package net.exoego.keycloak.fuhito.jpa;

import java.util.List;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaDelete;

/**
 * Removal of the rows of this extension that belong to one realm or to one person.
 */
public final class RealmData {

    // Children come before the rows they refer to.
    private static final List<Class<?>> ENTITIES = List.of(
            UserMaskEntity.class,
            ReportingLineEntity.class,
            QualificationEntity.class,
            ChangeLogEntity.class,
            ChangeLogCounterEntity.class,
            ChangeOrderEntity.class,
            AssignmentEntity.class,
            EmploymentTermsEntity.class,
            EmploymentEntity.class,
            PersonEntity.class,
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
     * Erases every row about one person, for a request to delete personal data. Removal of a user from Keycloak
     * does not call this. These are the employments of the person with their terms and assignments,
     * the qualifications, the reporting lines, and the person record itself.
     *
     * @param em the entity manager
     * @param realmId the ID of the realm
     * @param personId the ID of the person
     */
    public static void deletePerson(EntityManager em, String realmId, String personId) {
        // Every query is one fixed text. The order matters, because later queries read rows of earlier ones.
        List.of(
                // Lines where the person reports, and lines where the person is the manager.
                """
                delete from ReportingLineEntity r where r.realmId = :realmId
                and (r.managerPersonId = :personId or r.assignmentId in
                (select a.assignmentId from AssignmentEntity a where a.realmId = :realmId
                and a.employmentId in
                (select e.id from EmploymentEntity e where e.realmId = :realmId and e.personId = :personId)))""",
                // An entry that only names the person as the actor of a write stays.
                """
                delete from ChangeLogEntity l where l.realmId = :realmId
                and ((l.resourceType = 'FUHITO_EMPLOYMENT' and l.resourceId in
                (select e.id from EmploymentEntity e where e.realmId = :realmId and e.personId = :personId))
                or (l.resourceType = 'FUHITO_ASSIGNMENT' and l.resourceId in
                (select a.assignmentId from AssignmentEntity a where a.realmId = :realmId
                and a.employmentId in
                (select e.id from EmploymentEntity e where e.realmId = :realmId and e.personId = :personId)))
                or (l.resourceType = 'FUHITO_QUALIFICATION' and l.resourceId in
                (select q.id from QualificationEntity q where q.realmId = :realmId and q.personId = :personId))
                or (l.resourceType = 'FUHITO_PERSON' and l.resourceId = :personId))""",
                """
                delete from AssignmentEntity x where x.realmId = :realmId and x.employmentId in
                (select e.id from EmploymentEntity e where e.realmId = :realmId and e.personId = :personId)""",
                """
                delete from EmploymentTermsEntity x where x.realmId = :realmId and x.employmentId in
                (select e.id from EmploymentEntity e where e.realmId = :realmId and e.personId = :personId)""",
                "delete from QualificationEntity q where q.realmId = :realmId and q.personId = :personId",
                "delete from UserMaskEntity m where m.realmId = :realmId and m.personId = :personId",
                "delete from EmploymentEntity e where e.realmId = :realmId and e.personId = :personId",
                "delete from PersonEntity p where p.realmId = :realmId and p.id = :personId")
                .forEach(query -> em.createQuery(query)
                        .setParameter("realmId", realmId)
                        .setParameter("personId", personId)
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
