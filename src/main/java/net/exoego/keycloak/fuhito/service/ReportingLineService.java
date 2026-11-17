package net.exoego.keycloak.fuhito.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import net.exoego.keycloak.fuhito.domain.Assignment;
import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.Employment;
import net.exoego.keycloak.fuhito.domain.EmploymentStatus;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.Person;
import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.domain.ReportingLine.Kind;
import net.exoego.keycloak.fuhito.domain.ReportingLine;
import net.exoego.keycloak.fuhito.domain.ValidPeriod;
import net.exoego.keycloak.fuhito.store.AssignmentStore;
import net.exoego.keycloak.fuhito.store.EmploymentStore;
import net.exoego.keycloak.fuhito.store.OrgUnitStore;
import net.exoego.keycloak.fuhito.store.PersonStore;
import net.exoego.keycloak.fuhito.store.RefItemStore;
import net.exoego.keycloak.fuhito.store.ReportingLineStore;
import net.exoego.keycloak.fuhito.store.UserDirectory;

/**
 * Operations on the reporting lines of one realm, and the reads that answer who manages whom.
 */
public class ReportingLineService {

    /**
     * The stores that the service reads besides its own.
     *
     * @param assignments the storage of assignments of the realm
     * @param employments the storage of employments of the realm
     * @param orgUnits the storage of org units of the realm
     * @param refItems the storage of reference items of the realm
     * @param users the users of the realm
     */
    public record Sources(AssignmentStore assignments, EmploymentStore employments, OrgUnitStore orgUnits,
            RefItemStore refItems, UserDirectory users, PersonStore people) {
    }

    private final ReportingLineStore store;
    private final Sources sources;
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param store the storage of reporting lines of the realm
     * @param sources the other stores of the realm
     * @param clock the clock that decides what "today" is
     */
    public ReportingLineService(ReportingLineStore store, Sources sources, Clock clock) {
        this.store = store;
        this.sources = sources;
        this.clock = clock;
    }

    /**
     * Adds a reporting line from an assignment to a manager.
     * With no end date, the line lasts as long as the assignment.
     *
     * @param assignmentId the ID of the assignment of the person who reports
     * @param managerPersonId the ID of the person who is the manager
     * @param kind {@code SOLID} or {@code DOTTED}, or null for {@code SOLID}
     * @param validFrom the first date of the line, as {@code YYYY-MM-DD}
     * @param validTo the first date after the line, or null
     * @return the new line
     * @throws FuhitoException when a value is bad, the line is not inside the assignment, the manager is the
     *         person, or the assignment has a solid line on the same dates already
     */
    public ReportingLine add(String assignmentId, String managerPersonId, String kind, String validFrom,
            String validTo) {
        Checks.required("assignmentId", assignmentId);
        Checks.required("managerPersonId", managerPersonId);
        Kind lineKind = Checks.optionalEnum("kind", Kind.class, kind, Kind.SOLID);
        LocalDate from = Checks.date("validFrom", validFrom);
        LocalDate to = Checks.optionalDate("validTo", validTo);
        List<Assignment> rows = sources.assignments().rows(assignmentId);
        if (rows.isEmpty()) {
            throw FuhitoException.invalid("reporting_line.assignment_unknown", Map.of("assignmentId", assignmentId));
        }
        ValidPeriod life = new ValidPeriod(rows.getFirst().period().from(), rows.getLast().period().to());
        ValidPeriod period = Checks.period(from, to == null ? life.to() : to);
        if (!life.encloses(period)) {
            throw FuhitoException.conflict("reporting_line.outside_assignment",
                    Map.of("assignmentId", assignmentId, "validFrom", validFrom));
        }
        Person manager = sources.people().find(managerPersonId).orElseThrow(() -> FuhitoException.invalid(
                "reporting_line.manager_unknown", Map.of("managerPersonId", managerPersonId)));
        if (managerPersonId.equals(employmentOf(rows.getFirst()).map(Employment::personId).orElse(null))) {
            throw FuhitoException.invalid("reporting_line.self", Map.of("managerPersonId", managerPersonId));
        }
        if (lineKind == Kind.SOLID) {
            store.findByAssignment(assignmentId).stream()
                    .filter(other -> other.kind() == Kind.SOLID && other.period().overlaps(period))
                    .findFirst()
                    .ifPresent(other -> {
                        throw FuhitoException.conflict("reporting_line.solid_exists",
                                Map.of("assignmentId", assignmentId, "lineId", other.id()));
                    });
        }
        ReportingLine line = new ReportingLine(UUID.randomUUID().toString(), assignmentId, managerPersonId,
                manager.userId(), lineKind, period);
        store.insert(line);
        return line;
    }

    /**
     * Returns a reporting line.
     *
     * @param id the ID
     * @return the line
     * @throws FuhitoException when the realm has no such line
     */
    public ReportingLine get(String id) {
        return store.find(id)
                .orElseThrow(() -> FuhitoException.notFound("reporting_line.not_found", Map.of("id", id)));
    }

    /**
     * Finds a reporting line.
     *
     * @param id the ID
     * @return the line, or empty when the realm has no such line
     */
    public Optional<ReportingLine> find(String id) {
        return store.find(id);
    }

