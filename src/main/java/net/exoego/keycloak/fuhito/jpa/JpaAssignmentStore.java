package net.exoego.keycloak.fuhito.jpa;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import net.exoego.keycloak.fuhito.domain.Assignment;
import net.exoego.keycloak.fuhito.domain.MemberCursor;
import net.exoego.keycloak.fuhito.domain.MemberFilter;
import net.exoego.keycloak.fuhito.domain.ValidPeriod;
import net.exoego.keycloak.fuhito.store.AssignmentStore;

/**
 * Stores the assignments of one realm in the database of Keycloak.
 */
public class JpaAssignmentStore implements AssignmentStore {

    private final EntityManager em;
    private final String realmId;

    /**
     * Creates the store.
     *
     * @param em the entity manager of the request
     * @param realmId the ID of the realm
     */
    public JpaAssignmentStore(EntityManager em, String realmId) {
        this.em = em;
        this.realmId = realmId;
    }

    @Override
    public List<Assignment> rows(String assignmentId) {
        return em.createQuery("""
                        select a from AssignmentEntity a where a.assignmentId = :assignmentId
                        order by a.validFrom""", AssignmentEntity.class)
                .setParameter("assignmentId", assignmentId)
                .getResultStream()
                .filter(Rows.inRealm(realmId, a -> a.realmId))
                .map(JpaAssignmentStore::toDomain)
                .toList();
    }

    @Override
    public List<Assignment> rowsOf(Collection<String> assignmentIds) {
        if (assignmentIds.isEmpty()) {
            return List.of();
        }
        return em.createQuery("""
                        select a from AssignmentEntity a where %s
                        order by a.assignmentId, a.validFrom"""
                        .formatted(ManyIds.has("a.assignmentId", "assignmentIds")), AssignmentEntity.class)
                .setParameter("assignmentIds", ManyIds.of(assignmentIds))
                .getResultStream()
                .filter(Rows.inRealm(realmId, a -> a.realmId))
                .map(JpaAssignmentStore::toDomain)
                .toList();
    }

    @Override
    public List<Assignment> rowsOfEmployment(String employmentId) {
        return rowsOfEmployments(List.of(employmentId));
    }

    @Override
    public List<Assignment> rowsOfEmployments(Collection<String> employmentIds) {
        return em.createQuery("""
                        select a from AssignmentEntity a where %s
                        order by a.employmentId, a.validFrom, a.id"""
                        .formatted(ManyIds.has("a.employmentId", "employmentIds")), AssignmentEntity.class)
                .setParameter("employmentIds", ManyIds.of(employmentIds))
                .getResultStream()
                .filter(Rows.inRealm(realmId, a -> a.realmId))
                .map(JpaAssignmentStore::toDomain)
                .toList();
    }

    @Override
    public long assignmentsHoldingFrom(String orgUnitId, LocalDate date) {
        return em.createQuery("""
                        select a.realmId, count(distinct a.assignmentId) from AssignmentEntity a
                        where a.orgUnitId = :orgUnitId and (a.validTo is null or a.validTo > :date)
                        group by a.realmId""", Object[].class)
                .setParameter("orgUnitId", orgUnitId)
                .setParameter("date", date)
                .getResultStream()
                .filter(Rows.inRealm(realmId, row -> (String) row[0]))
                .mapToLong(row -> (Long) row[1])
                .sum();
    }

    @Override
    public List<Assignment> rowsOn(Collection<String> orgUnitIds, LocalDate date) {
        return em.createQuery("""
                        select a from AssignmentEntity a where %s
                        and a.validFrom <= :date and (a.validTo is null or a.validTo > :date)"""
                        .formatted(ManyIds.has("a.orgUnitId", "orgUnitIds")),
                        AssignmentEntity.class)
                .setParameter("orgUnitIds", ManyIds.of(orgUnitIds))
                .setParameter("date", date)
                .getResultStream()
                .filter(Rows.inRealm(realmId, a -> a.realmId))
                .map(JpaAssignmentStore::toDomain)
                .toList();
    }

    @Override
    public Map<String, Set<String>> usersOn(Collection<String> orgUnitIds, LocalDate date) {
        Map<String, Set<String>> result = new HashMap<>();
        em.createQuery("""
                        select a.realmId, a.orgUnitId, p.userId from AssignmentEntity a
                        join EmploymentEntity e on e.id = a.employmentId
                        join PersonEntity p on p.id = e.personId
                        where %s
                        and a.validFrom <= :date and (a.validTo is null or a.validTo > :date)
                        and p.userId is not null""".formatted(ManyIds.has("a.orgUnitId", "orgUnitIds")),
                        Object[].class)
                .setParameter("orgUnitIds", ManyIds.of(orgUnitIds))
                .setParameter("date", date)
                .getResultStream()
                .filter(Rows.inRealm(realmId, row -> (String) row[0]))
                .forEach(row -> result.computeIfAbsent((String) row[1], id -> new LinkedHashSet<>())
                        .add((String) row[2]));
        return result;
    }

    @Override
    public List<Assignment> membersOn(MemberFilter filter, MemberCursor after, int first, int max) {
        var query = em.createQuery(MemberQueries.text(filter, after, false), AssignmentEntity.class);
        MemberQueries.bind(query, realmId, filter, after);
        return query.setFirstResult(first).setMaxResults(max).getResultStream()
                .map(JpaAssignmentStore::toDomain)
                .toList();
    }

    @Override
    public long countMembersOn(MemberFilter filter) {
        var query = em.createQuery(MemberQueries.text(filter, null, true), Long.class);
        MemberQueries.bind(query, realmId, filter, null);
        return query.getSingleResult();
    }

