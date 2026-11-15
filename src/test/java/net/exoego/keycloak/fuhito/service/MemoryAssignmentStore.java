package net.exoego.keycloak.fuhito.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.exoego.keycloak.fuhito.domain.Assignment;
import net.exoego.keycloak.fuhito.domain.MemberCursor;
import net.exoego.keycloak.fuhito.domain.MemberFilter;
import net.exoego.keycloak.fuhito.domain.ValidPeriod;
import net.exoego.keycloak.fuhito.store.AssignmentStore;

final class MemoryAssignmentStore implements AssignmentStore {

    final List<Assignment> rows = new ArrayList<>();

    /** The stores that a list of members reads besides the rows. A test of members sets them. */
    MemoryOrgUnitStore orgUnits;
    MemoryEmploymentStore employments;

    @Override
    public List<Assignment> membersOn(MemberFilter filter, MemberCursor after, int first, int max) {
        List<String> unitIds = filter.includeDescendants()
                ? orgUnits.treeOn(filter.hierarchyId(), filter.orgUnitId(), filter.date(), 0, Integer.MAX_VALUE)
                        .stream().map(placed -> placed.unit().id()).toList()
                : List.of(filter.orgUnitId());
        Comparator<Assignment> order = Comparator
                .comparingInt((Assignment row) -> unitIds.indexOf(row.orgUnitId()))
                .thenComparingInt(Assignment::displayOrder)
                .thenComparing(row -> row.period().from())
                .thenComparing(Assignment::assignmentId);
        return rowsOn(unitIds, filter.date()).stream()
                .filter(row -> filter.occupationId() == null || filter.occupationId().equals(row.occupationId()))
                .filter(row -> filter.jobTitleId() == null || filter.jobTitleId().equals(row.jobTitleId()))
                .filter(row -> filter.status() == null
                        || employments.termsOn(List.of(row.employmentId()), filter.date()).stream()
                                .anyMatch(terms -> terms.status() == filter.status()))
                .filter(row -> filter.seconded() == null || filter.seconded().wanted() == seconded(row, filter))
                .sorted(order)
                .filter(row -> after == null || order.compare(row, new Assignment(null, after.assignmentId(), null,
                        after.orgUnitId(), ValidPeriod.startingAt(after.validFrom()), null, null, null, false,
                        false, null, after.displayOrder(), Map.of(), null, null, null)) > 0)
                .skip(first)
                .limit(max)
                .toList();
    }

    private boolean seconded(Assignment row, MemberFilter filter) {
        String employerId = employments.findAll(List.of(row.employmentId())).getFirst().employerId();
        return employerId != null && !orgUnits.ancestorsOn(filter.seconded().hierarchyId(),
                List.of(row.orgUnitId()), filter.date()).get(row.orgUnitId()).contains(employerId);
    }

    @Override
    public List<Assignment> rows(String assignmentId) {
        return rows.stream()
                .filter(r -> r.assignmentId().equals(assignmentId))
                .sorted(Comparator.comparing(r -> r.period().from()))
                .toList();
    }

    @Override
    public List<Assignment> rowsOfEmployment(String employmentId) {
        return rows.stream()
                .filter(r -> r.employmentId().equals(employmentId))
                .sorted(Comparator.comparing(r -> r.period().from()))
                .toList();
    }

    @Override
    public long assignmentsHoldingFrom(String orgUnitId, LocalDate date) {
        return rows.stream()
                .filter(r -> r.orgUnitId().equals(orgUnitId) && r.period().endsAfter(date))
                .map(Assignment::assignmentId)
                .distinct()
                .count();
    }

    @Override
    public List<Assignment> rowsOn(Collection<String> orgUnitIds, LocalDate date) {
        return rows.stream().filter(r -> orgUnitIds.contains(r.orgUnitId()) && r.period().contains(date)).toList();
    }

    @Override
    public Optional<String> findAssignmentIdByExternalId(String source, String externalId) {
        return rows.stream()
                .filter(r -> source.equals(r.source()) && externalId.equals(r.externalId()))
                .map(Assignment::assignmentId)
                .findFirst();
    }

    @Override
    public List<Assignment> rowsBySourceOn(String source, LocalDate date) {
        return rows.stream().filter(r -> source.equals(r.source()) && r.period().contains(date)).toList();
    }

    @Override
    public void insert(Assignment row) {
        rows.add(row);
    }

    @Override
    public void update(Assignment row) {
        rows.replaceAll(r -> r.rowId().equals(row.rowId()) ? row : r);
    }

    @Override
    public void delete(String rowId) {
        rows.removeIf(r -> r.rowId().equals(rowId));
    }
}
