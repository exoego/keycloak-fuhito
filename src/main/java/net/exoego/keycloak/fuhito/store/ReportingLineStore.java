package net.exoego.keycloak.fuhito.store;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import net.exoego.keycloak.fuhito.domain.DirectReport;
import net.exoego.keycloak.fuhito.domain.ReportingLine;

/**
 * Storage of the reporting lines of one realm.
 */
public interface ReportingLineStore {

    /**
     * Finds a reporting line by ID.
     *
     * @param id the ID
     * @return the line, or empty when the realm has no such line
     */
    Optional<ReportingLine> find(String id);

    /**
     * Lists the reporting lines of an assignment, oldest first.
     *
     * @param assignmentId the ID of the assignment
     * @return the lines
     */
    List<ReportingLine> findByAssignment(String assignmentId);

    /**
     * Lists the lines of several assignments, oldest first.
     *
     * @param assignmentIds the IDs of the assignments
     * @return the lines of those assignments
     */
    List<ReportingLine> findByAssignments(Collection<String> assignmentIds);

    /**
     * Lists the reporting lines to a manager, oldest first.
     *
     * @param managerPersonId the ID of the person who is the manager
     * @return the lines
     */
    List<ReportingLine> findByManager(String managerPersonId);

    /**
     * Stores a new reporting line.
     *
     * @param line the line
     */
    void insert(ReportingLine line);

    /**
     * Lists a page of the lines that hold on a date, ordered by ID.
     *
     * @param date the date
     * @param afterId the ID after which the page starts, or null for the start
     * @param max the largest number of lines
     * @return the lines
     */
    List<ReportingLine> listOn(LocalDate date, String afterId, int max);

    /**
     * Stores the new state of an existing reporting line.
     *
     * @param line the line
     */
    default void update(ReportingLine line) {
        updateAll(List.of(line));
    }

    /**
     * Stores the new state of several existing reporting lines.
     *
     * @param lines the lines
     */
    void updateAll(List<ReportingLine> lines);

    /**
     * Removes a reporting line.
     *
     * @param id the ID
     */
    default void delete(String id) {
        deleteAll(List.of(id));
    }

    /**
     * Removes several reporting lines.
     *
     * @param ids the IDs
     */
    void deleteAll(Collection<String> ids);

    /**
     * Lists a page of the assignments whose person reports to a manager on a date.
     * A stored line says so. For an assignment with no solid line, the manager is the head of its org unit, or
     * of the nearest org unit above that has a head. So the people of an org unit that the manager leads report
     * to the manager, and so do the people below it down to the next head, and that head.
     * The stored lines come first. Then the order is the distance from the org unit of the manager, the org
     * unit, and the assignment.
     *
     * @param hierarchyId the ID of the hierarchy that says what is below an org unit
     * @param managerPersonId the ID of the manager
     * @param date the date
     * @param first the number of entries to skip
     * @param max the largest number of entries to return
     * @return the entries of the page
     */
    List<DirectReport> reportsOn(String hierarchyId, String managerPersonId, LocalDate date, int first, int max);

    /**
     * Counts the entries that {@link #reportsOn} lists on all pages together.
     *
     * @param hierarchyId the ID of the hierarchy that says what is below an org unit
     * @param managerPersonId the ID of the manager
     * @param date the date
     * @return the number of entries
     */
    long countReportsOn(String hierarchyId, String managerPersonId, LocalDate date);

    /**
     * Ends the reporting lines of assignments that hold on or after a date.
     * A line that would start on or after the date is removed.
     *
     * @param assignmentIds the IDs of the assignments
     * @param to the first date on which no line of the assignments holds
     */
    default void truncate(Collection<String> assignmentIds, LocalDate to) {
        List<String> removed = new ArrayList<>();
        List<ReportingLine> ended = new ArrayList<>();
        for (ReportingLine line : findByAssignments(assignmentIds)) {
            if (!line.period().from().isBefore(to)) {
                removed.add(line.id());
            } else if (line.period().endsAfter(to)) {
                ended.add(line.withPeriod(line.period().endingAt(to)));
            }
        }
        deleteAll(removed);
        updateAll(ended);
    }
}
