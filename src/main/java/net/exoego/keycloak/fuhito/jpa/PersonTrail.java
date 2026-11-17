package net.exoego.keycloak.fuhito.jpa;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import jakarta.persistence.EntityManager;

/**
 * The IDs of what was ever stored about one person, also of rows that are gone. The change log keeps a copy of
 * a row after each write, and the IDs in the copies lead to the rest.
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
        Trail trail = new Trail(em, realmId);
        Set<String> employments = trail.both("select e.id from EmploymentEntity e where e.realmId = :realmId "
                + "and e.personId in :ids", List.of(personId), "FUHITO_EMPLOYMENT", "personId");
        Set<String> assignments = trail.both("select a.assignmentId from AssignmentEntity a where a.realmId = :realmId "
                + "and a.employmentId in :ids", employments, "FUHITO_ASSIGNMENT", "employmentId");
        Set<String> qualifications = trail.both("select q.id from QualificationEntity q where q.realmId = :realmId "
                + "and q.personId in :ids", List.of(personId), "FUHITO_QUALIFICATION", "personId");
        Set<String> unitRoles = trail.both("select r.id from UnitRoleEntity r where r.realmId = :realmId "
                + "and r.employmentId in :ids", employments, "FUHITO_UNIT_ROLE", "employmentId");
        Set<String> reportingLines = trail.both("select r.id from ReportingLineEntity r where r.realmId = :realmId "
                + "and r.assignmentId in :ids", assignments, "FUHITO_REPORTING_LINE", "assignmentId");
        reportingLines.addAll(trail.both("select r.id from ReportingLineEntity r where r.realmId = :realmId "
                + "and r.managerPersonId in :ids", List.of(personId), "FUHITO_REPORTING_LINE", "managerPersonId"));
        Set<String> named = new LinkedHashSet<>(List.of(personId));
        named.addAll(employments);
        named.addAll(assignments);
        named.addAll(qualifications);
        Set<String> externalIds = trail.both("select x.id from ExternalIdEntity x where x.realmId = :realmId "
                + "and x.resourceId in :ids", named, "FUHITO_EXTERNAL_ID", "resourceId");
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
                    .setParameter("pattern", "%\"" + Trail.escaped(id) + "\"%")
                    .executeUpdate();
        }
    }

    private record Trail(EntityManager em, String realmId) {

        // The IDs of the rows of today, and the IDs of the copies that name one of the values in a field.
        Set<String> both(String rowsOfToday, Collection<String> values, String resourceType, String field) {
            Set<String> ids = new LinkedHashSet<>();
            if (values.isEmpty()) {
                return ids;
            }
            ids.addAll(em.createQuery(rowsOfToday, String.class)
                    .setParameter("realmId", realmId)
                    .setParameter("ids", values)
                    .getResultList());
            // A copy is JSON with no spaces, so a field and its value are always written the same way.
            for (String value : values) {
                ids.addAll(em.createQuery("""
                                select distinct l.resourceId from ChangeLogEntity l where l.realmId = :realmId
                                and l.resourceType = :type and l.representation like :pattern escape '!'""",
                                String.class)
                        .setParameter("realmId", realmId)
                        .setParameter("type", resourceType)
                        .setParameter("pattern", "%\"" + field + "\":\"" + escaped(value) + "\"%")
                        .getResultList());
            }
            return ids;
        }

        private static String escaped(String value) {
            return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
        }
    }
}
