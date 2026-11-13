package net.exoego.keycloak.fuhito.store;

import java.util.List;

import net.exoego.keycloak.fuhito.domain.Assignment;

/**
 * Storage of the assignments of one realm.
 */
public interface AssignmentStore {

    /**
     * Lists all rows of an assignment, oldest first.
     *
     * @param assignmentId the ID of the assignment
     * @return the rows, or an empty list when the realm has no such assignment
     */
    List<Assignment> rows(String assignmentId);

    /**
     * Lists all rows of all assignments of an employment, oldest first.
     *
     * @param employmentId the ID of the employment
     * @return the rows
     */
    List<Assignment> rowsOfEmployment(String employmentId);

    /**
     * Stores a new row.
     *
     * @param row the row
     */
    void insert(Assignment row);

    /**
     * Stores the new state of an existing row.
     *
     * @param row the row
     */
    void update(Assignment row);
}