    /**
     * Lists the lines of an assignment, oldest first.
     *
     * @param assignmentId the ID of the assignment
     * @return the lines
     */
    public List<ReportingLine> ofAssignment(String assignmentId) {
        return store.findByAssignment(assignmentId);
    }

    /**
     * Lists a page of the lines that hold on a date, ordered by ID.
     *
     * @param date the date
     * @param afterId the ID after which the page starts, or null for the start
     * @param max the largest number of lines
     * @return the lines
     */
    public List<ReportingLine> listOn(LocalDate date, String afterId, int max) {
        return store.listOn(date, afterId, max);
    }

    /**
     * Ends a reporting line earlier.
     *
     * @param id the ID
     * @param validTo the first date on which the line no longer holds, as {@code YYYY-MM-DD}
     * @return the changed line
     * @throws FuhitoException when the date is not inside the line
     */
    public ReportingLine end(String id, String validTo) {
        LocalDate to = Checks.date("validTo", validTo);
        ReportingLine line = get(id);
        boolean inside = to.isAfter(line.period().from())
                && (line.period().to() == null || !to.isAfter(line.period().to()));
        if (!inside) {
            throw FuhitoException.invalid("invalid.period",
                    Map.of("validFrom", line.period().from().toString(), "validTo", validTo));
        }
        ReportingLine changed = line.withPeriod(line.period().endingAt(to));
        store.update(changed);
        return changed;
    }

    /**
     * Deletes a reporting line, as if it had never been stored.
     *
     * @param id the ID
     * @throws FuhitoException when the line does not exist
     */
    public void delete(String id) {
        get(id);
        store.delete(id);
    }

    /**
     * Lists the managers of a user on a date, for each assignment of the user.
     * A stored line gives the manager. An assignment with no solid line takes the head of its org unit.
     * A head, and a member of a unit with no head, take the head of the nearest unit above that has one.
     * An assignment in a lateral unit gets no derived manager, and the head of a lateral unit is nobody's derived
     * manager.
     * A head is absent while the status of the employment is not {@code ACTIVE}. A present acting head leads the
     * unit while its assignment holds, and the other heads look above the unit. With no present acting head, the
     * present heads lead.
     *
     * @param userId the ID of the user
     * @param asOf the date as {@code YYYY-MM-DD}, or null for today
     * @return the managers
     * @throws FuhitoException when the user does not exist
     */
    public List<Reporting> managersOf(String userId, String asOf) {
        LocalDate date = dateOrToday(asOf);
        requireUser(userId);
        return personOf(userId).map(personId -> managers(personId, date)).orElse(List.of());
    }

    /**
     * Lists the managers of a person on a date, for each assignment of the person.
     *
     * @param personId the ID of the person
     * @param asOf the date as {@code YYYY-MM-DD}, or null for today
     * @return the managers
     * @throws FuhitoException when the date is bad
     */
    public List<Reporting> managersOfPerson(String personId, String asOf) {
        return managers(personId, dateOrToday(asOf));
    }

    private List<Reporting> managers(String personId, LocalDate date) {
        Snapshot snapshot = new Snapshot(date);
        List<Reporting> result = new ArrayList<>();
        for (Assignment row : snapshot.rowsOfPerson(personId)) {
            List<ReportingLine> lines = snapshot.linesOf(row.assignmentId());
            lines.forEach(line -> result.add(new Reporting(line.managerPersonId(), line.managerUserId(),
                    line.kind(), false, line.id(), row.assignmentId(), row.orgUnitId())));
            if (lines.stream().noneMatch(line -> line.kind() == Kind.SOLID)) {
                snapshot.headsAbove(row.orgUnitId(), personId).forEach(head -> result.add(new Reporting(
                        snapshot.personOf(head), snapshot.userOf(head), Kind.SOLID, true, null,
                        row.assignmentId(), row.orgUnitId())));
            }
        }
        return result;
    }

    /**
     * Lists a page of the direct reports of a user on a date, by the same rules as {@link #managersOf}.
     * The stored lines come first. Then the order is the distance from the org unit of the manager, the org
     * unit, and the assignment.
     *
     * @param userId the ID of the user
     * @param asOf the date as {@code YYYY-MM-DD}, or null for today
     * @param page the part of the list to return
     * @return the direct reports of the page
     * @throws FuhitoException when the user does not exist
     */
    public List<Reporting> reportsOf(String userId, String asOf, Page page) {
        LocalDate date = dateOrToday(asOf);
        requireUser(userId);
        return personOf(userId).map(personId -> reports(personId, date, page)).orElse(List.of());
    }

    /**
     * Lists a page of the direct reports of a person on a date.
     *
     * @param personId the ID of the person
     * @param asOf the date as {@code YYYY-MM-DD}, or null for today
     * @param page the part of the list to return
     * @return the direct reports of the page
     * @throws FuhitoException when the date is bad
     */
    public List<Reporting> reportsOfPerson(String personId, String asOf, Page page) {
        return reports(personId, dateOrToday(asOf), page);
    }

