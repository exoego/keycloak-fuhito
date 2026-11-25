package net.exoego.keycloak.fuhito.jpa;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;
import net.exoego.keycloak.fuhito.store.WriteScopes;

/**
 * Finds the stored resources that a write changes, in the database of Keycloak.
 */
public class JpaWriteScopes implements WriteScopes {

    // The conditions on an employment e that name the employments of a scope.
    private static final String OF_PERSON = "e.personId = :id";
    private static final String ONE = "e.id = :id";
    private static final String OF_EMPLOYER = "e.employerId = :id";
    private static final String OF_ASSIGNMENT = "e.id in (select b.employmentId from AssignmentEntity b "
            + "where b.assignmentId = :id)";

    private final EntityManager em;
    private final String realmId;

    /**
     * Creates the finder.
     *
     * @param em the entity manager of the request
     * @param realmId the ID of the realm
     */
    public JpaWriteScopes(EntityManager em, String realmId) {
        this.em = em;
        this.realmId = realmId;
    }

    @Override
    public Map<String, Set<String>> personData(String personId) {
        Map<String, Set<String>> all = employments(OF_PERSON, personId);
        if (personId != null) {
            all.put("people", Set.of(personId));
            all.put("qualifications", ids("""
                    select q.realmId, q.id from QualificationEntity q where q.personId = :id""", personId));
            all.get("reporting-lines").addAll(ids("""
                    select l.realmId, l.id from ReportingLineEntity l where l.managerPersonId = :id""", personId));
        }
        return all;
    }

    // The key of the person has the realm and the user.
    @Override
    public Map<String, Set<String>> userData(String userId) {
        Map<String, Set<String>> all = new HashMap<>();
        if (userId == null) {
            return all;
        }
        em.createQuery("select p.id from PersonEntity p where p.realmId = :realmId and p.userId = :id", String.class)
                .setParameter("realmId", realmId)
                .setParameter("id", userId)
                .getResultList()
                .forEach(personId -> personData(personId).forEach((collection, ids) -> all
                        .computeIfAbsent(collection, any -> new HashSet<>()).addAll(ids)));
        return all;
    }

    @Override
    public Optional<String> userOfPerson(String personId) {
        return em.createQuery("""
                        select p.userId from PersonEntity p where p.realmId = :realmId and p.id = :id
                        and p.userId is not null""", String.class)
                .setParameter("realmId", realmId)
                .setParameter("id", personId)
                .getResultList().stream()
                .findFirst();
    }

    @Override
    public Map<String, Set<String>> employment(String employmentId) {
        return employments(ONE, employmentId);
    }

    @Override
    public Map<String, Set<String>> employer(String employerId) {
        return employments(OF_EMPLOYER, employerId);
    }

    @Override
    public Map<String, Set<String>> assignmentsOfPerson(String employmentId) {
        return Map.of("assignments", ids(ofPeople(ONE), employmentId));
    }

    @Override
    public Map<String, Set<String>> assignmentsBeside(String assignmentId) {
        return Map.of("assignments", ids(ofPeople(OF_ASSIGNMENT), assignmentId));
    }

    // The assignments of every employment of the people of the employments that a condition names.
    private static String ofPeople(String condition) {
        return """
                select distinct a.realmId, a.assignmentId from AssignmentEntity a where a.employmentId in
                (select p.id from EmploymentEntity p where p.personId in
                (select e.personId from EmploymentEntity e where %s))""".formatted(condition);
    }

    // Each condition is one of the fixed texts above.
    private Map<String, Set<String>> employments(String condition, String id) {
        String employments = "select e.id from EmploymentEntity e where " + condition;
        String assignments = "select a.assignmentId from AssignmentEntity a where a.employmentId in ("
                + employments + ")";
        Map<String, Set<String>> all = new HashMap<>();
        all.put("employments", ids("select e.realmId, e.id from EmploymentEntity e where " + condition, id));
        all.put("assignments", ids(ofPeople(condition), id));
        all.put("unit-roles", ids("select r.realmId, r.id from UnitRoleEntity r "
                + "where r.employmentId in (" + employments + ")", id));
        all.put("reporting-lines", ids("select l.realmId, l.id from ReportingLineEntity l "
                + "where l.assignmentId in (" + assignments + ")", id));
        return all;
    }

    // Each query finds the realm and the ID of its rows by an ID alone, for the reason in Rows.inRealm.
    private Set<String> ids(String query, String id) {
        if (id == null) {
            return new HashSet<>();
        }
        return em.createQuery(query, Object[].class)
                .setParameter("id", id)
                .getResultList().stream()
                .filter(Rows.inRealm(realmId, row -> (String) row[0]))
                .map(row -> (String) row[1])
                .collect(Collectors.toCollection(HashSet::new));
    }
}
