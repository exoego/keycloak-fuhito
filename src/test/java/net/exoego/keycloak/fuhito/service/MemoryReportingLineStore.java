package net.exoego.keycloak.fuhito.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import net.exoego.keycloak.fuhito.domain.Assignment;
import net.exoego.keycloak.fuhito.domain.DirectReport;
import net.exoego.keycloak.fuhito.domain.Employment;
import net.exoego.keycloak.fuhito.domain.EmploymentStatus;
import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.domain.ReportingLine;
import net.exoego.keycloak.fuhito.store.ReportingLineStore;

final class MemoryReportingLineStore implements ReportingLineStore {

    final List<ReportingLine> lines = new ArrayList<>();

    @Override
    public Optional<ReportingLine> find(String id) {
        return lines.stream().filter(l -> l.id().equals(id)).findFirst();
    }

    @Override
    public List<ReportingLine> findByAssignment(String assignmentId) {
        return lines.stream().filter(l -> l.assignmentId().equals(assignmentId))
                .sorted(Comparator.comparing(l -> l.period().from())).toList();
    }

    @Override
    public List<ReportingLine> findByManager(String managerPersonId) {
        return lines.stream().filter(l -> l.managerPersonId().equals(managerPersonId))
                .sorted(Comparator.comparing(l -> l.period().from())).toList();
    }

    @Override
    public void insert(ReportingLine line) {
        lines.add(line);
    }

    @Override
    public void update(ReportingLine line) {
        lines.replaceAll(l -> l.id().equals(line.id()) ? line : l);
    }

    @Override
    public void delete(String id) {
        lines.removeIf(l -> l.id().equals(id));
    }

    /** The other stores that the reports of a manager come from. A test of reports sets them. */
    MemoryAssignmentStore assignments;
    MemoryEmploymentStore employments;
    MemoryOrgUnitStore orgUnits;
    MemoryRefItemStore refItems = new MemoryRefItemStore();

    private record Found(int depth, DirectReport report) {
    }

    @Override
    public List<DirectReport> reportsOn(String hierarchyId, String managerPersonId, LocalDate date, int first,
            int max) {
        if (assignments == null) {
            return List.of();
        }
        List<Found> found = new ArrayList<>();
        for (ReportingLine line : findByManager(managerPersonId)) {
            if (line.period().contains(date)) {
                rowOn(line.assignmentId(), date).ifPresent(row -> found.add(new Found(0, report(row, line.kind(),
                        false, line.id()))));
            }
        }
        OrgUnitTree tree = new OrgUnitTree(orgUnits, refItems, date, hierarchyId);
        assignments.rows.stream()
                .filter(row -> row.period().contains(date) && managerPersonId.equals(personOf(row))
                        && leads(row, date) && !tree.path(row.orgUnitId()).getLast().group())
                .forEach(own -> collect(tree, own.orgUnitId(), 0, managerPersonId, date, found));
        return found.stream()
                .sorted(Comparator.comparing((Found f) -> f.report().derived())
                        .thenComparingInt(Found::depth)
                        .thenComparing(f -> f.report().orgUnitId())
                        .thenComparing(f -> f.report().assignmentId()))
                .map(Found::report)
                .skip(first)
                .limit(max)
                .toList();
    }

    // In the unit of the manager and in units below with no head, the members report to the manager.
    // In a unit below that has a head, only those who look above it do.
    private void collect(OrgUnitTree tree, String orgUnitId, int depth, String managerPersonId, LocalDate date,
            List<Found> found) {
        List<Assignment> rows = assignments.rowsOn(List.of(orgUnitId), date).stream()
                .filter(row -> !managerPersonId.equals(personOf(row)))
                .toList();
        if (depth > 0 && rows.stream().anyMatch(row -> leads(row, date))) {
            rows.stream().filter(row -> looksAbove(row, date)).forEach(row -> addDerived(row, depth, date, found));
            return;
        }
        rows.stream().filter(row -> depth > 0 || !looksAbove(row, date))
                .forEach(row -> addDerived(row, depth, date, found));
        tree.children(orgUnitId).stream().filter(child -> !child.group())
                .forEach(child -> collect(tree, child.unit().id(), depth + 1, managerPersonId, date, found));
    }

    private void addDerived(Assignment row, int depth, LocalDate date, List<Found> found) {
        boolean solid = findByAssignment(row.assignmentId()).stream()
                .anyMatch(line -> line.kind() == ReportingLine.Kind.SOLID && line.period().contains(date));
        if (!solid) {
            found.add(new Found(depth, report(row, ReportingLine.Kind.SOLID, true, null)));
        }
    }

    private DirectReport report(Assignment row, ReportingLine.Kind kind, boolean derived, String lineId) {
        Employment employment = employments.find(row.employmentId()).orElseThrow();
        return new DirectReport(employment.personId(), employment.userId(), kind, derived, lineId,
                row.assignmentId(), row.orgUnitId());
    }

    private boolean present(Assignment row, LocalDate date) {
        return employments.terms(row.employmentId()).stream()
                .anyMatch(t -> t.period().contains(date) && t.status() == EmploymentStatus.ACTIVE);
    }

    private boolean acting(Assignment row) {
        return row.kindId() != null && refItems.find(row.kindId()).map(RefItem::actingHead).orElse(false);
    }

    private boolean regularPresent(Assignment row, LocalDate date) {
        return assignments.rowsOn(List.of(row.orgUnitId()), date).stream()
                .anyMatch(other -> other.head() && present(other, date) && !acting(other));
    }

    private boolean leads(Assignment row, LocalDate date) {
        return row.head() && present(row, date) && (!acting(row) || !regularPresent(row, date));
    }

    private boolean looksAbove(Assignment row, LocalDate date) {
        return row.head() && !(present(row, date) && acting(row) && regularPresent(row, date));
    }

    private String personOf(Assignment row) {
        return employments.find(row.employmentId()).map(Employment::personId).orElse(null);
    }

    private Optional<Assignment> rowOn(String assignmentId, LocalDate date) {
        return assignments.rows(assignmentId).stream().filter(row -> row.period().contains(date)).findFirst();
    }
}
