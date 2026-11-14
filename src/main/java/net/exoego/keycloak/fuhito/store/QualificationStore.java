package net.exoego.keycloak.fuhito.store;

import java.util.List;
import java.util.Optional;

import net.exoego.keycloak.fuhito.domain.Qualification;

/**
 * Storage of the qualifications of one realm.
 */
public interface QualificationStore {

    /**
     * Finds a qualification by ID.
     *
     * @param id the ID
     * @return the qualification, or empty when the realm has no such qualification
     */
    Optional<Qualification> find(String id);

    /**
     * Lists the qualifications of a user, oldest first.
     *
     * @param userId the ID of the user
     * @return the qualifications
     */
    List<Qualification> findByUser(String userId);

    /**
     * Lists the qualifications of a person, oldest first.
     *
     * @param personId the ID of the person
     * @return the qualifications
     */
    List<Qualification> findByPerson(String personId);

    /**
     * Stores a new qualification.
     *
     * @param qualification the qualification
     */
    void insert(Qualification qualification);

    /**
     * Stores the new state of an existing qualification.
     *
     * @param qualification the qualification
     */
    void update(Qualification qualification);

    /**
     * Removes a qualification.
     *
     * @param id the ID
     */
    void delete(String id);
}
