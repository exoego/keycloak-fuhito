package net.exoego.keycloak.fuhito.jpa;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import net.exoego.keycloak.fuhito.domain.Assignment;
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
        return em.createQuery("select a from AssignmentEntity a where a.realmId = :realmId"
                        + " and a.assignmentId = :assignmentId order by a.validFrom", AssignmentEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("assignmentId", assignmentId)
                .getResultStream()
                .map(JpaAssignmentStore::toDomain)
                .toList();
    }

    @Override
    public List<Assignment> rowsOfEmployment(String employmentId) {
        return em.createQuery("select a from AssignmentEntity a where a.realmId = :realmId"
                        + " and a.employmentId = :employmentId order by a.validFrom, a.id", AssignmentEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("employmentId", employmentId)
                .getResultStream()
                .map(JpaAssignmentStore::toDomain)
                .toList();
    }

    @Override
    public List<Assignment> rowsOfOrgUnit(String orgUnitId) {
        return em.createQuery("select a from AssignmentEntity a where a.realmId = :realmId"
                        + " and a.orgUnitId = :orgUnitId order by a.validFrom, a.id", AssignmentEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("orgUnitId", orgUnitId)
                .getResultStream()
                .map(JpaAssignmentStore::toDomain)
                .toList();
    }

    @Override
    public List<Assignment> rowsOn(Collection<String> orgUnitIds, LocalDate date) {
        if (orgUnitIds.isEmpty()) {
            return List.of();
        }
        return em.createQuery("select a from AssignmentEntity a where a.realmId = :realmId"
                        + " and a.orgUnitId in :orgUnitIds"
                        + " and a.validFrom <= :date and (a.validTo is null or a.validTo > :date)",
                        AssignmentEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("orgUnitIds", orgUnitIds)
                .setParameter("date", date)
                .getResultStream()
                .map(JpaAssignmentStore::toDomain)
                .toList();
    }

    @Override
    public Optional<String> findAssignmentIdByExternalId(String source, String externalId) {
        return em.createQuery("select distinct a.assignmentId from AssignmentEntity a where a.realmId = :realmId"
                        + " and a.source = :source and a.externalId = :externalId", String.class)
                .setParameter("realmId", realmId)
                .setParameter("source", source)
                .setParameter("externalId", externalId)
                .getResultStream()
                .findFirst();
    }

    @Override
    public List<String> allAssignmentIds() {
        return em.createQuery("select a.assignmentId from AssignmentEntity a where a.realmId = :realmId"
                        + " group by a.assignmentId order by min(a.validFrom), a.assignmentId", String.class)
                .setParameter("realmId", realmId)
                .getResultList();
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
        AssignmentEntity entity = em.find(AssignmentEntity.class, row.rowId());
        if (entity == null || !entity.realmId.equals(realmId)) {
            throw new IllegalStateException("No such assignment row in the realm: " + row.rowId());
        }
        copy(row, entity);
        // A split shortens this row and then inserts the next one. The database must see the shorter row first.
        em.flush();
    }

    @Override
    public void delete(String rowId) {
        AssignmentEntity entity = em.find(AssignmentEntity.class, rowId);
        if (entity != null && entity.realmId.equals(realmId)) {
            em.remove(entity);
            em.flush();
        }
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
