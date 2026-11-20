package net.exoego.keycloak.fuhito.jpa;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaDelete;

/**
 * Removal of the rows of this extension that belong to one realm or to one person.
 */
public final class RealmData {

    // Children come before the rows they refer to.
    static final List<Class<?>> ENTITIES = List.of(
            ErasureEntity.class,
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
            PersonNameEntity.class,
            PersonEntity.class,
            OrgUnitSuccessionEntity.class,
            OrgUnitLinkEntity.class,
            OrgUnitVersionNameEntity.class,
            OrgUnitVersionEntity.class,
            OrgUnitEntity.class,
            RefItemNameEntity.class,
            RefItemEntity.class,
            RefKindNameEntity.class,
            RealmKindEntity.class,
            AttributeDefinitionEntity.class,
            ExternalIdEntity.class,
            JobRunEntity.class,
            ProjectedMemberEntity.class,
            HierarchyEntity.class);

    // The record of what happened, and the state of jobs, stay over a restore. So do the records of erases, which
    // the restore does again. The masks come back as the export has them. The members that the projection added
    // describe Keycloak, which a restore does not change.
    private static final Set<Class<?>> KEPT_BY_RESTORE = Set.of(ChangeLogEntity.class, ChangeLogCounterEntity.class,
            JobRunEntity.class, ErasureEntity.class, ProjectedMemberEntity.class);

    // The rows of a person are found by the person alone. Each delete checks the realm of its rows against the realm
    // of the person.
    private static final String EMPLOYMENTS = "select e.id from EmploymentEntity e where e.personId = :personId";

    private static final String ASSIGNMENTS = "select a.assignmentId from AssignmentEntity a where a.employmentId in ("
            + EMPLOYMENTS + ")";

    // Every query is one fixed text. The order matters, because later queries read rows of earlier ones.
    private static final List<String> PERSON_DELETES = List.of(
            // The IDs that systems have for the person and for the records of the person.
            """
            delete from ExternalIdEntity x where %s
            and ((x.kind = 'PERSON' and x.resourceId = :personId)
            or (x.kind = 'EMPLOYMENT' and x.resourceId in (%s))
            or (x.kind = 'ASSIGNMENT' and x.resourceId in (%s))
            or (x.kind = 'QUALIFICATION' and x.resourceId in
            (select q.id from QualificationEntity q where q.personId = :personId))
            or (x.kind = 'REPORTING_LINE' and (x.resourceId in
            (select r.id from ReportingLineEntity r where r.managerPersonId = :personId)
            or x.resourceId in (select r.id from ReportingLineEntity r where r.assignmentId in (%s))))
            or (x.kind = 'UNIT_ROLE' and x.resourceId in
            (select r.id from UnitRoleEntity r where r.employmentId in (%s))))"""
                    .formatted(ofPerson("x"), EMPLOYMENTS, ASSIGNMENTS, ASSIGNMENTS, EMPLOYMENTS),
            // Lines where the person is the manager, and lines where the person reports. Each takes its own index.
            "delete from ReportingLineEntity r where r.managerPersonId = :personId and " + ofPerson("r"),
            "delete from ReportingLineEntity r where r.assignmentId in (%s) and %s".formatted(ASSIGNMENTS,
                    ofPerson("r")),
            "delete from UnitRoleEntity r where r.employmentId in (%s) and %s".formatted(EMPLOYMENTS, ofPerson("r")),
            "delete from AssignmentEntity x where x.employmentId in (%s) and %s".formatted(EMPLOYMENTS,
                    ofPerson("x")),
            "delete from EmploymentTermsEntity x where x.employmentId in (%s) and %s".formatted(EMPLOYMENTS,
                    ofPerson("x")),
            "delete from QualificationEntity q where q.personId = :personId and " + ofPerson("q"),
            "delete from UserMaskEntity m where m.realmId = :realmId and m.personId = :personId",
            "delete from EmploymentEntity e where e.personId = :personId and " + ofPerson("e"),
            "delete from PersonNameEntity n where n.realmId = :realmId and n.personId = :personId",
            "delete from PersonEntity p where p.id = :personId and " + ofPerson("p"));

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
        // The row for writes goes first, so that the removal waits for the writes of the realm that run. The delete
        // does not see a row that a first write added and has not ended. Adding the row waits for that write. A
        // restore keeps the row.
        RowLocks.addRow(em, realmId);
        deleteOfRealm(em, RealmWriteEntity.class, realmId);
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
     * Erases every row about one person, for a request to delete personal data, and keeps the ID of the person
     * and the time. Removal of a user from Keycloak does not call this.
     *
     * @param em the entity manager
     * @param realmId the ID of the realm
     * @param personId the ID of the person
     * @param erasedAt the time of the erase
     */
    public static void erase(EntityManager em, String realmId, String personId, Instant erasedAt) {
        deletePerson(em, realmId, personId);
        recordErasure(em, realmId, personId, erasedAt);
    }

    /**
     * Keeps the ID of an erased person and the time, unless the realm has them already.
     *
     * @param em the entity manager
     * @param realmId the ID of the realm
     * @param personId the ID of the person
     * @param erasedAt the time of the erase
     */
    static void recordErasure(EntityManager em, String realmId, String personId, Instant erasedAt) {
        boolean recorded = !em.createQuery("""
                        select e.id from ErasureEntity e where e.realmId = :realmId and e.personId = :personId""",
                        String.class)
                .setParameter("realmId", realmId)
                .setParameter("personId", personId)
                .getResultList()
                .isEmpty();
        if (!recorded) {
            ErasureEntity record = new ErasureEntity();
            record.id = UUID.randomUUID().toString();
            record.realmId = realmId;
            record.personId = personId;
            record.erasedAt = erasedAt;
            em.persist(record);
        }
    }

    /**
     * Deletes every row about one person. These are the employments of the person with their terms, assignments, and roles over
     * org units, the qualifications, the reporting lines, and the person record itself with its names.
     * The entries of the change log stay, because they hold only IDs.
     *
     * @param em the entity manager
     * @param realmId the ID of the realm
     * @param personId the ID of the person
     */
    public static void deletePerson(EntityManager em, String realmId, String personId) {
        PERSON_DELETES.forEach(query -> em.createQuery(query)
                .setParameter("realmId", realmId)
                .setParameter("personId", personId)
                .executeUpdate());
    }

    private static String ofPerson(String alias) {
        return Rows.ofRecord(alias + ".realmId", "PersonEntity", "id", "personId");
    }

    /**
     * Removes the link to a Keycloak organization from the org unit that has it, which is a write of the unit.
     *
     * @param em the entity manager
     * @param realmId the ID of the realm
     * @param organizationId the ID of the organization
     */
    public static void unlinkOrganization(EntityManager em, String realmId, String organizationId) {
        em.createQuery("""
                        update OrgUnitEntity u set u.keycloakOrganizationId = null, u.version = u.version + 1
                        where u.realmId = :realmId and u.keycloakOrganizationId = :organizationId""")
                .setParameter("realmId", realmId)
                .setParameter("organizationId", organizationId)
                .executeUpdate();
    }
}
