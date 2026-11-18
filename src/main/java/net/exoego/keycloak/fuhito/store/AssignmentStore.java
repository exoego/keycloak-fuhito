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
    void insert(Assignment row);

    /**
     * Stores the new state of an existing row.
     * The link to the assignment that it continues never changes, so only {@link #insert} writes it.
     *
     * @param row the row
     */
    void update(Assignment row);

    /**
     * Removes a row. The IDs that systems have for the assignment go with its last row.
     *
     * @param rowId the ID of the row
     */
    void delete(String rowId);

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
