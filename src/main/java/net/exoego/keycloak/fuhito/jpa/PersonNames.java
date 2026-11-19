package net.exoego.keycloak.fuhito.jpa;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import jakarta.persistence.EntityManager;
import net.exoego.keycloak.fuhito.domain.Localized;
import net.exoego.keycloak.fuhito.domain.PersonName;

/**
 * The names of people, with one row for each language.
 */
final class PersonNames {

    private PersonNames() {
    }

    /**
     * Reads the names of many people in one query.
     *
     * @param em the entity manager
     * @param realmId the ID of the realm
     * @param personIds the IDs of the people
     * @return the names by the ID of the person. A person with no names is not in it.
     */
    static Map<String, Localized<PersonName>> of(EntityManager em, String realmId, Collection<String> personIds) {
        return personIds.isEmpty() ? Map.of() : names(rows(em, realmId, personIds));
    }

    /**
     * Stores the names of a new person.
     *
     * @param em the entity manager
     * @param realmId the ID of the realm
     * @param personId the ID of the person
     * @param names the names, or null for none
     */
    static void insert(EntityManager em, String realmId, String personId, Localized<PersonName> names) {
        if (names == null) {
            return;
        }
        names.byLanguage().forEach((language, name) -> {
            PersonNameEntity row = new PersonNameEntity();
            row.personId = personId;
            row.language = language;
            row.realmId = realmId;
            row.primary = language.equals(names.primaryLanguage());
            row.name = name.name();
            row.phoneticName = name.phoneticName();
            em.persist(row);
        });
    }

    /**
     * Stores the names of a stored person in place of the ones it has.
     *
     * @param em the entity manager
     * @param realmId the ID of the realm
     * @param personId the ID of the person
     * @param names the names, or null to remove them all
     */
    static void replace(EntityManager em, String realmId, String personId, Localized<PersonName> names) {
        List<PersonNameEntity> stored = rows(em, realmId, List.of(personId));
        if (Objects.equals(names, names(stored).get(personId))) {
            return;
        }
        stored.forEach(em::remove);
        // A new row can have the key of an old one, and only one row of a person is primary.
        em.flush();
        insert(em, realmId, personId, names);
    }

    private static Map<String, Localized<PersonName>> names(List<PersonNameEntity> rows) {
        Map<String, Map<String, PersonName>> byPerson = new HashMap<>();
        Map<String, String> primary = new HashMap<>();
        for (PersonNameEntity row : rows) {
            byPerson.computeIfAbsent(row.personId, id -> new HashMap<>())
                    .put(row.language, new PersonName(row.name, row.phoneticName));
            if (row.primary) {
                primary.put(row.personId, row.language);
            }
        }
        Map<String, Localized<PersonName>> result = new HashMap<>();
        byPerson.forEach((personId, names) -> result.put(personId, new Localized<>(primary.get(personId), names)));
        return result;
    }

    private static List<PersonNameEntity> rows(EntityManager em, String realmId, Collection<String> personIds) {
        return em.createQuery("""
                        select n from PersonNameEntity n where n.realmId = :realmId and %s"""
                        .formatted(ManyIds.has("n.personId", "ids")), PersonNameEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("ids", ManyIds.of(personIds))
                .getResultList();
    }
}
