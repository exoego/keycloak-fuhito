package net.exoego.keycloak.fuhito.jpa;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import net.exoego.keycloak.fuhito.domain.Person;
import net.exoego.keycloak.fuhito.store.PersonStore;

/**
 * Stores the people of one realm in the database of Keycloak.
 */
public class JpaPersonStore implements PersonStore {

    private final EntityManager em;
    private final String realmId;

    /**
     * Creates the store.
     *
     * @param em the entity manager of the request
     * @param realmId the ID of the realm
     */
    public JpaPersonStore(EntityManager em, String realmId) {
        this.em = em;
        this.realmId = realmId;
    }

    @Override
    public Optional<Person> find(String id) {
        return findEntity(id).map(JpaPersonStore::toDomain);
    }

    @Override
    public Optional<Person> findByUser(String userId) {
        return em.createQuery("""
                        select p from PersonEntity p where p.realmId = :realmId
                        and p.userId = :userId""", PersonEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("userId", userId)
                .getResultStream()
                .findFirst()
                .map(JpaPersonStore::toDomain);
    }

    @Override
    public Optional<Person> findByExternalId(String source, String externalId) {
        return em.createQuery("""
                        select p from PersonEntity p where p.realmId = :realmId
                        and p.source = :source and p.externalId = :externalId""", PersonEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("source", source)
                .setParameter("externalId", externalId)
                .getResultStream()
                .findFirst()
                .map(JpaPersonStore::toDomain);
    }

    @Override
    public List<Person> list(int first, int max) {
        return em.createQuery("select p from PersonEntity p where p.realmId = :realmId order by p.id",
                        PersonEntity.class)
                .setParameter("realmId", realmId)
                .setFirstResult(first)
                .setMaxResults(max)
                .getResultStream()
                .map(JpaPersonStore::toDomain)
                .toList();
    }

    @Override
    public void insert(Person person) {
        PersonEntity entity = new PersonEntity();
        entity.id = person.id();
        entity.realmId = realmId;
        entity.externalId = person.externalId();
        entity.source = person.source();
        copy(person, entity);
        em.persist(entity);
    }

    @Override
    public void update(Person person) {
        copy(person, findEntity(person.id()).orElseThrow());
    }

    @Override
    public void delete(String id) {
        findEntity(id).ifPresent(em::remove);
    }

    // The realm filter keeps one realm from reaching the rows of another by ID.
    private Optional<PersonEntity> findEntity(String id) {
        PersonEntity entity = em.find(PersonEntity.class, id);
        return entity != null && entity.realmId.equals(realmId) ? Optional.of(entity) : Optional.empty();
    }

    private static void copy(Person person, PersonEntity entity) {
        entity.userId = person.userId();
        entity.name = person.name();
        entity.names = person.names();
        entity.phoneticName = person.phoneticName();
        entity.attributes = person.attributes();
    }

    private static Person toDomain(PersonEntity e) {
        return new Person(e.id, e.userId, e.name, e.names, e.phoneticName, e.attributes, e.externalId, e.source);
    }
}
