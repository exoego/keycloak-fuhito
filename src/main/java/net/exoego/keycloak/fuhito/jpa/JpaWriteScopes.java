package net.exoego.keycloak.fuhito.jpa;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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
                    select q.id from QualificationEntity q where q.realmId = :realmId
                    and q.personId = :id""", personId));
            all.get("reporting-lines").addAll(ids("""
                    select l.id from ReportingLineEntity l where l.realmId = :realmId
                    and l.managerPersonId = :id""", personId));
        }
        return all;
    }

    @Override
    public Map<String, Set<String>> userData(String userId) {
        Map<String, Set<String>> all = new HashMap<>();
        ids("select p.id from PersonEntity p where p.realmId = :realmId and p.userId = :id", userId)
                .forEach(personId -> personData(personId).forEach((collection, ids) -> all
                        .computeIfAbsent(collection, any -> new HashSet<>()).addAll(ids)));
        return all;
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
    public Map<String, Set<String>> assignmentsOfEmployment(String employmentId) {
        return Map.of("assignments", ids("""
                select distinct a.assignmentId from AssignmentEntity a where a.realmId = :realmId
                and a.employmentId = :id""", employmentId));
    }

    @Override
    public Map<String, Set<String>> assignmentsBeside(String assignmentId) {
        return Map.of("assignments", ids("""
                select distinct a.assignmentId from AssignmentEntity a where a.realmId = :realmId
                and a.employmentId in (select b.employmentId from AssignmentEntity b where b.realmId = :realmId
                and b.assignmentId = :id)""", assignmentId));
    }

    // Each condition is one of the fixed texts above.
    private Map<String, Set<String>> employments(String condition, String id) {
        String employments = "select e.id from EmploymentEntity e where e.realmId = :realmId and " + condition;
        String assignments = "select a.assignmentId from AssignmentEntity a where a.realmId = :realmId "
                + "and a.employmentId in (" + employments + ")";
        Map<String, Set<String>> all = new HashMap<>();
        all.put("employments", ids(employments, id));
        all.put("assignments", ids("select distinct a.assignmentId from AssignmentEntity a "
                + "where a.realmId = :realmId and a.employmentId in (" + employments + ")", id));
        all.put("unit-roles", ids("select r.id from UnitRoleEntity r where r.realmId = :realmId "
                + "and r.employmentId in (" + employments + ")", id));
        all.put("reporting-lines", ids("select l.id from ReportingLineEntity l where l.realmId = :realmId "
                + "and l.assignmentId in (" + assignments + ")", id));
        return all;
    }

    private Set<String> ids(String query, String id) {
        if (id == null) {
            return new HashSet<>();
        }
        return new HashSet<>(em.createQuery(query, String.class)
                .setParameter("realmId", realmId)
                .setParameter("id", id)
                .getResultList());
    }
}
