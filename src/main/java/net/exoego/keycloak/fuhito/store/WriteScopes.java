package net.exoego.keycloak.fuhito.store;

import java.util.Map;
import java.util.Set;

/**
 * Finds the stored resources that a write changes besides the one that its path names, so that the write can lock
 * them all before it changes any. Each result has the IDs by the first part of the path of their kind, for example
 * {@code employments}. Only the IDs are read, so the rows are still unread when the write locks them.
 */
public interface WriteScopes {

    /**
     * Finds a person with the employments and the qualifications of the person, and with what
     * {@link #employment(String)} finds for each employment. It also finds the reporting lines where the person is
     * the manager. A mask and an erase change them.
     *
     * @param personId the ID of the person
     * @return the IDs
     */
    Map<String, Set<String>> personData(String personId);

    /**
     * Finds the same as {@link #personData(String)} for the person of a user.
     *
     * @param userId the ID of the Keycloak user
     * @return the IDs, or none when the user has no person
     */
    Map<String, Set<String>> userData(String userId);

    /**
     * Finds an employment with its assignments, the reporting lines of the assignments, and its roles over org
     * units. An end and a transfer of the employment end them.
     *
     * @param employmentId the ID of the employment
     * @return the IDs
     */
    Map<String, Set<String>> employment(String employmentId);

    /**
     * Finds the same as {@link #employment(String)} for every employment of an employer. A transfer of everyone of
     * the employer ends them.
     *
     * @param employerId the ID of the org unit that employs
     * @return the IDs
     */
    Map<String, Set<String>> employer(String employerId);

    /**
     * Finds the assignments of an employment.
     *
     * @param employmentId the ID of the employment
     * @return the IDs
     */
    Map<String, Set<String>> assignmentsOfEmployment(String employmentId);

    /**
     * Finds the assignments of the employment of an assignment, with the assignment itself.
     *
     * @param assignmentId the ID of the assignment
     * @return the IDs
     */
    Map<String, Set<String>> assignmentsBeside(String assignmentId);
}