    /**
     * Counts the direct reports that {@link #reportsOf} lists on all pages together.
     *
     * @param userId the ID of the user
     * @param asOf the date as {@code YYYY-MM-DD}, or null for today
     * @return the number of direct reports
     * @throws FuhitoException when the user does not exist
     */
    public long countReportsOf(String userId, String asOf) {
        LocalDate date = dateOrToday(asOf);
        requireUser(userId);
        return personOf(userId).map(personId -> countReports(personId, date)).orElse(0L);
    }

    /**
     * Counts the direct reports that {@link #reportsOfPerson} lists on all pages together.
     *
     * @param personId the ID of the person
     * @param asOf the date as {@code YYYY-MM-DD}, or null for today
     * @return the number of direct reports
     * @throws FuhitoException when the date is bad
     */
    public long countReportsOfPerson(String personId, String asOf) {
        return countReports(personId, dateOrToday(asOf));
    }

    private long countReports(String personId, LocalDate date) {
        return store.countReportsOn(sources.orgUnits().defaultHierarchyId(), personId, date);
    }

    private List<Reporting> reports(String personId, LocalDate date, Page page) {
        return store.reportsOn(sources.orgUnits().defaultHierarchyId(), personId, date, page.first(), page.max())
                .stream()
                .map(report -> new Reporting(report.personId(), report.userId(), report.kind(), report.derived(),
                        report.lineId(), report.assignmentId(), report.orgUnitId()))
                .toList();
    }

    private Optional<Employment> employmentOf(Assignment row) {
        return sources.employments().find(row.employmentId());
    }

    // A user with no person has no employment, so nobody reports to them and they report to nobody.
    private Optional<String> personOf(String userId) {
        return sources.people().findByUser(userId).map(Person::id);
    }

    private void requireUser(String userId) {
        if (!sources.users().isKnown(userId)) {
            throw FuhitoException.notFound("user.not_found", Map.of("userId", userId));
        }
    }

    private LocalDate dateOrToday(String asOf) {
        return asOf == null ? LocalDate.now(clock) : Checks.optionalDate("asOf", asOf);
    }

    /**
     * The tree, the assignments, and the lines as they are on one date.
     */
    private final class Snapshot {

        private final LocalDate date;
        private final Map<String, Optional<Employment>> employments = new HashMap<>();

        Snapshot(LocalDate date) {
            this.date = date;
        }

        private Optional<Employment> employment(Assignment row) {
            return employments.computeIfAbsent(row.employmentId(), id -> employmentOf(row));
        }

        String personOf(Assignment row) {
            return employment(row).map(Employment::personId).orElse(null);
        }

        String userOf(Assignment row) {
            return employment(row).map(Employment::userId).orElse(null);
        }

        List<Assignment> rowsOfPerson(String personId) {
            return sources.employments().findByPerson(personId).stream()
                    .flatMap(employment -> sources.assignments().rowsOfEmployment(employment.id()).stream())
                    .filter(row -> row.period().contains(date))
                    .toList();
        }

        List<ReportingLine> linesOf(String assignmentId) {
            return store.findByAssignment(assignmentId).stream()
                    .filter(line -> line.period().contains(date))
                    .toList();
        }

        private boolean present(Assignment row) {
            return sources.employments().terms(row.employmentId()).stream()
                    .anyMatch(terms -> terms.period().contains(date) && terms.status() == EmploymentStatus.ACTIVE);
        }

        private boolean acting(Assignment row) {
            return row.kindId() != null && sources.refItems().find(row.kindId()).map(RefItem::actingHead)
                    .orElse(false);
        }

        private List<Assignment> headsOf(String orgUnitId) {
            List<Assignment> present = sources.assignments().rowsOn(List.of(orgUnitId), date).stream()
                    .filter(row -> row.head() && present(row))
                    .toList();
            List<Assignment> acting = present.stream().filter(this::acting).toList();
            return acting.isEmpty() ? present : acting;
        }

        // A head looks above the unit, also when an acting head leads it in the head's place.
        private boolean looksAbove(String orgUnitId, String personId) {
            return sources.assignments().rowsOn(List.of(orgUnitId), date).stream()
                    .anyMatch(row -> row.head() && personId.equals(personOf(row)));
        }

        // The heads of the unit, or of the nearest unit above that has a head. A head looks above its own unit.
        List<Assignment> headsAbove(String orgUnitId, String personId) {
            List<OrgUnitState> path = OrgUnitPaths.on(sources.orgUnits(), sources.refItems(),
                    sources.orgUnits().defaultHierarchyId(), orgUnitId, date);
            // A lateral unit is outside the line organization. No line unit is below a lateral unit, so the rest of
            // the path is made of line units.
            if (path.getLast().lateral()) {
                return List.of();
            }
            for (int i = path.size() - 1; i >= 0; i--) {
                String unitId = path.get(i).unit().id();
                // The person is no head of this unit from here on, so none of its heads is the person.
                if (looksAbove(unitId, personId)) {
                    continue;
                }
                List<Assignment> heads = headsOf(unitId);
                if (!heads.isEmpty()) {
                    return heads;
                }
            }
            return List.of();
        }
    }
}
