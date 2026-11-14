package net.exoego.keycloak.fuhito.jpa;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import net.exoego.keycloak.fuhito.domain.Qualification;
import net.exoego.keycloak.fuhito.domain.ValidPeriod;
import net.exoego.keycloak.fuhito.store.QualificationStore;

/**
 * Stores the qualifications of one realm in the database of Keycloak.
 */
public class JpaQualificationStore implements QualificationStore {

    private final EntityManager em;
    private final String realmId;

    /**
     * Creates the store.
     *
     * @param em the entity manager of the request
     * @param realmId the ID of the realm
     */
    public JpaQualificationStore(EntityManager em, String realmId) {
        this.em = em;
        this.realmId = realmId;
    }

    @Override
    public Optional<Qualification> find(String id) {
        return findEntity(id).map(JpaQualificationStore::toDomain);
    }

    @Override
    public List<Qualification> findByUser(String userId) {
        return em.createQuery("""
                        select q from QualificationEntity q where q.realmId = :realmId
                        and q.userId = :userId order by q.validFrom, q.id""", QualificationEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("userId", userId)
                .getResultStream()
                .map(JpaQualificationStore::toDomain)
                .toList();
    }

    @Override
    public void insert(Qualification qualification) {
        QualificationEntity entity = new QualificationEntity();
        entity.id = qualification.id();
        entity.realmId = realmId;
        entity.userId = qualification.userId();
        entity.externalId = qualification.externalId();
        entity.source = qualification.source();
        copy(qualification, entity);
        em.persist(entity);
    }

    @Override
    public void update(Qualification qualification) {
        copy(qualification, findEntity(qualification.id()).orElseThrow());
    }

    @Override
    public void delete(String id) {
        findEntity(id).ifPresent(em::remove);
    }

    // The realm filter keeps one realm from reaching the rows of another by ID.
    private Optional<QualificationEntity> findEntity(String id) {
        QualificationEntity entity = em.find(QualificationEntity.class, id);
        return entity != null && entity.realmId.equals(realmId) ? Optional.of(entity) : Optional.empty();
    }

    private static void copy(Qualification qualification, QualificationEntity entity) {
        entity.kindId = qualification.kindId();
        entity.number = qualification.number();
        entity.validFrom = qualification.period().from();
        entity.validTo = qualification.period().to();
        entity.attributes = qualification.attributes();
    }

    private static Qualification toDomain(QualificationEntity e) {
        return new Qualification(e.id, e.userId, e.kindId, e.number, new ValidPeriod(e.validFrom, e.validTo),
                e.attributes, e.externalId, e.source);
    }
}
