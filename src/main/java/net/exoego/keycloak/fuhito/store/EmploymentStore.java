package net.exoego.keycloak.fuhito.store;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import net.exoego.keycloak.fuhito.domain.Employment;
import net.exoego.keycloak.fuhito.domain.EmploymentTerms;

/**
 * Storage of the employments of one realm.
 */
public interface EmploymentStore {

    /**
     * Finds an employment by ID.
     *
     * @param id the ID
     * @return the employment, or empty when the realm has no such employment
     */
    Optional<Employment> find(String id);

    /**
     * Finds an employment by the ID it has in the system it came from.
     *
     * @param source the name of that system
     * @param externalId the ID in that system
     * @return the employment, or empty when the realm has no such employment
     */
    Optional<Employment> findByExternalId(String source, String externalId);

    /**
     * Lists all employments of the realm, oldest first.
     *
     * @return the employments
     */
    List<Employment> all();

    /**
     * Lists the employments that use a staff number.
     *
     * @param staffNumber the staff number
     * @return the employments
     */
    List<Employment> findByStaffNumber(String staffNumber);

    /**
     * Lists the employments of a user, oldest first.
     *
     * @param userId the ID of the user
     * @return the employments
     */
    List<Employment> findByUser(String userId);

    /**
     * Lists the employments with the given IDs.
     *
     * @param ids the IDs
     * @return the employments that exist in the realm
     */
    List<Employment> findAll(Collection<String> ids);

    /**
     * Lists the employments of a person, oldest first.
     *
     * @param personId the ID of the person
     * @return the employments
     */
    List<Employment> findByPerson(String personId);

    /**
     * Lists the terms that are valid on a date for the given employments.
     *
     * @param employmentIds the IDs of the employments
     * @param date the date
     * @return at most one set of terms for each employment
     */
    List<EmploymentTerms> termsOn(Collection<String> employmentIds, LocalDate date);

    /**
     * Stores a new employment.
     *
     * @param employment the employment
     */
    void insert(Employment employment);

    /**
     * Lists all terms of an employment, oldest first.
     *
     * @param employmentId the ID of the employment
     * @return the terms
     */
    List<EmploymentTerms> terms(String employmentId);

    /**
     * Stores new terms.
     *
     * @param terms the terms
     */
    void insertTerms(EmploymentTerms terms);

    /**
     * Stores the new state of existing terms.
     *
     * @param terms the terms
     */
    void updateTerms(EmploymentTerms terms);

    /**
     * Stores the new state of an existing employment.
     *
     * @param employment the employment
     */
    void update(Employment employment);

    /**
     * Removes an employment. Its terms and assignments must be removed first.
     *
     * @param id the ID
     */
    void delete(String id);

    /**
     * Removes terms.
     *
     * @param termsId the ID of the terms
     */
    void deleteTerms(String termsId);
}
