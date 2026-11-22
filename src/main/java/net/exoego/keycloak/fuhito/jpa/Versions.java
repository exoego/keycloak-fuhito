package net.exoego.keycloak.fuhito.jpa;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;
import net.exoego.keycloak.fuhito.domain.ExternalId;

/**
 * Counts up the version of a resource whose writes can change several rows, such as an org unit with its periods
 * and its parents, a reference item with its names, or any resource with its IDs of other systems. Each write counts
 * it up once, however many rows it changes.
 */
final class Versions {

    private Versions() {
    }

    /**
     * Counts up a version by one.
     *
     * @param version the version
     * @return the next version
     * @throws ArithmeticException when the version is the largest long. A version that wraps around would be one
     *         that the resource had long before, so a caller that read it then could write again.
     */
    static long next(long version) {
        return Math.addExact(version, 1);
    }

    static void orgUnit(EntityManager em, String realmId, String id) {
        Rows.require(em, OrgUnitEntity.class, id, realmId, row -> row.realmId).countUp();
    }

    static void refItem(EntityManager em, String realmId, String id) {
        Rows.require(em, RefItemEntity.class, id, realmId, row -> row.realmId).countUp();
    }

    static void person(EntityManager em, String realmId, String id) {
        Rows.require(em, PersonEntity.class, id, realmId, row -> row.realmId).countUp();
    }

    static void employment(EntityManager em, String realmId, String id) {
        Rows.require(em, EmploymentEntity.class, id, realmId, row -> row.realmId).countUp();
    }

    static void qualification(EntityManager em, String realmId, String id) {
        Rows.require(em, QualificationEntity.class, id, realmId, row -> row.realmId).countUp();
    }

    static void reportingLine(EntityManager em, String realmId, String id) {
        Rows.require(em, ReportingLineEntity.class, id, realmId, row -> row.realmId).countUp();
    }

    static void unitRole(EntityManager em, String realmId, String id) {
        Rows.require(em, UnitRoleEntity.class, id, realmId, row -> row.realmId).countUp();
    }

    static void changeOrder(EntityManager em, String realmId, String id) {
        Rows.require(em, ChangeOrderEntity.class, id, realmId, row -> row.realmId).countUp();
    }

    static void refKind(EntityManager em, String realmId, String id) {
        Rows.require(em, RealmKindEntity.class, id, realmId, row -> row.realmId).countUp();
    }

    static void hierarchy(EntityManager em, String realmId, String id) {
        Rows.require(em, HierarchyEntity.class, id, realmId, HierarchyEntity::getRealmId).countUp();
    }

    static void attributeDefinition(EntityManager em, String realmId, String id) {
        Rows.require(em, AttributeDefinitionEntity.class, id, realmId, row -> row.realmId).countUp();
    }

    /**
     * Counts up the version on every row of an assignment, once in a transaction.
     *
     * @param em the entity manager of the transaction
     * @param realmId the ID of the realm
     * @param assignmentId the ID of the assignment
     */
    static void assignment(EntityManager em, String realmId, String assignmentId) {
        assignments(em, realmId, List.of(assignmentId));
    }

    /**
     * Counts up the version on every row of several assignments, once in a transaction for each.
     *
     * @param em the entity manager of the transaction
     * @param realmId the ID of the realm
     * @param assignmentIds the IDs of the assignments
     * @return the version of each assignment that has a row
     */
    static Map<String, Long> assignments(EntityManager em, String realmId, Collection<String> assignmentIds) {
        Map<String, Long> versions = new HashMap<>();
        if (assignmentIds.isEmpty()) {
            return versions;
        }
        Map<String, List<AssignmentEntity>> rowsById = em.createQuery("""
                        select a from AssignmentEntity a where %s""".formatted(ManyIds.has("a.assignmentId", "ids")),
                        AssignmentEntity.class)
                .setParameter("ids", ManyIds.of(assignmentIds))
                .getResultStream()
                .filter(Rows.inRealm(realmId, row -> row.realmId))
                .collect(Collectors.groupingBy(row -> row.assignmentId));
        rowsById.forEach((id, rows) -> {
            long current = rows.stream().mapToLong(row -> row.version).max().orElseThrow();
            long version = rows.stream().anyMatch(row -> row.counted) ? current : next(current);
            rows.forEach(row -> {
                row.version = version;
                row.counted = true;
            });
            versions.put(id, version);
        });
        return versions;
    }

