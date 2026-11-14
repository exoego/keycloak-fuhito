package net.exoego.keycloak.fuhito.jpa;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import net.exoego.keycloak.fuhito.store.UserDataMasker;

/**
 * Masks the data of one user in the database of Keycloak.
 */
public class JpaUserDataMasker implements UserDataMasker {

    // The fields of a stored representation that can identify a person.
    private static final Set<String> IDENTIFYING = Set.of("staffNumber", "number", "externalId", "attributes");
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
    public Optional<Instant> maskedAt(String userId) {
        return em.createQuery("""
                        select m.maskedAt from UserMaskEntity m where m.realmId = :realmId
                        and m.userId = :userId""", Instant.class)
                .setParameter("realmId", realmId)
                .setParameter("userId", userId)
                .getResultStream()
                .findFirst();
    }

    @Override
    public void mask(String userId, Instant now) {
        List<EmploymentEntity> employments = em.createQuery("""
                        select e from EmploymentEntity e
                        where e.realmId = :realmId and e.userId = :userId""", EmploymentEntity.class)
                .setParameter("realmId", realmId).setParameter("userId", userId).getResultList();
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
                            and t.employmentId in :ids""", EmploymentTermsEntity.class)
                    .setParameter("realmId", realmId).setParameter("ids", employmentIds).getResultList()
                    .forEach(terms -> terms.attributes = new LinkedHashMap<>());
            for (AssignmentEntity row : em.createQuery("""
                            select a from AssignmentEntity a
                            where a.realmId = :realmId and a.employmentId in :ids""", AssignmentEntity.class)
                    .setParameter("realmId", realmId).setParameter("ids", employmentIds).getResultList()) {
                assignmentIds.add(row.assignmentId);
                row.attributes = new LinkedHashMap<>();
                row.externalId = null;
            }
        }
        List<String> qualificationIds = new ArrayList<>();
        for (QualificationEntity qualification : em.createQuery("""
                        select q from QualificationEntity q
                        where q.realmId = :realmId and q.userId = :userId""", QualificationEntity.class)
                .setParameter("realmId", realmId).setParameter("userId", userId).getResultList()) {
            qualificationIds.add(qualification.id);
            qualification.number = null;
            qualification.attributes = new LinkedHashMap<>();
            qualification.externalId = null;
        }
        maskChangeLog("FUHITO_EMPLOYMENT", employmentIds);
        maskChangeLog("FUHITO_ASSIGNMENT", assignmentIds);
        maskChangeLog("FUHITO_QUALIFICATION", qualificationIds);

        if (maskedAt(userId).isEmpty()) {
            UserMaskEntity mark = new UserMaskEntity();
            mark.id = UUID.randomUUID().toString();
            mark.realmId = realmId;
            mark.userId = userId;
            mark.maskedAt = now;
            em.persist(mark);
        }
        em.flush();
    }

    // The change log holds copies of the state after each write. The same fields are cleared there.
    private void maskChangeLog(String resourceType, List<String> resourceIds) {
        if (resourceIds.isEmpty()) {
            return;
        }
        for (ChangeLogEntity entry : em.createQuery("""
                        select l from ChangeLogEntity l where l.realmId = :realmId
                        and l.resourceType = :type and l.resourceId in :ids""", ChangeLogEntity.class)
                .setParameter("realmId", realmId).setParameter("type", resourceType)
                .setParameter("ids", resourceIds).getResultList()) {
            if (entry.representation != null) {
                entry.representation = masked(entry.representation);
            }
        }
    }

    static String masked(String json) {
        try {
            JsonNode root = JSON.readTree(json);
            strip(root);
            return JSON.writeValueAsString(root);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void strip(JsonNode node) {
        if (node instanceof ObjectNode object) {
            object.remove(IDENTIFYING);
        }
        node.forEach(JpaUserDataMasker::strip);
    }
}
