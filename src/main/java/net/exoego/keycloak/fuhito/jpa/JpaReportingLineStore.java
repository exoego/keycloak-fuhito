package net.exoego.keycloak.fuhito.jpa;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import net.exoego.keycloak.fuhito.domain.ReportingLine;
import net.exoego.keycloak.fuhito.domain.ValidPeriod;
import net.exoego.keycloak.fuhito.store.ReportingLineStore;

/**
 * Stores the reporting lines of one realm in the database of Keycloak.
 */
public class JpaReportingLineStore implements ReportingLineStore {

    private final EntityManager em;
    private final String realmId;

    /**
     * Creates the store.
     *
     * @param em the entity manager of the request
     * @param realmId the ID of the realm
     */
    public JpaReportingLineStore(EntityManager em, String realmId) {
        this.em = em;
        this.realmId = realmId;
    }

    @Override
    public Optional<ReportingLine> find(String id) {
        return findEntity(id).map(JpaReportingLineStore::toDomain);
    }

    @Override
    public List<ReportingLine> findByAssignment(String assignmentId) {
        return list("""
                select l from ReportingLineEntity l where l.realmId = :realmId and l.assignmentId = :value
                order by l.validFrom, l.id""", assignmentId);
    }

    @Override
    public List<ReportingLine> findByManager(String managerUserId) {
        return list("""
                select l from ReportingLineEntity l where l.realmId = :realmId and l.managerUserId = :value
                order by l.validFrom, l.id""", managerUserId);
    }

    private List<ReportingLine> list(String query, String value) {
        return em.createQuery(query, ReportingLineEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("value", value)
                .getResultStream()
                .map(JpaReportingLineStore::toDomain)
                .toList();
    }

    @Override
    public void insert(ReportingLine line) {
        ReportingLineEntity entity = new ReportingLineEntity();
        entity.id = line.id();
        entity.realmId = realmId;
        entity.assignmentId = line.assignmentId();
        entity.managerUserId = line.managerUserId();
        entity.kind = line.kind().name();
        entity.validFrom = line.period().from();
        entity.validTo = line.period().to();
        em.persist(entity);
    }

    @Override
    public void update(ReportingLine line) {
        ReportingLineEntity entity = findEntity(line.id()).orElseThrow();
        entity.validFrom = line.period().from();
        entity.validTo = line.period().to();
        em.flush();
    }

    @Override
    public void delete(String id) {
        findEntity(id).ifPresent(entity -> {
            em.remove(entity);
            em.flush();
        });
    }

    // The realm filter keeps one realm from reaching the rows of another by ID.
    private Optional<ReportingLineEntity> findEntity(String id) {
        ReportingLineEntity entity = em.find(ReportingLineEntity.class, id);
        return entity != null && entity.realmId.equals(realmId) ? Optional.of(entity) : Optional.empty();
    }

    private static ReportingLine toDomain(ReportingLineEntity e) {
        return new ReportingLine(e.id, e.assignmentId, e.managerUserId, ReportingLine.Kind.valueOf(e.kind),
                new ValidPeriod(e.validFrom, e.validTo));
    }
}
