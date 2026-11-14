package net.exoego.keycloak.fuhito.store;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

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
     * Stores the new state of an existing reporting line.
     *
     * @param line the line
     */
    void update(ReportingLine line);

    /**
     * Removes a reporting line.
     *
     * @param id the ID
     */
    void delete(String id);

    /**
     * Ends the reporting lines of an assignment that hold on or after a date.
     * A line that would start on or after the date is removed.
     *
     * @param assignmentId the ID of the assignment
     * @param to the first date on which no line of the assignment holds
     */
    default void truncate(String assignmentId, LocalDate to) {
        for (ReportingLine line : findByAssignment(assignmentId)) {
            if (!line.period().from().isBefore(to)) {
                delete(line.id());
            } else if (line.period().endsAfter(to)) {
                update(line.withPeriod(line.period().endingAt(to)));
            }
        }
    }
}
