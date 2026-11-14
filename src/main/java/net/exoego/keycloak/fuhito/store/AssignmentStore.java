package net.exoego.keycloak.fuhito.store;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

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
     * Lists all rows of all assignments to an org unit, oldest first.
     *
     * @param orgUnitId the ID of the org unit
     * @return the rows
     */
    List<Assignment> rowsOfOrgUnit(String orgUnitId);

    /**
     * Lists the rows that are valid on a date in any of the given org units.
     *
     * @param orgUnitIds the IDs of the org units, at least one
     * @param date the date
     * @return the rows
     */
    List<Assignment> rowsOn(Collection<String> orgUnitIds, LocalDate date);

    /**
     * Finds the ID of an assignment by the ID it has in the system it came from.
     *
     * @param source the name of that system
     * @param externalId the ID in that system
     * @return the assignment ID, or empty when the realm has no such assignment
     */
    Optional<String> findAssignmentIdByExternalId(String source, String externalId);

    /**
     * Lists the rows with a source that are valid on a date.
     *
     * @param source the name of the system that the rows came from
     * @param date the date
     * @return the rows
     */
    List<Assignment> rowsBySourceOn(String source, LocalDate date);

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

    /**
     * Removes a row.
     *
     * @param rowId the ID of the row
     */
    void delete(String rowId);
}
