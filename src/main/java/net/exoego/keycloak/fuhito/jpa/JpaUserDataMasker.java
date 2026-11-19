package net.exoego.keycloak.fuhito.jpa;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import net.exoego.keycloak.fuhito.store.UserDataMasker;

/**
 * Masks the data of one person in the database of Keycloak.
 */
public class JpaUserDataMasker implements UserDataMasker {

    private final EntityManager em;
    private final String realmId;

    /**
     * Creates the masker.
     *
     * @param em the entity manager of the request
     * @param realmId the ID of the realm
     */
    public JpaUserDataMasker(EntityManager em, String realmId) {
        this.em = em;
        this.realmId = realmId;
    }

    @Override
    public Optional<Instant> maskedAt(String personId) {
        return em.createQuery("""
                        select m.maskedAt from UserMaskEntity m where m.realmId = :realmId
                        and m.personId = :personId""", Instant.class)
                .setParameter("realmId", realmId)
                .setParameter("personId", personId)
                .getResultStream()
                .findFirst();
    }

    @Override
    public void mask(String personId, Instant now) {
        List<EmploymentEntity> employments = em.createQuery("""
                        select e from EmploymentEntity e
                        where e.realmId = :realmId and e.personId = :personId""", EmploymentEntity.class)
                .setParameter("realmId", realmId).setParameter("personId", personId).getResultList();
        List<String> employmentIds = new ArrayList<>();
        for (EmploymentEntity employment : employments) {
            employmentIds.add(employment.id);
            employment.staffNumber = null;
            employment.countUp();
        }
        List<String> assignmentIds = new ArrayList<>();
        if (!employmentIds.isEmpty()) {
            em.createQuery("""
                            select t from EmploymentTermsEntity t where t.realmId = :realmId
                            and %s""".formatted(ManyIds.has("t.employmentId", "ids")), EmploymentTermsEntity.class)
                    .setParameter("realmId", realmId).setParameter("ids", ManyIds.of(employmentIds)).getResultList()
                    .forEach(terms -> {
                        terms.attributes = new LinkedHashMap<>();
                        terms.leaveReasonId = null;
                    });
            for (AssignmentEntity row : em.createQuery("""
                            select a from AssignmentEntity a
                            where a.realmId = :realmId and %s""".formatted(ManyIds.has("a.employmentId", "ids")),
                    AssignmentEntity.class)
                    .setParameter("realmId", realmId).setParameter("ids", ManyIds.of(employmentIds)).getResultList()) {
                assignmentIds.add(row.assignmentId);
                row.attributes = new LinkedHashMap<>();
            }
            assignmentIds.stream().distinct().forEach(id -> Versions.assignment(em, realmId, id));
        }
        List<String> qualificationIds = new ArrayList<>();
        for (QualificationEntity qualification : em.createQuery("""
                        select q from QualificationEntity q
                        where q.realmId = :realmId and q.personId = :personId""", QualificationEntity.class)
                .setParameter("realmId", realmId).setParameter("personId", personId).getResultList()) {
            qualificationIds.add(qualification.id);
            qualification.number = null;
            qualification.attributes = new LinkedHashMap<>();
            qualification.countUp();
        }
        PersonEntity person = Rows.require(em, PersonEntity.class, personId, realmId, found -> found.realmId);
        PersonNames.replace(em, realmId, personId, null);
        person.attributes = new LinkedHashMap<>();
        person.countUp();
        removeExternalIds("PERSON", List.of(personId));
        removeExternalIds("EMPLOYMENT", employmentIds);
        removeExternalIds("ASSIGNMENT", assignmentIds);
        removeExternalIds("QUALIFICATION", qualificationIds);
        // The lines and the roles keep their values, so only those that lose an ID are written.
        removeExternalIds("REPORTING_LINE", lineIds(personId))
                .forEach(id -> Versions.reportingLine(em, realmId, id));
        removeExternalIds("UNIT_ROLE", roleIds(employmentIds))
                .forEach(id -> Versions.unitRole(em, realmId, id));

        if (maskedAt(personId).isEmpty()) {
            UserMaskEntity mark = new UserMaskEntity();
            mark.id = UUID.randomUUID().toString();
            mark.realmId = realmId;
            mark.personId = personId;
            mark.maskedAt = now;
            em.persist(mark);
        }
        em.flush();
    }

    // Lines where the person reports, and lines where the person is the manager.
    private List<String> lineIds(String personId) {
        return em.createQuery("""
                        select l.id from ReportingLineEntity l where l.realmId = :realmId
                        and (l.managerPersonId = :personId or l.assignmentId in
                        (select a.assignmentId from AssignmentEntity a where a.realmId = :realmId
                        and a.employmentId in (select e.id from EmploymentEntity e where e.realmId = :realmId
                        and e.personId = :personId)))""",
                        String.class)
                .setParameter("realmId", realmId).setParameter("personId", personId).getResultList();
    }

    private List<String> roleIds(List<String> employmentIds) {
        if (employmentIds.isEmpty()) {
            return List.of();
        }
        return em.createQuery("""
                        select r.id from UnitRoleEntity r where r.realmId = :realmId
                        and %s""".formatted(ManyIds.has("r.employmentId", "ids")), String.class)
                .setParameter("realmId", realmId).setParameter("ids", ManyIds.of(employmentIds)).getResultList();
    }

    // The IDs that systems have for the records of the person can identify the person too.
    private Set<String> removeExternalIds(String kind, List<String> resourceIds) {
        if (resourceIds.isEmpty()) {
            return Set.of();
        }
        Set<String> owners = new LinkedHashSet<>();
        em.createQuery("""
                        select x from ExternalIdEntity x where x.realmId = :realmId and x.kind = :kind
                        and %s""".formatted(ManyIds.has("x.resourceId", "ids")), ExternalIdEntity.class)
                .setParameter("realmId", realmId).setParameter("kind", kind)
                .setParameter("ids", ManyIds.of(resourceIds))
                .getResultList()
                .forEach(row -> {
                    owners.add(row.resourceId);
                    em.remove(row);
                });
        return owners;
    }
}