    /**
     * Counts up the versions of org units with one statement, for a write that changed their reads without writing
     * them. A unit that a write of this transaction counted up already keeps its version. The IDs come from rows of
     * the realm, so the statement finds the units by their IDs alone, for the reason in {@link Rows#inRealm}.
     *
     * @param em the entity manager of the transaction
     * @param ids the IDs of the units
     */
    static void orgUnitsOfReads(EntityManager em, Collection<String> ids) {
        Set<String> wanted = new HashSet<>(ids);
        Set<String> counted = JpaLongWrite.release(em, OrgUnitEntity.class, row -> row.id, wanted);
        List<String> next = wanted.stream()
                .filter(id -> !counted.contains(id) && !JpaLongWrite.counted(OrgUnitEntity.class, id))
                .sorted()
                .toList();
        if (next.isEmpty()) {
            return;
        }
        em.createQuery("update OrgUnitEntity u set u.version = u.version + 1 where %s"
                        .formatted(ManyIds.has("u.id", "ids")))
                .setParameter("ids", ManyIds.of(next))
                .executeUpdate();
        JpaLongWrite.markCounted(OrgUnitEntity.class, next);
    }

    /**
     * Counts up the versions of assignments with one statement, as {@link #orgUnitsOfReads} does for org units. A
     * long write marks each row, so that a later write of the transaction does not count it up again.
     *
     * @param em the entity manager of the transaction
     * @param assignmentIds the IDs of the assignments
     */
    static void assignmentsOfReads(EntityManager em, Collection<String> assignmentIds) {
        Set<String> wanted = new HashSet<>(assignmentIds);
        Set<String> counted = JpaLongWrite.release(em, AssignmentEntity.class, row -> row.assignmentId, wanted);
        Map<String, List<String>> rows = new HashMap<>();
        if (JpaLongWrite.marking() && !wanted.isEmpty()) {
            em.createQuery("select a.id, a.assignmentId from AssignmentEntity a where %s"
                            .formatted(ManyIds.has("a.assignmentId", "ids")), Object[].class)
                    .setParameter("ids", ManyIds.of(wanted))
                    .getResultStream()
                    .forEach(row -> rows.computeIfAbsent((String) row[1], id -> new ArrayList<>())
                            .add((String) row[0]));
            rows.forEach((id, rowIds) -> {
                if (rowIds.stream().anyMatch(rowId -> JpaLongWrite.counted(AssignmentEntity.class, rowId))) {
                    counted.add(id);
                }
            });
        }
        List<String> next = wanted.stream().filter(id -> !counted.contains(id)).sorted().toList();
        if (next.isEmpty()) {
            return;
        }
        em.createQuery("update AssignmentEntity a set a.version = a.version + 1 where %s"
                        .formatted(ManyIds.has("a.assignmentId", "ids")))
                .setParameter("ids", ManyIds.of(next))
                .executeUpdate();
        JpaLongWrite.markCounted(AssignmentEntity.class,
                next.stream().flatMap(id -> rows.getOrDefault(id, List.of()).stream()).toList());
    }

    // The IDs that systems have for a resource are part of it. The rows of many assignments are read together.
    static void of(EntityManager em, String realmId, ExternalId.Kind kind, Collection<String> resourceIds) {
        if (kind == ExternalId.Kind.ASSIGNMENT) {
            assignments(em, realmId, resourceIds);
        } else {
            resourceIds.forEach(id -> of(em, realmId, kind, id));
        }
    }

    static void of(EntityManager em, String realmId, ExternalId.Kind kind, String resourceId) {
        switch (kind) {
            case ORG_UNIT -> orgUnit(em, realmId, resourceId);
            case PERSON -> person(em, realmId, resourceId);
            case EMPLOYMENT -> employment(em, realmId, resourceId);
            case QUALIFICATION -> qualification(em, realmId, resourceId);
            case ASSIGNMENT -> assignment(em, realmId, resourceId);
            case REPORTING_LINE -> reportingLine(em, realmId, resourceId);
            case UNIT_ROLE -> unitRole(em, realmId, resourceId);
            case CHANGE_ORDER -> changeOrder(em, realmId, resourceId);
            case REF_ITEM -> refItem(em, realmId, resourceId);
            case REF_KIND -> refKind(em, realmId, resourceId);
            case HIERARCHY -> hierarchy(em, realmId, resourceId);
            case ATTRIBUTE_DEFINITION -> attributeDefinition(em, realmId, resourceId);
        }
    }
}
