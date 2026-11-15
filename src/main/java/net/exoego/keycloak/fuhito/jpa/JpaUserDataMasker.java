package net.exoego.keycloak.fuhito.jpa;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import net.exoego.keycloak.fuhito.domain.Unchecked;
import net.exoego.keycloak.fuhito.store.UserDataMasker;

/**
 * Masks the data of one person in the database of Keycloak.
 */
public class JpaUserDataMasker implements UserDataMasker {

    // The fields of a stored representation that can identify a person. A leave reason can tell of an illness.
    private static final Set<String> IDENTIFYING = Set.of("staffNumber", "number", "externalId", "attributes",
            "name", "names", "phoneticName", "leaveReason");
    private static final ObjectMapper JSON = new ObjectMapper();

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
        // Also the rows that are gone, so that their copies in the change log are masked too.
        PersonTrail trail = PersonTrail.of(em, realmId, personId);
        List<EmploymentEntity> employments = em.createQuery("""
                        select e from EmploymentEntity e
                        where e.realmId = :realmId and e.personId = :personId""", EmploymentEntity.class)
                .setParameter("realmId", realmId).setParameter("personId", personId).getResultList();
        List<String> employmentIds = new ArrayList<>();
        for (EmploymentEntity employment : employments) {
            employmentIds.add(employment.id);
            employment.staffNumber = null;
            employment.externalId = null;
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
                row.externalId = null;
            }
        }
        List<String> qualificationIds = new ArrayList<>();
        for (QualificationEntity qualification : em.createQuery("""
                        select q from QualificationEntity q
                        where q.realmId = :realmId and q.personId = :personId""", QualificationEntity.class)
                .setParameter("realmId", realmId).setParameter("personId", personId).getResultList()) {
            qualificationIds.add(qualification.id);
            qualification.number = null;
            qualification.attributes = new LinkedHashMap<>();
            qualification.externalId = null;
        }
        PersonEntity person = Rows.require(em, PersonEntity.class, personId, realmId, found -> found.realmId);
        trail.clearStoredResponses(em, realmId, personId, person.userId);
        person.name = null;
        person.names = new LinkedHashMap<>();
        person.phoneticName = null;
        person.attributes = new LinkedHashMap<>();
        person.externalId = null;
        List<String> externalIds = new ArrayList<>(removeExternalIds("PERSON", List.of(personId)));
        externalIds.addAll(removeExternalIds("EMPLOYMENT", employmentIds));
        externalIds.addAll(removeExternalIds("ASSIGNMENT", assignmentIds));
        externalIds.addAll(removeExternalIds("QUALIFICATION", qualificationIds));
        Set<String> loggedIds = new LinkedHashSet<>(externalIds);
        loggedIds.addAll(trail.externalIds());
        maskChangeLog("FUHITO_EXTERNAL_ID", loggedIds);
        maskChangeLog("FUHITO_PERSON", Set.of(personId));
        maskChangeLog("FUHITO_EMPLOYMENT", trail.employments());
        maskChangeLog("FUHITO_ASSIGNMENT", trail.assignments());
        maskChangeLog("FUHITO_QUALIFICATION", trail.qualifications());

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

    // The IDs that other systems have for the resources of the person can identify the person too.
    private List<String> removeExternalIds(String kind, List<String> resourceIds) {
        if (resourceIds.isEmpty()) {
            return List.of();
        }
        List<ExternalIdEntity> rows = em.createQuery("""
                        select x from ExternalIdEntity x where x.realmId = :realmId and x.kind = :kind
                        and %s""".formatted(ManyIds.has("x.resourceId", "ids")), ExternalIdEntity.class)
                .setParameter("realmId", realmId).setParameter("kind", kind)
                .setParameter("ids", ManyIds.of(resourceIds))
                .getResultList();
        rows.forEach(em::remove);
        return rows.stream().map(row -> row.id).toList();
    }

    // The change log holds copies of the state after each write. The same fields are cleared there.
    private void maskChangeLog(String resourceType, Set<String> resourceIds) {
        if (resourceIds.isEmpty()) {
            return;
        }
        for (ChangeLogEntity entry : em.createQuery("""
                        select l from ChangeLogEntity l where l.realmId = :realmId
                        and l.resourceType = :type and %s
                        and l.representation is not null""".formatted(ManyIds.has("l.resourceId", "ids")),
                        ChangeLogEntity.class)
                .setParameter("realmId", realmId).setParameter("type", resourceType)
                .setParameter("ids", ManyIds.of(resourceIds)).getResultList()) {
            entry.representation = masked(entry.representation);
        }
    }

    static String masked(String json) {
        return Unchecked.io(() -> {
            JsonNode root = JSON.readTree(json);
            strip(root);
            return JSON.writeValueAsString(root);
        });
    }

    private static void strip(JsonNode node) {
        if (node instanceof ObjectNode object) {
            object.remove(IDENTIFYING);
        }
        node.forEach(JpaUserDataMasker::strip);
    }
}
