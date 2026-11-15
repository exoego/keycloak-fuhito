package net.exoego.keycloak.fuhito.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.exoego.keycloak.fuhito.domain.Assignment;
import net.exoego.keycloak.fuhito.domain.Employment;
import net.exoego.keycloak.fuhito.domain.ExternalId;
import net.exoego.keycloak.fuhito.domain.ReportingLine;
import net.exoego.keycloak.fuhito.store.AssignmentStore;
import net.exoego.keycloak.fuhito.store.EmploymentStore;
import net.exoego.keycloak.fuhito.store.ExternalIdStore;
import net.exoego.keycloak.fuhito.store.PersonStore;
import net.exoego.keycloak.fuhito.store.ReportingLineStore;
import net.exoego.keycloak.fuhito.store.UnitRoleStore;

/**
 * What happens to the data about a user when the user is removed from Keycloak.
 * The history stays. Only what would still hold after today ends today.
 */
public class UserRetirement {

    private final EmploymentStore employments;
    private final EmploymentService employmentService;
    private final AssignmentStore assignments;
    private final ReportingLineStore lines;
    private final PersonStore people;
    private final UnitRoleStore unitRoles;
    private final ExternalIdStore externalIds;
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param employments the storage of employments of the realm
     * @param employmentService the operations on employments of the realm
     * @param assignments the storage of assignments of the realm
     * @param lines the storage of reporting lines of the realm
     * @param people the storage of people of the realm
     * @param unitRoles the storage of roles over org units of the realm
     * @param externalIds the storage of the IDs of other systems of the realm
     * @param clock the clock that decides what "today" is
     */
    public UserRetirement(EmploymentStore employments, EmploymentService employmentService,
            AssignmentStore assignments, ReportingLineStore lines, PersonStore people, UnitRoleStore unitRoles,
            ExternalIdStore externalIds, Clock clock) {
        this.people = people;
        this.unitRoles = unitRoles;
        this.externalIds = externalIds;
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
        List<Employment> all = employments.findByUser(userId);
        // A later employment can continue an earlier one, so the later one goes first.
        all.reversed().stream()
                .filter(employment -> !employment.period().from().isBefore(today))
                .forEach(this::removeNeverHeld);
        for (Employment employment : all) {
            if (employment.period().from().isBefore(today) && employment.period().endsAfter(today)) {
                ended.add(employmentService.end(employment.id(), today.toString(), null));
            }
        }
        List<ReportingLine> managed = people.findByUser(userId).map(person -> lines.findByManager(person.id()))
                .orElse(List.of());
        for (ReportingLine line : managed) {
            if (!line.period().from().isBefore(today)) {
                lines.delete(line.id());
            } else if (line.period().endsAfter(today)) {
                lines.update(line.withPeriod(line.period().endingAt(today)));
            }
        }
        return ended;
    }

    private void removeNeverHeld(Employment employment) {
        unitRoles.findByEmployment(employment.id()).forEach(role -> unitRoles.delete(role.id()));
        Set<String> assignmentIds = new LinkedHashSet<>();
        for (Assignment row : assignments.rowsOfEmployment(employment.id())) {
            lines.findByAssignment(row.assignmentId()).forEach(line -> lines.delete(line.id()));
            assignments.delete(row.rowId());
            assignmentIds.add(row.assignmentId());
        }
        assignmentIds.forEach(id -> removeExternalIds(ExternalId.Kind.ASSIGNMENT, id));
        removeExternalIds(ExternalId.Kind.EMPLOYMENT, employment.id());
        employments.terms(employment.id()).forEach(terms -> employments.deleteTerms(terms.id()));
        employments.delete(employment.id());
    }

    private void removeExternalIds(ExternalId.Kind kind, String resourceId) {
        externalIds.ofResource(kind, resourceId).forEach(row -> externalIds.delete(row.id()));
    }
}
