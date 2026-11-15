package net.exoego.keycloak.fuhito.jpa;

import java.util.List;
import java.util.Set;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaDelete;

/**
 * Removal of the rows of this extension that belong to one realm or to one person.
 */
public final class RealmData {

    // Children come before the rows they refer to.
    static final List<Class<?>> ENTITIES = List.of(
            UserMaskEntity.class,
            ReportingLineEntity.class,
            UnitRoleEntity.class,
            QualificationEntity.class,
            ChangeLogEntity.class,
            ChangeLogCounterEntity.class,
            ChangeOrderEntity.class,
            AssignmentEntity.class,
            EmploymentTermsEntity.class,
            EmploymentEntity.class,
            PersonEntity.class,
            OrgUnitSuccessionEntity.class,
            OrgUnitLinkEntity.class,
            OrgUnitVersionEntity.class,
            OrgUnitEntity.class,
            RefItemEntity.class,
            RealmKindEntity.class,
            AttributeDefinitionEntity.class,
            ExternalIdEntity.class,
            IdempotencyKeyEntity.class,
            JobRunEntity.class,
            HierarchyEntity.class);

    // The record of what happened, and the state of requests and of jobs, stay over a restore.
    private static final Set<Class<?>> KEPT_BY_RESTORE = Set.of(ChangeLogEntity.class, ChangeLogCounterEntity.class,
            IdempotencyKeyEntity.class, JobRunEntity.class);

    private RealmData() {
    }

    /**
     * Deletes every row of a realm that a restore replaces.
     *
     * @param em the entity manager
     * @param realmId the ID of the realm
     */
    public static void deleteForRestore(EntityManager em, String realmId) {
        ENTITIES.stream()
                .filter(entity -> !KEPT_BY_RESTORE.contains(entity))
                .forEach(entity -> deleteOfRealm(em, entity, realmId));
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
     * does not call this. These are the employments of the person with their terms, assignments, and roles over
     * org units, the qualifications, the reporting lines, and the person record itself.
     *
     * @param em the entity manager
     * @param realmId the ID of the realm
     * @param personId the ID of the person
     */
    public static void deletePerson(EntityManager em, String realmId, String personId) {
        // The trail is read first, because the deletes below remove the rows that it starts from.
        PersonTrail trail = PersonTrail.of(em, realmId, personId);
        PersonEntity person = em.find(PersonEntity.class, personId);
        trail.clearStoredResponses(em, realmId, personId,
                person == null || !person.realmId.equals(realmId) ? null : person.userId);
        // An entry that only names the person as the actor of a write stays.
        deleteEntries(em, realmId, "FUHITO_PERSON", Set.of(personId));
        deleteEntries(em, realmId, "FUHITO_PERSON_DATA", Set.of(personId));
        deleteEntries(em, realmId, "FUHITO_EMPLOYMENT", trail.employments());
        deleteEntries(em, realmId, "FUHITO_ASSIGNMENT", trail.assignments());
        deleteEntries(em, realmId, "FUHITO_QUALIFICATION", trail.qualifications());
        deleteEntries(em, realmId, "FUHITO_UNIT_ROLE", trail.unitRoles());
        deleteEntries(em, realmId, "FUHITO_REPORTING_LINE", trail.reportingLines());
        deleteEntries(em, realmId, "FUHITO_EXTERNAL_ID", trail.externalIds());
        // Every query is one fixed text. The order matters, because later queries read rows of earlier ones.
        List.of(
                // Lines where the person reports, and lines where the person is the manager.
                """
                delete from ReportingLineEntity r where r.realmId = :realmId
                and (r.managerPersonId = :personId or r.assignmentId in
                (select a.assignmentId from AssignmentEntity a where a.realmId = :realmId
                and a.employmentId in
                (select e.id from EmploymentEntity e where e.realmId = :realmId and e.personId = :personId)))""",
                // The IDs that other systems have for the person and for the records of the person.
                """
                delete from ExternalIdEntity x where x.realmId = :realmId
                and ((x.kind = 'PERSON' and x.resourceId = :personId)
                or (x.kind = 'EMPLOYMENT' and x.resourceId in
                (select e.id from EmploymentEntity e where e.realmId = :realmId and e.personId = :personId))
                or (x.kind = 'ASSIGNMENT' and x.resourceId in
                (select a.assignmentId from AssignmentEntity a where a.realmId = :realmId and a.employmentId in
                (select e.id from EmploymentEntity e where e.realmId = :realmId and e.personId = :personId)))
                or (x.kind = 'QUALIFICATION' and x.resourceId in
                (select q.id from QualificationEntity q where q.realmId = :realmId and q.personId = :personId)))""",
                """
                delete from UnitRoleEntity r where r.realmId = :realmId and r.employmentId in
                (select e.id from EmploymentEntity e where e.realmId = :realmId and e.personId = :personId)""",
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

    private static void deleteEntries(EntityManager em, String realmId, String resourceType, Set<String> ids) {
        if (!ids.isEmpty()) {
            em.createQuery("""
                            delete from ChangeLogEntity l where l.realmId = :realmId and l.resourceType = :type
                            and l.resourceId in :ids""")
                    .setParameter("realmId", realmId)
                    .setParameter("type", resourceType)
                    .setParameter("ids", ids)
                    .executeUpdate();
        }
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
