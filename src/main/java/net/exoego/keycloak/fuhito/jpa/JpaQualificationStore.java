package net.exoego.keycloak.fuhito.jpa;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
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

    // The realm is checked on the person, whose key has the user too.
    @Override
    public List<Qualification> findByUser(String userId) {
        return em.createQuery("""
                        select q from QualificationEntity q join q.person p where p.realmId = :realmId
                        and p.userId = :userId order by q.validFrom, q.id""", QualificationEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("userId", userId)
                .getResultList().stream()
                .filter(Rows.inRealm(realmId, q -> q.realmId))
                .map(JpaQualificationStore::toDomain)
                .toList();
    }

    // Each piece is a fixed text. The values of the search go in as parameters.
    private <T> TypedQuery<T> searchQuery(QualificationStore.Search search, String afterId, boolean count,
            Class<T> type) {
        StringBuilder text = new StringBuilder(count ? "select count(q)" : "select q")
                .append(" from QualificationEntity q where q.realmId = :realmId");
        if (search.kindId() != null) {
            text.append(" and q.kindId = :kindId");
        }
        if (search.date() != null) {
            text.append(" and q.validFrom <= :date and (q.validTo is null or q.validTo > :date)");
        }
        if (afterId != null) {
            text.append(" and q.id > :afterId");
        }
        if (!count) {
            text.append(" order by q.id");
        }
        TypedQuery<T> query = em.createQuery(text.toString(), type).setParameter("realmId", realmId);
        if (search.kindId() != null) {
            query.setParameter("kindId", search.kindId());
        }
        if (search.date() != null) {
            query.setParameter("date", search.date());
        }
        if (afterId != null) {
            query.setParameter("afterId", afterId);
        }
        return query;
    }

    @Override
    public List<Qualification> search(QualificationStore.Search search, String afterId, int first, int max) {
        return searchQuery(search, afterId, false, QualificationEntity.class)
                .setFirstResult(first).setMaxResults(max)
                .getResultList().stream()
                .map(JpaQualificationStore::toDomain)
                .toList();
    }

    @Override
    public long count(QualificationStore.Search search) {
        return searchQuery(search, null, true, Long.class).getSingleResult();
    }

    // The qualifications are found by the person alone, for the reason in Rows.inRealm.
    @Override
    public List<Qualification> findByPerson(String personId) {
        return em.createQuery("""
                        select q from QualificationEntity q where q.personId = :personId
                        order by q.validFrom, q.id""", QualificationEntity.class)
                .setParameter("personId", personId)
                .getResultList().stream()
                .filter(Rows.inRealm(realmId, q -> q.realmId))
                .map(JpaQualificationStore::toDomain)
                .toList();
    }

    @Override
    public void insert(Qualification qualification) {
        QualificationEntity entity = new QualificationEntity();
        entity.id = qualification.id();
        entity.realmId = realmId;
        entity.personId = qualification.personId();
        // The same session reads the row back, and it then needs the person.
        entity.person = em.getReference(PersonEntity.class, qualification.personId());
        entity.source = qualification.source();
        entity.version = qualification.version();
        entity.counted = true;
        copy(qualification, entity);
        em.persist(entity);
    }

    @Override
    public void update(Qualification qualification) {
        QualificationEntity entity = findEntity(qualification.id()).orElseThrow();
        copy(qualification, entity);
        entity.countUp();
    }

    @Override
    public void delete(String id) {
        findEntity(id).ifPresent(entity -> {
            ExternalIds.deleteOf(em, realmId, "QUALIFICATION", id);
            em.remove(entity);
        });
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

    static Qualification toDomain(QualificationEntity e) {
        return new Qualification(e.id, e.personId, e.person.userId, e.kindId, e.number, new ValidPeriod(e.validFrom, e.validTo),
                e.attributes, e.source, e.version);
    }
}
