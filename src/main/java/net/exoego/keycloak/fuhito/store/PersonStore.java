package net.exoego.keycloak.fuhito.store;

import java.util.List;
import java.util.Optional;

import net.exoego.keycloak.fuhito.domain.Person;

/**
 * Storage of the people of one realm.
 */
public interface PersonStore {

    /**
     * Finds a person by ID.
     *
     * @param id the ID
     * @return the person, or empty when the realm has no such person
     */
    Optional<Person> find(String id);

    /**
     * Finds the person that is linked to a Keycloak user.
     *
     * @param userId the ID of the user
     * @return the person, or empty when no person is linked to the user
     */
    Optional<Person> findByUser(String userId);

    /**
     * Finds a person by the ID in the system it came from.
     *
     * @param source the name of that system
     * @param externalId the ID in that system
     * @return the person, or empty when the realm has no such person
     */
    Optional<Person> findByExternalId(String source, String externalId);

    /**
     * Lists the people of the realm, ordered by ID.
     *
     * @param first the number of people to skip
     * @param max the largest number of people to return
     * @return the people
     */
    List<Person> list(int first, int max);

    /**
     * Stores a new person.
     *
     * @param person the person
     */
    void insert(Person person);

    /**
     * Stores the new state of an existing person.
     *
     * @param person the person
     */
    void update(Person person);

    /**
     * Removes a person.
     *
     * @param id the ID
     */
    void delete(String id);
}
