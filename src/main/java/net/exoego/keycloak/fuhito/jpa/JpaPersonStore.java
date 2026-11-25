package net.exoego.keycloak.fuhito.jpa;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import net.exoego.keycloak.fuhito.domain.DerivedValue;
import net.exoego.keycloak.fuhito.domain.Localized;
import net.exoego.keycloak.fuhito.domain.Person;
import net.exoego.keycloak.fuhito.domain.PersonName;
import net.exoego.keycloak.fuhito.domain.PropagatedChange;
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
        return findEntity(id).map(entity -> withNames(em, realmId, List.of(entity)).getFirst());
    }

    @Override
    public Optional<Person> findByUser(String userId) {
        return withNames(em, realmId, em.createQuery("""
                        select p from PersonEntity p where p.realmId = :realmId
                        and p.userId = :userId""", PersonEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("userId", userId)
                .getResultList())
                .stream()
                .findFirst();
    }

    // The people are found by their IDs alone, for the reason in Rows.inRealm.
    @Override
    public List<Person> findAll(Collection<String> ids) {
        return ids.isEmpty() ? List.of() : withNames(em, realmId, em.createQuery("select p from PersonEntity p where %s"
                        .formatted(ManyIds.has("p.id", "ids")), PersonEntity.class)
                .setParameter("ids", ManyIds.of(ids))
                .getResultList().stream()
                .filter(Rows.inRealm(realmId, p -> p.realmId))
                .toList());
    }

    @Override
    public List<Person> findByUsers(Collection<String> userIds) {
        return userIds.isEmpty() ? List.of() : withNames(em, realmId, em.createQuery("""
                        select p from PersonEntity p where p.realmId = :realmId and %s"""
                        .formatted(ManyIds.has("p.userId", "ids")), PersonEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("ids", ManyIds.of(userIds))
                .getResultList());
    }

    @Override
    public List<Person> search(String userId, String text, String staffNumber, String afterId, int first,
            int max) {
        return withNames(em, realmId, searchQuery(userId, text, staffNumber, afterId, false, PersonEntity.class)
                .setFirstResult(first)
                .setMaxResults(max)
                .getResultList());
    }

    @Override
    public long count(String userId, String text, String staffNumber) {
        return searchQuery(userId, text, staffNumber, null, true, Long.class).getSingleResult();
    }

    private <T> TypedQuery<T> searchQuery(String userId, String text, String staffNumber, String afterId,
            boolean count, Class<T> type) {
        StringBuilder query = new StringBuilder(count ? "select count(p)" : "select p")
                .append(" from PersonEntity p where p.realmId = :realmId");
        if (userId != null) {
            query.append(" and p.userId = :userId");
        }
        if (afterId != null) {
            query.append(" and p.id > :afterId");
        }
        if (text != null) {
            // A person with a Keycloak user often has no name of its own, and takes the name of the user.
            query.append("""
                     and (exists (select 1 from PersonNameEntity n where n.personId = p.id
                    and (lower(n.name) like :text escape '!' or lower(n.phoneticName) like :text escape '!'))
                    or exists (select 1 from UserEntity u
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
        if (userId != null) {
            typed.setParameter("userId", userId);
        }
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
        return withNames(em, realmId, query.setParameter("realmId", realmId)
                .setFirstResult(first)
                .setMaxResults(max)
                .getResultList());
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
        PersonNames.insert(em, realmId, person.id(), person.names());
    }

    @Override
    public void update(Person person) {
        PersonEntity entity = findEntity(person.id()).orElseThrow();
        copy(person, entity);
        PersonNames.replace(em, realmId, person.id(), person.names());
        entity.countUp();
    }

    @Override
    public void delete(String id) {
        findEntity(id).ifPresent(entity -> {
            ExternalIds.deleteOf(em, realmId, "PERSON", id);
            PersonNames.replace(em, realmId, id, null);
            em.remove(entity);
        });
    }

    // The rows of the person are found by IDs alone, for the reason in Rows.inRealm.
    @Override
    public List<PropagatedChange> countUpReadsOfUser(String personId) {
        List<PropagatedChange> changed = new ArrayList<>();
        List<String> employmentIds = new ArrayList<>();
        for (EmploymentEntity employment : ofPerson("select e from EmploymentEntity e where e.personId = :personId",
                EmploymentEntity.class, personId, e -> e.realmId)) {
            employment.countUp();
            employmentIds.add(employment.id);
            changed.add(new PropagatedChange(DerivedValue.EMPLOYMENT_USER_ID, employment.id));
        }
        if (!employmentIds.isEmpty()) {
            Set<String> assignmentIds = em.createQuery("""
                            select a.realmId, a.assignmentId from AssignmentEntity a where %s"""
                            .formatted(ManyIds.has("a.employmentId", "ids")), Object[].class)
                    .setParameter("ids", ManyIds.of(employmentIds)).getResultList().stream()
                    .filter(Rows.inRealm(realmId, row -> (String) row[0]))
                    .map(row -> (String) row[1])
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            Versions.assignments(em, realmId, assignmentIds);
            assignmentIds.forEach(id -> changed.add(new PropagatedChange(DerivedValue.ASSIGNMENT_USER_ID, id)));
            em.createQuery("""
                            select r from UnitRoleEntity r where r.realmId = :realmId and %s"""
                            .formatted(ManyIds.has("r.employmentId", "ids")), UnitRoleEntity.class)
                    .setParameter("realmId", realmId).setParameter("ids", ManyIds.of(employmentIds))
                    .getResultList()
                    .forEach(role -> {
                        role.countUp();
                        changed.add(new PropagatedChange(DerivedValue.UNIT_ROLE_USER_ID, role.id));
                    });
        }
        for (QualificationEntity qualification : ofPerson(
                "select q from QualificationEntity q where q.personId = :personId", QualificationEntity.class,
                personId, q -> q.realmId)) {
            qualification.countUp();
            changed.add(new PropagatedChange(DerivedValue.QUALIFICATION_USER_ID, qualification.id));
        }
        for (ReportingLineEntity line : ofPerson(
                "select l from ReportingLineEntity l where l.managerPersonId = :personId", ReportingLineEntity.class,
                personId, l -> l.realmId)) {
            line.countUp();
            changed.add(new PropagatedChange(DerivedValue.REPORTING_LINE_MANAGER_USER_ID, line.id));
        }
        return changed;
    }

    private <T> List<T> ofPerson(String query, Class<T> type, String personId, Function<T, String> realmOf) {
        return em.createQuery(query, type)
                .setParameter("personId", personId)
                .getResultList().stream()
                .filter(Rows.inRealm(realmId, realmOf))
                .toList();
    }

    // The realm filter keeps one realm from reaching the rows of another by ID.
    private Optional<PersonEntity> findEntity(String id) {
        PersonEntity entity = em.find(PersonEntity.class, id);
        return entity != null && entity.realmId.equals(realmId) ? Optional.of(entity) : Optional.empty();
    }

    private static void copy(Person person, PersonEntity entity) {
        entity.userId = person.userId();
        entity.attributes = person.attributes();
    }

    /**
     * Reads the names of people in one query, and gives each person its names.
     *
     * @param em the entity manager
     * @param realmId the ID of the realm
     * @param entities the rows of the people
     * @return the people in the same order
     */
    static List<Person> withNames(EntityManager em, String realmId, List<PersonEntity> entities) {
        Map<String, Localized<PersonName>> names = PersonNames.of(em, realmId,
                entities.stream().map(e -> e.id).toList());
        return entities.stream()
                .map(e -> new Person(e.id, e.userId, names.get(e.id), e.attributes, e.source, e.version))
                .toList();
    }
}
