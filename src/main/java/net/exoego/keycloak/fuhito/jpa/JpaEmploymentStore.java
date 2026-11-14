package net.exoego.keycloak.fuhito.jpa;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import net.exoego.keycloak.fuhito.domain.Employment;
import net.exoego.keycloak.fuhito.domain.EmploymentStatus;
import net.exoego.keycloak.fuhito.domain.EmploymentTerms;
import net.exoego.keycloak.fuhito.domain.ValidPeriod;
import net.exoego.keycloak.fuhito.store.EmploymentStore;

/**
 * Stores the employments of one realm in the database of Keycloak.
 */
public class JpaEmploymentStore implements EmploymentStore {

    private final EntityManager em;
    private final String realmId;

    /**
     * Creates the store.
     *
     * @param em the entity manager of the request
     * @param realmId the ID of the realm
     */
    public JpaEmploymentStore(EntityManager em, String realmId) {
        this.em = em;
        this.realmId = realmId;
    }

    @Override
    public Optional<Employment> find(String id) {
        EmploymentEntity entity = em.find(EmploymentEntity.class, id);
        // The realm filter keeps one realm from reaching the rows of another by ID.
        return entity != null && entity.realmId.equals(realmId) ? Optional.of(toDomain(entity)) : Optional.empty();
    }

    @Override
    public Optional<Employment> findByExternalId(String source, String externalId) {
        return em.createQuery("""
                        select e from EmploymentEntity e where e.realmId = :realmId and e.source = :source
                        and e.externalId = :externalId""", EmploymentEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("source", source)
                .setParameter("externalId", externalId)
                .getResultStream()
                .findFirst()
                .map(JpaEmploymentStore::toDomain);
    }

    @Override
    public List<Employment> findByStaffNumber(String staffNumber) {
        return em.createQuery("""
                        select e from EmploymentEntity e where e.realmId = :realmId
                        and e.staffNumber = :staffNumber order by e.validFrom""", EmploymentEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("staffNumber", staffNumber)
                .getResultStream()
                .map(JpaEmploymentStore::toDomain)
                .toList();
    }

    @Override
    public List<Employment> findByUser(String userId) {
        return em.createQuery("""
                        select e from EmploymentEntity e where e.realmId = :realmId
                        and e.person.userId = :userId order by e.validFrom, e.id""", EmploymentEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("userId", userId)
                .getResultStream()
                .map(JpaEmploymentStore::toDomain)
                .toList();
    }

    @Override
    public List<Employment> findByPerson(String personId) {
        return em.createQuery("""
                        select e from EmploymentEntity e where e.realmId = :realmId
                        and e.personId = :personId order by e.validFrom, e.id""", EmploymentEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("personId", personId)
                .getResultStream()
                .map(JpaEmploymentStore::toDomain)
                .toList();
    }

    @Override
    public List<Employment> findAll(Collection<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return em.createQuery("select e from EmploymentEntity e where e.realmId = :realmId and e.id in :ids",
                        EmploymentEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("ids", ids)
                .getResultStream()
                .map(JpaEmploymentStore::toDomain)
                .toList();
    }

    @Override
    public List<EmploymentTerms> termsOn(Collection<String> employmentIds, LocalDate date) {
        if (employmentIds.isEmpty()) {
            return List.of();
        }
        return em.createQuery("""
                        select t from EmploymentTermsEntity t where t.realmId = :realmId
                        and t.employmentId in :employmentIds
                        and t.validFrom <= :date and (t.validTo is null or t.validTo > :date)""",
                        EmploymentTermsEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("employmentIds", employmentIds)
                .setParameter("date", date)
                .getResultStream()
                .map(JpaEmploymentStore::toDomain)
                .toList();
    }

    @Override
    public void insert(Employment employment) {
        EmploymentEntity entity = new EmploymentEntity();
        entity.id = employment.id();
        entity.realmId = realmId;
        entity.personId = employment.personId();
        // The same session reads the row back, and it then needs the person.
        entity.person = em.getReference(PersonEntity.class, employment.personId());
        entity.validFrom = employment.period().from();
        entity.validTo = employment.period().to();
        entity.staffNumber = employment.staffNumber();
        entity.endReasonId = employment.endReasonId();
        entity.externalId = employment.externalId();
        entity.source = employment.source();
        em.persist(entity);
        // Other rows refer to this one. Hibernate groups inserts by entity type, so this row goes to the
        // database now, before a row that needs it.
        em.flush();
    }

    @Override
    public void update(Employment employment) {
        EmploymentEntity entity = Rows.require(em, EmploymentEntity.class, employment.id(), realmId, found -> found.realmId);
        entity.validFrom = employment.period().from();
        entity.validTo = employment.period().to();
        entity.staffNumber = employment.staffNumber();
        entity.endReasonId = employment.endReasonId();
    }

    @Override
    public void delete(String id) {
        em.remove(Rows.require(em, EmploymentEntity.class, id, realmId, found -> found.realmId));
        em.flush();
    }

    @Override
    public void deleteTerms(String termsId) {
        em.remove(Rows.require(em, EmploymentTermsEntity.class, termsId, realmId, found -> found.realmId));
        em.flush();
    }

    @Override
    public List<EmploymentTerms> terms(String employmentId) {
        return em.createQuery("""
                        select t from EmploymentTermsEntity t where t.realmId = :realmId
                        and t.employmentId = :employmentId order by t.validFrom""", EmploymentTermsEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("employmentId", employmentId)
                .getResultStream()
                .map(JpaEmploymentStore::toDomain)
                .toList();
    }

    @Override
    public void insertTerms(EmploymentTerms terms) {
        EmploymentTermsEntity entity = new EmploymentTermsEntity();
        entity.id = terms.id();
        entity.realmId = realmId;
        entity.employmentId = terms.employmentId();
        copy(terms, entity);
        em.persist(entity);
    }

    @Override
    public void updateTerms(EmploymentTerms terms) {
        EmploymentTermsEntity entity = Rows.require(em, EmploymentTermsEntity.class, terms.id(), realmId, found -> found.realmId);
        copy(terms, entity);
        // A split shortens this row and then inserts the next one. The database must see the shorter row first.
        em.flush();
    }

    private static void copy(EmploymentTerms terms, EmploymentTermsEntity entity) {
        entity.validFrom = terms.period().from();
        entity.validTo = terms.period().to();
        entity.employmentTypeId = terms.employmentTypeId();
        entity.gradeId = terms.gradeId();
        entity.status = terms.status().name();
        entity.workPercent = terms.workPercent();
        entity.attributes = terms.attributes();
    }

    private static Employment toDomain(EmploymentEntity e) {
        return new Employment(e.id, e.personId, e.person.userId, new ValidPeriod(e.validFrom, e.validTo), e.staffNumber, e.endReasonId,
                e.externalId, e.source);
    }

    private static EmploymentTerms toDomain(EmploymentTermsEntity e) {
        return new EmploymentTerms(e.id, e.employmentId, new ValidPeriod(e.validFrom, e.validTo), e.employmentTypeId,
                e.gradeId, EmploymentStatus.valueOf(e.status), e.workPercent, e.attributes);
    }
}
