package net.exoego.keycloak.fuhito.jpa;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
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
    public List<Person> search(String text, String staffNumber, String afterId, int first, int max) {
        return searchQuery(text, staffNumber, afterId, false, PersonEntity.class)
                .setFirstResult(first)
                .setMaxResults(max)
                .getResultStream()
                .map(JpaPersonStore::toDomain)
                .toList();
    }

    @Override
    public long count(String text, String staffNumber) {
        return searchQuery(text, staffNumber, null, true, Long.class).getSingleResult();
    }

    private <T> TypedQuery<T> searchQuery(String text, String staffNumber, String afterId, boolean count,
            Class<T> type) {
        StringBuilder query = new StringBuilder(count ? "select count(p)" : "select p")
                .append(" from PersonEntity p where p.realmId = :realmId");
        if (afterId != null) {
            query.append(" and p.id > :afterId");
        }
        if (text != null) {
            // A person with a Keycloak user often has no name of its own, and takes the name of the user.
            query.append("""
                     and (p.searchNames like :text escape '!' or exists (select 1 from UserEntity u
                    where u.id = p.userId and u.realmId = :realmId and (lower(u.firstName) like :text escape '!'
                    or lower(u.lastName) like :text escape '!' or u.username like :text escape '!'
                    or u.email like :text escape '!')))""");
        }
        if (staffNumber != null) {
            query.append("""
                     and exists (select 1 from EmploymentEntity e where e.realmId = :realmId
                    and e.personId = p.id and e.staffNumber = :staffNumber)""");
        }
        if (!count) {
            query.append(" order by p.id");
        }
        TypedQuery<T> typed = em.createQuery(query.toString(), type)
                .setParameter("realmId", realmId);
        if (afterId != null) {
            typed.setParameter("afterId", afterId);
        }
        if (text != null) {
            typed.setParameter("text", "%" + text.toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%")
                    .replace("_", "!_") + "%");
        }
        if (staffNumber != null) {
            typed.setParameter("staffNumber", staffNumber);
        }
        return typed;
    }

    @Override
    public List<Person> list(String afterId, int first, int max) {
        // The cursor makes the database start in the index at the ID, so a late page costs as much as the first.
        var query = afterId == null
                ? em.createQuery("select p from PersonEntity p where p.realmId = :realmId order by p.id",
                        PersonEntity.class)
                : em.createQuery("""
                        select p from PersonEntity p where p.realmId = :realmId and p.id > :afterId
                        order by p.id""", PersonEntity.class).setParameter("afterId", afterId);
        return query.setParameter("realmId", realmId)
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
        entity.source = person.source();
        entity.version = person.version();
        entity.counted = true;
        copy(person, entity);
        em.persist(entity);
    }

    @Override
    public void update(Person person) {
        PersonEntity entity = findEntity(person.id()).orElseThrow();
        copy(person, entity);
        entity.countUp();
    }

    @Override
    public void delete(String id) {
        findEntity(id).ifPresent(entity -> {
            ExternalIds.deleteOf(em, realmId, "PERSON", id);
            em.remove(entity);
        });
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
        entity.searchNames = Stream.concat(Stream.of(person.name(), person.phoneticName()),
                        person.names() == null ? Stream.empty() : person.names().values().stream())
                .filter(Objects::nonNull)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.joining("\n"));
        entity.attributes = person.attributes();
    }

    static Person toDomain(PersonEntity e) {
        return new Person(e.id, e.userId, e.name, e.names, e.phoneticName, e.attributes, e.source, e.version);
    }
}
