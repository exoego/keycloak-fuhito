package net.exoego.keycloak.fuhito.jpa;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;
import net.exoego.keycloak.fuhito.domain.Assignment;
import net.exoego.keycloak.fuhito.domain.ValidPeriod;

/**
 * Makes assignments out of the rows of their periods. A row has no version, as the version of an assignment is in
 * {@link AssignmentVersionEntity}. So each read takes the versions of its rows with one more query, and this is
 * the only way from a row to an assignment.
 */
final class AssignmentReads {

    private AssignmentReads() {
    }

    /**
     * Makes the assignments of rows.
     *
     * @param em the entity manager
     * @param rows the rows
     * @return the assignments, in the order of the rows
     */
    static List<Assignment> of(EntityManager em, List<AssignmentEntity> rows) {
        Map<String, Long> versions = versions(em, rows);
        return rows.stream().map(row -> assignment(row, versions)).toList();
    }

    /**
     * Makes the assignments of the rows of a query that selects a key first and a row of an assignment after it.
     *
     * @param em the entity manager
     * @param rows the rows of the query
     * @param column the column of the row of an assignment
     * @return the assignments by the key, each list in the order of the rows
     */
    static Map<String, List<Assignment>> byKey(EntityManager em, List<Object[]> rows, int column) {
        Map<String, Long> versions = versions(em, rows.stream().map(row -> (AssignmentEntity) row[column]).toList());
        Map<String, List<Assignment>> found = new HashMap<>();
        rows.forEach(row -> found.computeIfAbsent((String) row[0], key -> new ArrayList<>())
                .add(assignment((AssignmentEntity) row[column], versions)));
        return found;
    }

    // The IDs come from rows of the realm, so the query finds the versions by their IDs alone, for the reason in
    // Rows.inRealm. With all IDs in one condition, PostgreSQL scanned the whole table while the statistics knew only a
    // few of its rows. A sub-select for each ID takes the key, as a scan there would read the table once for each ID.
    private static Map<String, Long> versions(EntityManager em, List<AssignmentEntity> rows) {
        Map<String, Long> versions = new HashMap<>();
        if (rows.isEmpty()) {
            return versions;
        }
        em.createQuery("select i.id, (select v.version from AssignmentVersionEntity v where v.id = i.id) from %s i"
                        .formatted(ManyIds.rows("ids")), Object[].class)
                .setParameter("ids", ManyIds.of(rows.stream().map(row -> row.assignmentId).distinct().toList()))
                .getResultStream()
                .forEach(row -> versions.put((String) row[0], (Long) row[1]));
        return versions;
    }

    private static Assignment assignment(AssignmentEntity e, Map<String, Long> versions) {
        return new Assignment(e.id, e.assignmentId, e.employmentId, e.orgUnitId,
                new ValidPeriod(e.validFrom, e.validTo), e.jobTitleId, e.occupationId, e.kindId, e.workLocationId,
                e.costCenterId, e.head, e.primary, e.allocationPercent, e.displayOrder, e.attributes, e.source,
                versions.get(e.assignmentId));
    }
}
