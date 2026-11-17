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
     * Lists the people of the realm, ordered by ID.
     *
     * @param afterId the ID of the person that the list goes on after, or null to start at the first one
     * @param first the number of people to skip
     * @param max the largest number of people to return
     * @return the people
     */
    List<Person> list(String afterId, int first, int max);

    /**
     * Finds people of the realm, ordered by ID.
     *
     * @param text a text that the name, the phonetic name, a name in another language, or the name, the username,
     *        or the email of the Keycloak user has, in any case, or null for any
     * @param staffNumber a staff number that an employment of the person has, or null for any
     * @param afterId the ID of the person that the list goes on after, or null to start at the first one
     * @param first the number of people to skip
     * @param max the largest number of people to return
     * @return the people
     */
    List<Person> search(String text, String staffNumber, String afterId, int first, int max);

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
     * Removes a person with the IDs that systems have for it.
     *
     * @param id the ID
     */
    void delete(String id);
}
