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
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.ReportingLine;
import net.exoego.keycloak.fuhito.domain.ReportingLine.Kind;
import net.exoego.keycloak.fuhito.domain.ValidPeriod;
import net.exoego.keycloak.fuhito.store.AssignmentStore;
import net.exoego.keycloak.fuhito.store.EmploymentStore;
import net.exoego.keycloak.fuhito.store.OrgUnitStore;
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
            RefItemStore refItems, UserDirectory users) {
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
     * @param managerUserId the ID of the manager
     * @param kind {@code SOLID} or {@code DOTTED}, or null for {@code SOLID}
     * @param validFrom the first date of the line, as {@code YYYY-MM-DD}
     * @param validTo the first date after the line, or null
     * @return the new line
     * @throws FuhitoException when a value is bad, the line is not inside the assignment, the manager is the
     *         person, or the assignment has a solid line on the same dates already
     */
    public ReportingLine add(String assignmentId, String managerUserId, String kind, String validFrom,
            String validTo) {
        Checks.required("assignmentId", assignmentId);
        Checks.required("managerUserId", managerUserId);
        Kind lineKind = Checks.optionalEnum("kind", Kind.class, kind, Kind.SOLID);
        LocalDate from = Checks.date("validFrom", validFrom);
        LocalDate to = Checks.optionalDate("validTo", validTo);
        List<Assignment> rows = sources.assignments().rows(assignmentId);
        if (rows.isEmpty()) {
            throw FuhitoException.invalid("reporting_line.assignment_unknown", Map.of("assignmentId", assignmentId));
        }
        ValidPeriod life = new ValidPeriod(rows.get(0).period().from(), rows.get(rows.size() - 1).period().to());
        ValidPeriod period = Checks.period(from, to == null ? life.to() : to);
        if (!life.encloses(period)) {
            throw FuhitoException.conflict("reporting_line.outside_assignment",
                    Map.of("assignmentId", assignmentId, "validFrom", validFrom));
        }
        if (!sources.users().exists(managerUserId)) {
            throw FuhitoException.invalid("reporting_line.manager_unknown", Map.of("managerUserId", managerUserId));
        }
        if (managerUserId.equals(userOf(rows.get(0)))) {
            throw FuhitoException.invalid("reporting_line.self", Map.of("managerUserId", managerUserId));
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
        ReportingLine line = new ReportingLine(UUID.randomUUID().toString(), assignmentId, managerUserId, lineKind,
                period);
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
     *
     * @param userId the ID of the user
     * @param asOf the date as {@code YYYY-MM-DD}, or null for today
     * @return the managers
     * @throws FuhitoException when the user does not exist
     */
    public List<Reporting> managersOf(String userId, String asOf) {
        LocalDate date = dateOrToday(asOf);
        requireUser(userId);
        Snapshot snapshot = new Snapshot(date);
        List<Reporting> result = new ArrayList<>();
        for (Assignment row : snapshot.rowsOfUser(userId)) {
            List<ReportingLine> lines = snapshot.linesOf(row.assignmentId());
            lines.forEach(line -> result.add(new Reporting(line.managerUserId(), line.kind(), false, line.id(),
                    row.assignmentId(), row.orgUnitId())));
            if (lines.stream().noneMatch(line -> line.kind() == Kind.SOLID)) {
                snapshot.headsAbove(row.orgUnitId(), userId).forEach(head -> result.add(new Reporting(
                        snapshot.userOf(head), Kind.SOLID, true, null, row.assignmentId(), row.orgUnitId())));
            }
        }
        return result;
    }

    /**
     * Lists the direct reports of a user on a date, by the same rules as {@link #managersOf}.
     *
     * @param userId the ID of the user
     * @param asOf the date as {@code YYYY-MM-DD}, or null for today
     * @return the direct reports
     * @throws FuhitoException when the user does not exist
     */
    public List<Reporting> reportsOf(String userId, String asOf) {
        LocalDate date = dateOrToday(asOf);
        requireUser(userId);
        Snapshot snapshot = new Snapshot(date);
        List<Reporting> result = new ArrayList<>();
        for (ReportingLine line : store.findByManager(userId)) {
            if (line.period().contains(date)) {
                snapshot.rowOf(line.assignmentId()).ifPresent(row -> result.add(new Reporting(snapshot.userOf(row),
                        line.kind(), false, line.id(), row.assignmentId(), row.orgUnitId())));
            }
        }
        for (Assignment own : snapshot.rowsOfUser(userId)) {
            if (own.head()) {
                snapshot.collectReports(own.orgUnitId(), true, userId, result);
            }
        }
        return result;
    }

    private String userOf(Assignment row) {
        return sources.employments().find(row.employmentId()).map(Employment::userId).orElse(null);
    }

    private void requireUser(String userId) {
        if (!sources.users().exists(userId)) {
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
        private final OrgUnitTree tree;
        private final Map<String, String> users = new HashMap<>();

        Snapshot(LocalDate date) {
            this.date = date;
            this.tree = new OrgUnitTree(sources.orgUnits(), sources.refItems(), date);
        }

        String userOf(Assignment row) {
            return users.computeIfAbsent(row.employmentId(), id -> ReportingLineService.this.userOf(row));
        }

        List<Assignment> rowsOfUser(String userId) {
            return sources.employments().findByUser(userId).stream()
                    .flatMap(employment -> sources.assignments().rowsOfEmployment(employment.id()).stream())
                    .filter(row -> row.period().contains(date))
                    .toList();
        }

        Optional<Assignment> rowOf(String assignmentId) {
            return sources.assignments().rows(assignmentId).stream()
                    .filter(row -> row.period().contains(date))
                    .findFirst();
        }

        List<ReportingLine> linesOf(String assignmentId) {
            return store.findByAssignment(assignmentId).stream()
                    .filter(line -> line.period().contains(date))
                    .toList();
        }

        private List<Assignment> headsOf(String orgUnitId, String exceptUserId) {
            return sources.assignments().rowsOn(List.of(orgUnitId), date).stream()
                    .filter(row -> row.head() && !exceptUserId.equals(userOf(row)))
                    .toList();
        }

        private boolean isHeadOf(String orgUnitId, String userId) {
            return sources.assignments().rowsOn(List.of(orgUnitId), date).stream()
                    .anyMatch(row -> row.head() && userId.equals(userOf(row)));
        }

        // The heads of the unit, or of the nearest unit above that has a head. A head looks above its own unit.
        List<Assignment> headsAbove(String orgUnitId, String userId) {
            List<OrgUnitState> path = tree.path(orgUnitId);
            for (int i = path.size() - 1; i >= 0; i--) {
                String unitId = path.get(i).unit().id();
                if (isHeadOf(unitId, userId)) {
                    continue;
                }
                List<Assignment> heads = headsOf(unitId, userId);
                if (!heads.isEmpty()) {
                    return heads;
                }
            }
            return List.of();
        }

        // In the unit of the manager and in units below with no head, the members report to the manager.
        // In a unit below that has a head, only its heads do.
        void collectReports(String orgUnitId, boolean top, String managerUserId, List<Reporting> result) {
            List<Assignment> rows = sources.assignments().rowsOn(List.of(orgUnitId), date).stream()
                    .filter(row -> !managerUserId.equals(userOf(row)))
                    .toList();
            List<Assignment> heads = rows.stream().filter(Assignment::head).toList();
            if (!top && !heads.isEmpty()) {
                heads.forEach(head -> addDerived(head, result));
                return;
            }
            rows.stream().filter(row -> !row.head()).forEach(row -> addDerived(row, result));
            tree.children(orgUnitId).forEach(child -> collectReports(child.unit().id(), false, managerUserId,
                    result));
        }

        private void addDerived(Assignment row, List<Reporting> result) {
            if (linesOf(row.assignmentId()).stream().noneMatch(line -> line.kind() == Kind.SOLID)) {
                result.add(new Reporting(userOf(row), Kind.SOLID, true, null, row.assignmentId(),
                        row.orgUnitId()));
            }
        }
    }
}
