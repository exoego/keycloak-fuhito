package net.exoego.keycloak.fuhito.jpa;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

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
                        select a from AssignmentEntity a where a.realmId = :realmId
                        and a.assignmentId = :assignmentId order by a.validFrom""", AssignmentEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("assignmentId", assignmentId)
                .getResultStream()
                .map(JpaAssignmentStore::toDomain)
                .toList();
    }

    @Override
    public List<Assignment> rowsOfEmployment(String employmentId) {
        return em.createQuery("""
                        select a from AssignmentEntity a where a.realmId = :realmId
                        and a.employmentId = :employmentId order by a.validFrom, a.id""", AssignmentEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("employmentId", employmentId)
                .getResultStream()
                .map(JpaAssignmentStore::toDomain)
                .toList();
    }

    @Override
    public long assignmentsHoldingFrom(String orgUnitId, LocalDate date) {
        return em.createQuery("""
                        select count(distinct a.assignmentId) from AssignmentEntity a where a.realmId = :realmId
                        and a.orgUnitId = :orgUnitId and (a.validTo is null or a.validTo > :date)""", Long.class)
                .setParameter("realmId", realmId)
                .setParameter("orgUnitId", orgUnitId)
                .setParameter("date", date)
                .getSingleResult();
    }

    @Override
    public List<Assignment> rowsOn(Collection<String> orgUnitIds, LocalDate date) {
        return em.createQuery("""
                        select a from AssignmentEntity a where a.realmId = :realmId
                        and %s
                        and a.validFrom <= :date and (a.validTo is null or a.validTo > :date)"""
                        .formatted(ManyIds.has("a.orgUnitId", "orgUnitIds")),
                        AssignmentEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("orgUnitIds", ManyIds.of(orgUnitIds))
                .setParameter("date", date)
                .getResultStream()
                .map(JpaAssignmentStore::toDomain)
                .toList();
    }

    // Each employer with itself and every org unit below it. A person who works in one of those is at home.
    private static final String OWNED = """
            Owned as (
                select m.employerId as employerId, m.employerId as unitId
                from EmploymentEntity m where m.realmId = :realmId and m.employerId is not null
                group by m.employerId
                union all
                select o.employerId as employerId, l.childId as unitId
                from Owned o
                join OrgUnitLinkEntity l on l.parentId = o.unitId and l.realmId = :realmId
                    and l.hierarchyId = :employerHierarchyId and l.%s
            )
            """.formatted(TreeQuery.onDate("l"));

    @Override
    public List<Assignment> membersOn(MemberFilter filter, MemberCursor after, int first, int max) {
        var query = em.createQuery(membersText(filter, after, false), AssignmentEntity.class);
        bind(query, filter, after);
        return query.setFirstResult(first).setMaxResults(max).getResultStream()
                .map(JpaAssignmentStore::toDomain)
                .toList();
    }

    @Override
    public long countMembersOn(MemberFilter filter) {
        var query = em.createQuery(membersText(filter, null, true), Long.class);
        bind(query, filter, null);
        return query.getSingleResult();
    }

    private static String membersText(MemberFilter filter, MemberCursor after, boolean count) {
        // Each piece is a fixed text. The values of the request go in as parameters.
        String selected = count ? "select count(a)" : "select a";
        StringBuilder text = new StringBuilder();
        if (filter.includeDescendants()) {
            text.append(TreeQuery.SUBTREE).append(filter.seconded() == null ? "" : ", " + OWNED).append(selected)
                    .append("""
                     from AssignmentEntity a join Tree t on t.id = a.orgUnitId
                    where a.realmId = :realmId""");
        } else {
            text.append(filter.seconded() == null ? "" : "with " + OWNED).append(selected)
                    .append(" from AssignmentEntity a where a.realmId = :realmId and a.orgUnitId = :rootId");
        }
        text.append(" and a.").append(TreeQuery.onDate("a"));
        if (filter.occupationId() != null) {
            text.append(" and a.occupationId = :occupationId");
        }
        if (filter.jobTitleId() != null) {
            text.append(" and a.jobTitleId = :jobTitleId");
        }
        if (filter.status() != null) {
            text.append("""
                     and exists (select 1 from EmploymentTermsEntity e where e.realmId = :realmId
                    and e.employmentId = a.employmentId and e.status = :status and e.""")
                    .append(TreeQuery.onDate("e")).append(')');
        }
        if (filter.seconded() != null) {
            // An employment with no employer is never a secondment.
            text.append("""
                     and exists (select 1 from EmploymentEntity m where m.id = a.employmentId and\s""")
                    .append(filter.seconded().wanted() ? "m.employerId is not null and not " : "(m.employerId is null or ")
                    .append("""
                    exists (select 1 from Owned o where o.employerId = m.employerId
                    and o.unitId = a.orgUnitId))""")
                    .append(filter.seconded().wanted() ? "" : ")");
        }
        if (after != null) {
            // The rows after the cursor, in the order of the list. So the database does not count past the
            // rows of the pages before.
            String laterInUnit = """
                    (a.displayOrder > :afterOrder or (a.displayOrder = :afterOrder and (a.validFrom > :afterFrom
                    or (a.validFrom = :afterFrom and a.assignmentId > :afterId))))""";
            if (filter.includeDescendants()) {
                text.append("""
                         and (t.position > (select c.position from Tree c where c.id = :afterUnit)
                        or (a.orgUnitId = :afterUnit and\s""").append(laterInUnit).append("))");
            } else {
                text.append(" and ").append(laterInUnit);
            }
        }
        if (!count) {
            text.append(" order by ").append(filter.includeDescendants() ? "t.position, " : "")
                    .append("a.displayOrder, a.validFrom, a.assignmentId");
        }
        return text.toString();
    }

    private void bind(TypedQuery<?> query, MemberFilter filter, MemberCursor after) {
        query.setParameter("realmId", realmId)
                .setParameter("rootId", filter.orgUnitId())
                .setParameter("date", filter.date());
        if (filter.includeDescendants()) {
            query.setParameter("hierarchyId", filter.hierarchyId());
        }
        if (filter.occupationId() != null) {
            query.setParameter("occupationId", filter.occupationId());
        }
        if (filter.jobTitleId() != null) {
            query.setParameter("jobTitleId", filter.jobTitleId());
        }
        if (filter.status() != null) {
            query.setParameter("status", filter.status().name());
        }
        if (filter.seconded() != null) {
            query.setParameter("employerHierarchyId", filter.seconded().hierarchyId());
        }
        if (after != null) {
            query.setParameter("afterOrder", after.displayOrder())
                    .setParameter("afterFrom", after.validFrom())
                    .setParameter("afterId", after.assignmentId());
            if (filter.includeDescendants()) {
                query.setParameter("afterUnit", after.orgUnitId());
            }
        }
    }

    // Each piece is a fixed text. The values of the search go in as parameters.
    private <T> TypedQuery<T> searchQuery(AssignmentStore.Search search, String afterId, boolean count,
            Class<T> type) {
        StringBuilder text = new StringBuilder(count ? "select count(a)" : "select a")
                .append(" from AssignmentEntity a where a.realmId = :realmId and a.")
                .append(TreeQuery.onDate("a"));
        if (search.orgUnitId() != null) {
            text.append(" and a.orgUnitId = :orgUnitId");
        }
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
    public Optional<String> findAssignmentIdByExternalId(String source, String externalId) {
        return em.createQuery("""
                        select distinct a.assignmentId from AssignmentEntity a where a.realmId = :realmId
                        and a.source = :source and a.externalId = :externalId""", String.class)
                .setParameter("realmId", realmId)
                .setParameter("source", source)
                .setParameter("externalId", externalId)
                .getResultStream()
                .findFirst();
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
    public void insert(Assignment row) {
        AssignmentEntity entity = new AssignmentEntity();
        entity.id = row.rowId();
        entity.realmId = realmId;
        entity.assignmentId = row.assignmentId();
        entity.employmentId = row.employmentId();
        entity.orgUnitId = row.orgUnitId();
        entity.externalId = row.externalId();
        entity.source = row.source();
        entity.continuesAssignmentId = row.continuesAssignmentId();
        copy(row, entity);
        em.persist(entity);
    }

    @Override
    public void update(Assignment row) {
        AssignmentEntity entity = Rows.require(em, AssignmentEntity.class, row.rowId(), realmId, found -> found.realmId);
        copy(row, entity);
        // A split shortens this row and then inserts the next one. The database must see the shorter row first.
        em.flush();
    }

    @Override
    public void delete(String rowId) {
        em.remove(Rows.require(em, AssignmentEntity.class, rowId, realmId, found -> found.realmId));
        em.flush();
    }

    private static void copy(Assignment row, AssignmentEntity entity) {
        entity.validFrom = row.period().from();
        entity.validTo = row.period().to();
        entity.jobTitleId = row.jobTitleId();
        entity.occupationId = row.occupationId();
        entity.kindId = row.kindId();
        entity.head = row.head();
        entity.primary = row.primary();
        entity.allocationPercent = row.allocationPercent();
        entity.displayOrder = row.displayOrder();
        entity.attributes = row.attributes();
    }

    private static Assignment toDomain(AssignmentEntity e) {
        return new Assignment(e.id, e.assignmentId, e.employmentId, e.orgUnitId,
                new ValidPeriod(e.validFrom, e.validTo), e.jobTitleId, e.occupationId, e.kindId, e.head, e.primary,
                e.allocationPercent, e.displayOrder, e.attributes, e.externalId, e.source,
                e.continuesAssignmentId);
    }
}
