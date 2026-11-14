package net.exoego.keycloak.fuhito.jpa;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import net.exoego.keycloak.fuhito.domain.Assignment;
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
    public List<Assignment> rowsOfOrgUnit(String orgUnitId) {
        return em.createQuery("""
                        select a from AssignmentEntity a where a.realmId = :realmId
                        and a.orgUnitId = :orgUnitId order by a.validFrom, a.id""", AssignmentEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("orgUnitId", orgUnitId)
                .getResultStream()
                .map(JpaAssignmentStore::toDomain)
                .toList();
    }

    @Override
    public List<Assignment> rowsOn(Collection<String> orgUnitIds, LocalDate date) {
        return em.createQuery("""
                        select a from AssignmentEntity a where a.realmId = :realmId
                        and a.orgUnitId in :orgUnitIds
                        and a.validFrom <= :date and (a.validTo is null or a.validTo > :date)""",
                        AssignmentEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("orgUnitIds", orgUnitIds)
                .setParameter("date", date)
                .getResultStream()
                .map(JpaAssignmentStore::toDomain)
                .toList();
    }

    @Override
    public List<Assignment> membersOn(MemberFilter filter, int first, int max) {
        // Each piece is a fixed text. The values of the request go in as parameters.
        StringBuilder text = new StringBuilder();
        if (filter.includeDescendants()) {
            text.append(TreeQuery.SUBTREE).append("""
                    select a from AssignmentEntity a join Tree t on t.id = a.orgUnitId
                    where a.realmId = :realmId""");
        } else {
            text.append("select a from AssignmentEntity a where a.realmId = :realmId and a.orgUnitId = :rootId");
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
        text.append(" order by ").append(filter.includeDescendants() ? "t.position, " : "")
                .append("a.displayOrder, a.validFrom, a.assignmentId");

        var query = em.createQuery(text.toString(), AssignmentEntity.class)
                .setParameter("realmId", realmId)
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
        return query.setFirstResult(first).setMaxResults(max).getResultStream()
                .map(JpaAssignmentStore::toDomain)
                .toList();
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
                e.allocationPercent, e.displayOrder, e.attributes, e.externalId, e.source);
    }
}
