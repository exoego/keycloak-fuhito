package net.exoego.keycloak.fuhito.store;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.exoego.keycloak.fuhito.domain.Assignment;
import net.exoego.keycloak.fuhito.domain.MemberCursor;
import net.exoego.keycloak.fuhito.domain.MemberFilter;

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
     * Lists all rows of many assignments, by the ID of the assignment and then oldest first.
     *
     * @param assignmentIds the IDs of the assignments
     * @return the rows
     */
    List<Assignment> rowsOf(Collection<String> assignmentIds);

    /**
     * Lists all rows of all assignments of an employment, oldest first.
     *
     * @param employmentId the ID of the employment
     * @return the rows
     */
    List<Assignment> rowsOfEmployment(String employmentId);

    /**
     * Lists all rows of all assignments of several employments. The rows of one employment come oldest first.
     *
     * @param employmentIds the IDs of the employments
     * @return the rows
     */
    default List<Assignment> rowsOfEmployments(Collection<String> employmentIds) {
        return employmentIds.stream().distinct().flatMap(id -> rowsOfEmployment(id).stream()).toList();
    }

    /**
     * Finds the assignments that end on the start of each of some assignments. Such an assignment is of the same
     * person, in any employment of the person. Its last row ends on the first date of the other assignment, and has
     * the primary flag of the first row of the other one.
     *
     * @param assignmentIds the IDs of the assignments
     * @return the last row of each assignment that ends on the start of one of them, by the ID of that one, in the
     *         order of the IDs of the assignments that end. An assignment with none is left out.
     */
    Map<String, List<Assignment>> endedOnStart(Collection<String> assignmentIds);

    /**
     * Lists the first row of each assignment of the people of some employments, in every employment of each person.
     *
     * @param employmentIds the IDs of the employments
     * @return the first rows by the ID of each of the employments. An employment of a person with no assignment is
     *         left out.
     */
    Map<String, List<Assignment>> firstRowsOfPeople(Collection<String> employmentIds);

    /**
     * Counts up the versions of assignments whose read changed although no write changed their rows. An assignment
     * that a write of the transaction counted up already keeps its version.
     *
     * @param assignmentIds the IDs of the assignments
     */
    void countUp(Collection<String> assignmentIds);

    /**
     * Counts the assignments to an org unit that hold on a date or after it.
     *
     * @param orgUnitId the ID of the org unit
     * @param date the date
     * @return the number of assignments
     */
    long assignmentsHoldingFrom(String orgUnitId, LocalDate date);

    /**
     * Lists the rows that are valid on a date in any of the given org units.
     *
     * @param orgUnitIds the IDs of the org units, at least one
     * @param date the date
     * @return the rows
     */
    List<Assignment> rowsOn(Collection<String> orgUnitIds, LocalDate date);

    /**
     * Lists the Keycloak users of the rows that are valid on a date in any of the given org units.
     * A row whose person has no Keycloak user is left out.
     *
     * @param orgUnitIds the IDs of the org units, at least one
     * @param date the date
     * @return the IDs of the users by the ID of their org unit. A unit with no such row is left out.
     */
    Map<String, Set<String>> usersOn(Collection<String> orgUnitIds, LocalDate date);

    /**
     * Lists a page of the rows of the members of an org unit on a date.
     * With descendants, the units come in tree order.
     * Inside one unit, the rows follow the display order, then the start of the row, then the assignment ID.
     *
     * @param filter which assignments to take
     * @param after the last row of the page before, or null to start at the first row
     * @param first the number of rows to skip
     * @param max the largest number of rows to return
     * @return the rows of the page
     */
    List<Assignment> membersOn(MemberFilter filter, MemberCursor after, int first, int max);

    /**
     * Counts the assignments that the same filter as {@link #membersOn} keeps, on all pages.
     *
     * @param filter the unit, the date, and what to keep
     * @return the number of assignments
     */
    long countMembersOn(MemberFilter filter);

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
    default void insert(Assignment row) {
        insertAll(List.of(row));
    }

    /**
     * Stores new rows in their order. A row of an assignment that has rows already takes their version.
     *
     * @param rows the rows
     */
    void insertAll(List<Assignment> rows);

    /**
     * Stores the new state of an existing row.
     *
     * @param row the row
     */
    default void update(Assignment row) {
        updateAll(List.of(row));
    }

    /**
     * Stores the new state of several existing rows, as {@link #update} says.
     *
     * @param rows the rows
     */
    void updateAll(List<Assignment> rows);

    /**
     * Removes a row. The IDs that systems have for the assignment go with its last row.
     *
     * @param rowId the ID of the row
     */
    default void delete(String rowId) {
        deleteAll(List.of(rowId));
    }

    /**
     * Removes several rows, as {@link #delete} says.
     *
     * @param rowIds the IDs of the rows
     */
    void deleteAll(Collection<String> rowIds);

    /**
     * What a list of assignments across the realm keeps. A value that is null keeps everything.
     *
     * @param orgUnitId the ID of the org unit
     * @param jobTitleId the ID of the job title
     * @param occupationId the ID of the occupation
     * @param kindId the ID of the assignment kind
     * @param date the date on which the rows hold
     */
    record Search(String orgUnitId, String jobTitleId, String occupationId, String kindId, LocalDate date) {
    }

    /**
     * Lists a page of the rows of a date that match a search, by the ID of the assignment.
     *
     * @param search what to keep
     * @param afterAssignmentId the ID of the assignment after which the page starts, or null for the start
     * @param first the number of rows to skip
     * @param max the largest number of rows to return
     * @return the rows of the page
     */
    List<Assignment> searchOn(Search search, String afterAssignmentId, int first, int max);

    /**
     * Counts the rows of a date that match a search.
     *
     * @param search what to keep
     * @return the number of rows
     */
    long countSearchOn(Search search);
}
