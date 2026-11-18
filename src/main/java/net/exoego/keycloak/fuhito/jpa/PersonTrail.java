package net.exoego.keycloak.fuhito.jpa;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import jakarta.persistence.EntityManager;

/**
 * The IDs of the rows that are stored about one person now.
 *
 * @param employments the IDs of the employments
 * @param assignments the IDs of the assignments of those employments
 * @param qualifications the IDs of the qualifications
 * @param unitRoles the IDs of the roles over org units
 * @param reportingLines the IDs of the reporting lines of the assignments, and of the lines to the person as a manager
 * @param externalIds the IDs of the stored rows of IDs of other systems for all of them
 */
record PersonTrail(Set<String> employments, Set<String> assignments, Set<String> qualifications,
        Set<String> unitRoles, Set<String> reportingLines, Set<String> externalIds) {

    static PersonTrail of(EntityManager em, String realmId, String personId) {
        Ids ids = new Ids(em, realmId);
        Set<String> employments = ids.of("select e.id from EmploymentEntity e where e.realmId = :realmId "
                + "and e.personId in :ids", List.of(personId));
        Set<String> assignments = ids.of("select a.assignmentId from AssignmentEntity a where a.realmId = :realmId "
                + "and a.employmentId in :ids", employments);
        Set<String> qualifications = ids.of("select q.id from QualificationEntity q where q.realmId = :realmId "
                + "and q.personId in :ids", List.of(personId));
        Set<String> unitRoles = ids.of("select r.id from UnitRoleEntity r where r.realmId = :realmId "
                + "and r.employmentId in :ids", employments);
        Set<String> reportingLines = ids.of("select r.id from ReportingLineEntity r where r.realmId = :realmId "
                + "and r.assignmentId in :ids", assignments);
        reportingLines.addAll(ids.of("select r.id from ReportingLineEntity r where r.realmId = :realmId "
                + "and r.managerPersonId in :ids", List.of(personId)));
        Set<String> named = new LinkedHashSet<>(List.of(personId));
        named.addAll(employments);
        named.addAll(assignments);
        named.addAll(qualifications);
        Set<String> externalIds = ids.of("select x.id from ExternalIdEntity x where x.realmId = :realmId "
                + "and x.resourceId in :ids", named);
        return new PersonTrail(employments, assignments, qualifications, unitRoles, reportingLines, externalIds);
    }

    /**
     * Clears the body of every stored response of a repeated request that names the person or a record of the
     * person. The status stays, so a request sent again still writes nothing.
     *
     * @param em the entity manager
     * @param realmId the ID of the realm
     * @param personId the ID of the person
     * @param userId the ID of the Keycloak user of the person, or null
     */
    void clearStoredResponses(EntityManager em, String realmId, String personId, String userId) {
        Set<String> ids = new LinkedHashSet<>(List.of(personId));
        if (userId != null) {
            ids.add(userId);
        }
        ids.addAll(employments);
        ids.addAll(assignments);
        ids.addAll(qualifications);
        ids.addAll(unitRoles);
        ids.addAll(reportingLines);
        ids.addAll(externalIds);
        for (String id : ids) {
            em.createQuery("""
                            update IdempotencyKeyEntity k set k.body = null where k.realmId = :realmId
                            and k.body like :pattern escape '!'""")
                    .setParameter("realmId", realmId)
                    .setParameter("pattern", "%\"" + escaped(id) + "\"%")
                    .executeUpdate();
        }
    }

    private static String escaped(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private record Ids(EntityManager em, String realmId) {

        Set<String> of(String query, Collection<String> values) {
            Set<String> ids = new LinkedHashSet<>();
            if (!values.isEmpty()) {
                ids.addAll(em.createQuery(query, String.class)
                        .setParameter("realmId", realmId)
                        .setParameter("ids", values)
                        .getResultList());
            }
            return ids;
        }
    }
}
