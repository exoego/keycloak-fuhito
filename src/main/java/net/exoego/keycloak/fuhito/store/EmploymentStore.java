package net.exoego.keycloak.fuhito.store;

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
     * Lists the employments that use a staff number.
     *
     * @param staffNumber the staff number
     * @return the employments
     */
    List<Employment> findByStaffNumber(String staffNumber);

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
     * Removes terms.
     *
     * @param termsId the ID of the terms
     */
    void deleteTerms(String termsId);
}
