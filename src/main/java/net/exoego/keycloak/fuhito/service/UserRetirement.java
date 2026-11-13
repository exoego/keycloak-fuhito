package net.exoego.keycloak.fuhito.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import net.exoego.keycloak.fuhito.domain.Assignment;
import net.exoego.keycloak.fuhito.domain.Employment;
import net.exoego.keycloak.fuhito.domain.ReportingLine;
import net.exoego.keycloak.fuhito.store.AssignmentStore;
import net.exoego.keycloak.fuhito.store.EmploymentStore;
import net.exoego.keycloak.fuhito.store.ReportingLineStore;

/**
 * What happens to the data about a user when the user is removed from Keycloak.
 * The history stays. Only what would still hold after today ends today.
 */
public class UserRetirement {

    private final EmploymentStore employments;
    private final EmploymentService employmentService;
    private final AssignmentStore assignments;
    private final ReportingLineStore lines;
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param employments the storage of employments of the realm
     * @param employmentService the operations on employments of the realm
     * @param assignments the storage of assignments of the realm
     * @param lines the storage of reporting lines of the realm
     * @param clock the clock that decides what "today" is
     */
    public UserRetirement(EmploymentStore employments, EmploymentService employmentService,
            AssignmentStore assignments, ReportingLineStore lines, Clock clock) {
        this.employments = employments;
        this.employmentService = employmentService;
        this.assignments = assignments;
        this.lines = lines;
        this.clock = clock;
    }

    /**
     * Ends today what would still hold for a removed user, and keeps everything else.
     * An employment that is open today ends today, with its terms, its assignments, and their reporting lines.
     * An employment that would start today or later is removed, because it never held.
     * A reporting line where the user is the manager ends today.
     *
     * @param userId the ID of the removed user
     * @return the employments that ended today, for the change log
     */
    public List<EmploymentView> retire(String userId) {
        LocalDate today = LocalDate.now(clock);
        List<EmploymentView> ended = new ArrayList<>();
        for (Employment employment : employments.findByUser(userId)) {
            if (!employment.period().from().isBefore(today)) {
                removeNeverHeld(employment);
            } else if (employment.period().to() == null || employment.period().to().isAfter(today)) {
                ended.add(employmentService.end(employment.id(), today.toString(), null));
            }
        }
        for (ReportingLine line : lines.findByManager(userId)) {
            if (!line.period().from().isBefore(today)) {
                lines.delete(line.id());
            } else if (line.period().to() == null || line.period().to().isAfter(today)) {
                lines.update(line.withPeriod(line.period().endingAt(today)));
            }
        }
        return ended;
    }

    private void removeNeverHeld(Employment employment) {
        for (Assignment row : assignments.rowsOfEmployment(employment.id())) {
            lines.findByAssignment(row.assignmentId()).forEach(line -> lines.delete(line.id()));
            assignments.delete(row.rowId());
        }
        employments.terms(employment.id()).forEach(terms -> employments.deleteTerms(terms.id()));
        employments.delete(employment.id());
    }
}
