package net.exoego.keycloak.fuhito.jpa;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
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
        PersonTrail trail = PersonTrail.of(em, realmId, personId);
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
        trail.clearStoredResponses(em, realmId, personId, person.userId);
        person.name = null;
        person.names = new LinkedHashMap<>();
        person.phoneticName = null;
        person.searchNames = null;
        person.attributes = new LinkedHashMap<>();
        person.countUp();
        removeExternalIds("PERSON", List.of(personId));
        removeExternalIds("EMPLOYMENT", employmentIds);
        removeExternalIds("ASSIGNMENT", assignmentIds);
        removeExternalIds("QUALIFICATION", qualificationIds);

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

    // The IDs that systems have for the records of the person can identify the person too.
    private void removeExternalIds(String kind, List<String> resourceIds) {
        if (resourceIds.isEmpty()) {
            return;
        }
        em.createQuery("""
                        select x from ExternalIdEntity x where x.realmId = :realmId and x.kind = :kind
                        and %s""".formatted(ManyIds.has("x.resourceId", "ids")), ExternalIdEntity.class)
                .setParameter("realmId", realmId).setParameter("kind", kind)
                .setParameter("ids", ManyIds.of(resourceIds))
                .getResultList()
                .forEach(em::remove);
    }
}
