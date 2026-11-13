package net.exoego.keycloak.fuhito.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import net.exoego.keycloak.fuhito.domain.Assignment;
import net.exoego.keycloak.fuhito.store.AssignmentStore;

final class MemoryAssignmentStore implements AssignmentStore {

    final List<Assignment> rows = new ArrayList<>();

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
    public List<Assignment> rowsOfOrgUnit(String orgUnitId) {
        return rows.stream()
                .filter(r -> r.orgUnitId().equals(orgUnitId))
                .sorted(Comparator.comparing(r -> r.period().from()))
                .toList();
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