    // Each piece is a fixed text. The values of the search go in as parameters.
    private <T> TypedQuery<T> searchQuery(AssignmentStore.Search search, String afterId, boolean count,
            Class<T> type) {
        StringBuilder text = new StringBuilder(count ? "select count(a)" : "select a")
                .append(" from AssignmentEntity a where ")
                .append(search.orgUnitId() == null ? "a.realmId = :realmId"
                        : "a.orgUnitId = :orgUnitId and " + Rows.ofUnit("a.realmId", "orgUnitId"))
                .append(" and a.").append(TreeQuery.onDate("a"));
        if (search.jobTitleId() != null) {
            text.append(" and a.jobTitleId = :jobTitleId");
        }
        if (search.occupationId() != null) {
            text.append(" and a.occupationId = :occupationId");
        }
        if (search.kindId() != null) {
            text.append(" and a.kindId = :kindId");
        }
        if (afterId != null) {
            text.append(" and a.assignmentId > :afterId");
        }
        if (!count) {
            text.append(" order by a.assignmentId");
        }
        TypedQuery<T> query = em.createQuery(text.toString(), type)
                .setParameter("realmId", realmId)
                .setParameter("date", search.date());
        if (search.orgUnitId() != null) {
            query.setParameter("orgUnitId", search.orgUnitId());
        }
        if (search.jobTitleId() != null) {
            query.setParameter("jobTitleId", search.jobTitleId());
        }
        if (search.occupationId() != null) {
            query.setParameter("occupationId", search.occupationId());
        }
        if (search.kindId() != null) {
            query.setParameter("kindId", search.kindId());
        }
        if (afterId != null) {
            query.setParameter("afterId", afterId);
        }
        return query;
    }

    @Override
    public List<Assignment> searchOn(AssignmentStore.Search search, String afterAssignmentId, int first, int max) {
        return searchQuery(search, afterAssignmentId, false, AssignmentEntity.class)
                .setFirstResult(first).setMaxResults(max)
                .getResultStream()
                .map(JpaAssignmentStore::toDomain)
                .toList();
    }

    @Override
    public long countSearchOn(AssignmentStore.Search search) {
        return searchQuery(search, null, true, Long.class).getSingleResult();
    }

    @Override
    public List<Assignment> rowsBySourceOn(String source, LocalDate date) {
        return em.createQuery("""
                        select a from AssignmentEntity a where a.realmId = :realmId and a.source = :source
                        and a.validFrom <= :date and (a.validTo is null or a.validTo > :date) order by a.id""",
                        AssignmentEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("source", source)
                .setParameter("date", date)
                .getResultStream()
                .map(JpaAssignmentStore::toDomain)
                .toList();
    }

    @Override
    public void insertAll(List<Assignment> rows) {
        // A row of an assignment that has rows already takes their version.
        Map<String, Long> versions = Versions.assignments(em, realmId,
                rows.stream().map(Assignment::assignmentId).collect(Collectors.toSet()));
        for (Assignment row : rows) {
            AssignmentEntity entity = new AssignmentEntity();
            entity.id = row.rowId();
            entity.realmId = realmId;
            entity.assignmentId = row.assignmentId();
            entity.employmentId = row.employmentId();
            entity.orgUnitId = row.orgUnitId();
            entity.source = row.source();
            entity.continuesAssignmentId = row.continuesAssignmentId();
            entity.version = versions.computeIfAbsent(row.assignmentId(), id -> row.version());
            entity.counted = true;
            copy(row, entity);
            em.persist(entity);
        }
    }

    @Override
    public void updateAll(List<Assignment> rows) {
        Versions.assignments(em, realmId, rows.stream().map(Assignment::assignmentId).collect(Collectors.toSet()));
        for (Assignment row : rows) {
            copy(row, Rows.require(em, AssignmentEntity.class, row.rowId(), realmId, found -> found.realmId));
        }
        // A split shortens this row and then inserts the next one. The database must see the shorter row first.
        em.flush();
    }

    @Override
    public void deleteAll(Collection<String> rowIds) {
        Set<String> assignmentIds = new LinkedHashSet<>();
        for (String rowId : rowIds) {
            AssignmentEntity entity = Rows.require(em, AssignmentEntity.class, rowId, realmId,
                    found -> found.realmId);
            em.remove(entity);
            assignmentIds.add(entity.assignmentId);
        }
        em.flush();
        Set<String> kept = Versions.assignments(em, realmId, assignmentIds).keySet();
        ExternalIds.deleteOfGoneAssignments(em, realmId, assignmentIds.stream()
                .filter(id -> !kept.contains(id))
                .toList());
    }

    private static void copy(Assignment row, AssignmentEntity entity) {
        entity.validFrom = row.period().from();
        entity.validTo = row.period().to();
        entity.jobTitleId = row.jobTitleId();
        entity.occupationId = row.occupationId();
        entity.kindId = row.kindId();
        entity.workLocationId = row.workLocationId();
        entity.costCenterId = row.costCenterId();
        entity.head = row.head();
        entity.primary = row.primary();
        entity.allocationPercent = row.allocationPercent();
        entity.displayOrder = row.displayOrder();
        entity.attributes = row.attributes();
    }

    static Assignment toDomain(AssignmentEntity e) {
        return new Assignment(e.id, e.assignmentId, e.employmentId, e.orgUnitId,
                new ValidPeriod(e.validFrom, e.validTo), e.jobTitleId, e.occupationId, e.kindId, e.workLocationId,
                e.costCenterId, e.head, e.primary, e.allocationPercent, e.displayOrder, e.attributes, e.source,
                e.continuesAssignmentId, e.version);
    }
}
